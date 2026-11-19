package com.defold.mangler;

import com.defold.mangler.Lexer.Kind;
import com.defold.mangler.Lexer.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-pass reader for one Lua file. Sorts identifier tokens into the classes the mangler cares about:
 * locals with their scope-resolved uses, global references, field references, and the sets of names the file
 * defines or pins. Follows the shape of the grammar and tracks lexical scopes, but builds no syntax tree:
 * scope resolution is the only thing renaming needs.
 *
 * Throws IllegalArgumentException on Lua this class does not understand.
 */
public final class Scan {

    /** A local variable. Every declaration and reference is tracked by scope, so renaming it is always safe. */
    static final class Local {
        final String name;
        String root;  // global this local aliases (`local go = go`), if any
        final List<Token> uses = new ArrayList<>();  // the declaration and every reference

        Local(String name) {
            this.name = name;
        }
    }

    /**
     * A field access: `x.name`, `x:name`, `{ name = ... }`, `function x.name()`. {@code root} is the global
     * the chain hangs off (`rand` in `rand.ffi_ptrs().entropy`), or null when the chain starts from a local, a
     * literal or an expression. The mangler skips a field whose root is a global the project never assigns,
     * because such a name is looked up by the engine, not by Lua (`go.delete`, `socket.http.request`).
     */
    record FieldRef(Token token, String root) {}

    final List<Local> locals = new ArrayList<>();
    final List<Token> globalRefs = new ArrayList<>();    // variables that resolve to a global
    final List<FieldRef> fieldRefs = new ArrayList<>();  // x.name, x:name, { name = ... }, function x.name()
    final Set<String> definedGlobals = new HashSet<>();  // globals this file assigns to
    final Set<String> definedFields = new HashSet<>();   // field names this file assigns to
    final Set<String> strings = new HashSet<>();         // contents of table-key string literals

    public static Scan of(String source) {
        Scan s = new Scan(Lexer.lex(source));
        s.block();
        if (s.current().kind() != Kind.EOF) throw s.error("unexpected symbol");
        return s;
    }

    // What an expression turned out to be: the root global of a lone `global.a.b` chain.
    private record Ex(String root) {
        static final Ex OTHER = new Ex(null);
    }

    // The tail of a suffixed expression (`a`, `a.b`, `f().c`): what assignments need to know.
    private record Chain(Token var, Local local, Token field, String root) {}

    private static final class Scope {
        final Scope parent;
        final Map<String, Local> vars = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    // Every infix operator. Precedence is irrelevant to scoping, so the parser only needs the set.
    private static final Set<String> BINARY = Set.of(
        "+", "-", "*", "/", "%", "^", "..", "==", "~=", "<", "<=", ">", ">=", "and", "or"
    );

    private final List<Token> toks;
    private int pos;
    private Scope scope = new Scope(null);

    private Scan(List<Token> toks) {
        this.toks = toks;
    }

    // ---- token helpers ------------------------------------------------------------------------------------

    private Token current() {
        return toks.get(pos);
    }

    private boolean is(String text) {
        return current().text().equals(text);
    }

    private boolean accept(String text) {
        if (!is(text)) return false;
        pos++;
        return true;
    }

    private void expect(String text) {
        if (!accept(text)) throw error("'" + text + "' expected");
    }

    private Token name() {
        Token t = current();
        if (t.kind() != Kind.NAME) throw error("name expected");
        pos++;
        return t;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " near '" + current().text() + "' at offset " + current().start());
    }

    // ---- scopes and facts ---------------------------------------------------------------------------------

    private Local declare(Token t) {
        Local l = new Local(t.text());
        l.uses.add(t);
        scope.vars.put(l.name, l);
        locals.add(l);
        return l;
    }

    private Local lookup(String name) {
        for (Scope s = scope; s != null; s = s.parent) {
            Local l = s.vars.get(name);
            if (l != null) return l;
        }
        return null;
    }

    /** Records a variable reference and returns the global it stands for, if any. */
    private String use(Token t, Local local) {
        if (local == null) {
            globalRefs.add(t);
            return t.text();
        }
        local.uses.add(t);
        return local.root;
    }

    private void field(Token t, String root) {
        fieldRefs.add(new FieldRef(t, root));
    }

    /** If {@code t} is a quoted string literal, its contents could name a table key, so record them. */
    private void trackKeyString(Token t) {
        if (t.kind() != Kind.STRING) return;
        String x = t.text();
        if (x.startsWith("\"") || x.startsWith("'")) strings.add(x.substring(1, x.length() - 1));
    }

    // ---- statements ---------------------------------------------------------------------------------------

    private boolean atBlockEnd() {
        return is("end") || is("else") || is("elseif") || is("until") || current().kind() == Kind.EOF;
    }

    private void block() {
        while (!atBlockEnd()) {
            if (accept("return")) {
                if (!atBlockEnd() && !is(";")) exprList();
                accept(";");
                return;
            }
            statement();
        }
    }

    private void scoped() {
        scope = new Scope(scope);
        block();
        scope = scope.parent;
    }

    private void statement() {
        if (accept(";")) return;
        if (accept("if")) {
            expr();
            expect("then");
            scoped();
            while (accept("elseif")) {
                expr();
                expect("then");
                scoped();
            }
            if (accept("else")) scoped();
            expect("end");
        } else if (accept("while")) {
            expr();
            expect("do");
            scoped();
            expect("end");
        } else if (accept("do")) {
            scoped();
            expect("end");
        } else if (accept("for")) {
            forStat();
        } else if (accept("repeat")) {
            scope = new Scope(scope);  // the `until` expression can see the body's locals
            block();
            expect("until");
            expr();
            scope = scope.parent;
        } else if (accept("function")) {
            functionStat();
        } else if (accept("local")) {
            localStat();
        } else if (accept("::")) {
            name();
            expect("::");
        } else if (accept("goto")) {
            name();
        } else if (!accept("break")) {
            assignOrCall();
        }
    }

    private void forStat() {
        List<Token> vars = new ArrayList<>();
        vars.add(name());
        if (accept("=")) {
            expr();
            expect(",");
            expr();
            if (accept(",")) expr();
        } else {
            while (accept(",")) vars.add(name());
            expect("in");
            exprList();
        }
        expect("do");
        scope = new Scope(scope);
        for (Token v : vars) declare(v);
        block();
        scope = scope.parent;
        expect("end");
    }

    private void functionStat() {
        Token head = name();
        Local local = lookup(head.text());
        String root = use(head, local);
        Token last = null;
        boolean method = false;
        while (!method && (is(".") || is(":"))) {
            method = is(":");
            pos++;
            last = name();
            field(last, root);
        }
        if (last != null) {
            definedFields.add(last.text());
        } else if (local == null) {
            definedGlobals.add(head.text());
        }
        body(method);
    }

    private void localStat() {
        if (accept("function")) {
            declare(name());  // declared before the body, so the function can recurse
            body(false);
            return;
        }
        List<Token> names = new ArrayList<>();
        names.add(name());
        while (accept(",")) names.add(name());
        List<Ex> values = accept("=") ? exprList() : List.of();  // evaluated before the names come into scope
        for (int i = 0; i < names.size(); i++) {
            Local l = declare(names.get(i));
            if (i < values.size()) l.root = values.get(i).root();
        }
    }

    private void assignOrCall() {
        List<Chain> targets = new ArrayList<>();
        targets.add(suffixed());
        while (accept(",")) targets.add(suffixed());
        if (!accept("=")) return;  // it was a call statement
        List<Ex> values = exprList();
        for (int i = 0; i < targets.size(); i++) {
            Chain t = targets.get(i);
            if (t.var() != null && t.local() != null) {
                // A later assignment may be the first one to reveal what the local really is.
                if (i < values.size() && t.local().root == null) t.local().root = values.get(i).root();
            } else if (t.var() != null) {
                definedGlobals.add(t.var().text());
            } else if (t.field() != null) {
                definedFields.add(t.field().text());
            }
        }
    }

    // ---- expressions --------------------------------------------------------------------------------------

    private List<Ex> exprList() {
        List<Ex> out = new ArrayList<>();
        out.add(expr());
        while (accept(",")) out.add(expr());
        return out;
    }

    /** An expression is just operands joined by operators; precedence does not affect scoping. */
    private Ex expr() {
        boolean lone = true;
        Ex first = Ex.OTHER;
        for (;;) {
            while (is("not") || is("-") || is("#")) {
                pos++;
                lone = false;
            }
            Ex operand = operand();
            if (lone) first = operand;
            if (!BINARY.contains(current().text())) return lone ? first : Ex.OTHER;
            pos++;
            lone = false;
        }
    }

    private Ex operand() {
        Kind k = current().kind();
        if (k == Kind.NUMBER || k == Kind.STRING) {
            pos++;
            return Ex.OTHER;
        }
        if (k == Kind.NAME || is("(")) return new Ex(suffixed().root());
        if (accept("function")) {
            body(false);
            return Ex.OTHER;
        }
        if (is("{")) {
            table();
            return Ex.OTHER;
        }
        if (accept("nil") || accept("true") || accept("false") || accept("...")) return Ex.OTHER;
        throw error("unexpected symbol");
    }

    private Chain suffixed() {
        Token var = null;
        Local local = null;
        Token member = null;
        String root = null;
        if (current().kind() == Kind.NAME) {
            var = name();
            local = lookup(var.text());
            root = use(var, local);
        } else {
            expect("(");
            Ex inner = expr();
            expect(")");
            root = inner.root();  // `(rand.ffi_ptrs()).x` is still part of `rand`
        }
        for (;;) {
            if (accept(".")) {
                member = name();
                field(member, root);  // `root` survives, so `socket.http.request` is still part of `socket`
                var = null;
                local = null;
            } else if (accept(":")) {
                field(name(), root);
                var = null; local = null; member = null;
                args();
                if ("require".equals(root)) root = null;  // require("x"):x() returns a project module
            } else if (accept("[")) {
                trackKeyString(current());  // `t["name"]` pins `name`, since `t.name` may be renamed
                expr();
                expect("]");
                var = null; local = null; member = null;
                if ("require".equals(root)) root = null;
            } else if (is("(") || is("{") || current().kind() == Kind.STRING) {
                args();
                var = null; local = null; member = null;
                // require("m") returns a project-owned module; other calls keep their root, so fields
                // reached through engine accessors (`rand.ffi_ptrs().entropy`) stay external.
                if ("require".equals(root)) root = null;
            } else {
                return new Chain(var, local, member, root);
            }
        }
    }

    private void args() {
        if (accept("(")) {
            if (!is(")")) exprList();
            expect(")");
        } else if (is("{")) {
            table();
        } else if (current().kind() == Kind.STRING) {
            pos++;
        } else {
            throw error("function arguments expected");
        }
    }

    private void table() {
        expect("{");
        while (!is("}")) {
            if (accept("[")) {
                trackKeyString(current());  // `{["name"] = ...}` pins `name`
                expr();
                expect("]");
                expect("=");
                expr();
            } else if (current().kind() == Kind.NAME && toks.get(pos + 1).text().equals("=")) {
                Token key = name();
                pos++;
                field(key, null);
                definedFields.add(key.text());
                expr();
            } else {
                expr();
            }
            if (!accept(",") && !accept(";")) break;
        }
        expect("}");
    }

    private void body(boolean method) {
        expect("(");
        scope = new Scope(scope);
        if (method) scope.vars.put("self", new Local("self"));  // implicit parameter; nothing to rename
        if (!is(")")) {
            do {
                if (!accept("...")) declare(name());
            } while (accept(","));
        }
        expect(")");
        block();
        expect("end");
        scope = scope.parent;
    }
}
