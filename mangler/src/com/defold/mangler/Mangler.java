package com.defold.mangler;

import com.defold.mangler.Lexer.Tok;
import com.defold.mangler.Lexer.T;
import com.defold.mangler.Parser.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Tracks every occurrence of one variable/function name; eligible once seen holding a function value. */
final class Binding {
    enum Kind { LOCAL, GLOBAL }
    
    final String name;
    final Kind kind;
    boolean eligible = false;
    final List<Tok> occurrences = new ArrayList<>();

    Binding(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }
}

/** One lexical block scope; local bindings shadow outer ones by name. */
final class Scope {
    final Scope parent;
    private final Map<String, Binding> locals = new HashMap<>();

    Scope(Scope parent) {
        this.parent = parent;
    }

    void declare(String name, Binding b) {
        locals.put(name, b);
    }

    Binding resolveLocal(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            Binding b = s.locals.get(name);
            if (b != null) return b;
        }
        return null;
    }
}

/**
 * Walks the AST, resolving scope and flagging function-valued bindings.
 *
 * Every function identifier mangles to the same hash-derived name wherever it occurs in the build, so a dotted
 * or method function name (`M.greet`, `obj:method`) is just as safe to rename as a plain local/global -- AS LONG
 * AS the base table is one of the project's own local tables, not an engine/stdlib/third-party one we don't
 * control. "base resolves to a local variable in this file" is used as that line between "ours" and "external";
 * anything else is left alone, and any remaining risky case can be hand-excluded via mangler.config.
 */
final class ScopeResolver {
    private final Map<String, Binding> globals = new HashMap<>();
    private final List<Binding> all = new ArrayList<>();

    List<Binding> resolve(Block chunk) {
        visitBlock(chunk, new Scope(null));
        return all;
    }

    private Binding newLocal(String name) {
        Binding b = new Binding(name, Binding.Kind.LOCAL);
        all.add(b);
        return b;
    }

    private Binding globalByText(String name) {
        return globals.computeIfAbsent(name, n -> {
            Binding b = new Binding(n, Binding.Kind.GLOBAL);
            all.add(b);
            return b;
        });
    }

    private void visitBlock(Block block, Scope parent) {
        Scope scope = new Scope(parent);
        for (Node s : block.stmts) visitStat(s, scope);
    }

    private void visitStatInScope(Block block, Scope scope) {
        for (Node s : block.stmts) visitStat(s, scope);
    }

    private void visitStat(Node stmt, Scope scope) {
        if (stmt instanceof LocalFunctionStat) {
            LocalFunctionStat s = (LocalFunctionStat) stmt;
            Binding b = newLocal(s.name.text);
            b.eligible = true;
            b.occurrences.add(s.name);
            scope.declare(s.name.text, b);
            visitFunctionBody(s.body, scope);
        } else if (stmt instanceof FunctionStat) {
            FunctionStat s = (FunctionStat) stmt;
            if (s.path.size() == 1 && !s.isMethod) {
                Binding b = resolveOrCreateGlobal(scope, s.path.get(0).text);
                b.eligible = true;
                b.occurrences.add(s.path.get(0));
            } else {
                Tok first = s.path.get(0);
                Binding baseBinding = scope.resolveLocal(first.text);
                referenceName(scope, first);
                if (baseBinding != null) {
                    Tok funcName = s.path.get(s.path.size() - 1);
                    Binding b = globalByText(funcName.text);
                    b.eligible = true;
                    b.occurrences.add(funcName);
                }
            }
            visitFunctionBody(s.body, scope);
        } else if (stmt instanceof LocalStat) {
            LocalStat s = (LocalStat) stmt;
            for (Node e : s.exprs) visitExpr(e, scope);
            for (int i = 0; i < s.names.size(); i++) {
                Tok name = s.names.get(i);
                Node init = i < s.exprs.size() ? s.exprs.get(i) : null;
                Binding b = newLocal(name.text);
                b.occurrences.add(name);
                if (init instanceof FunctionExpr) b.eligible = true;
                scope.declare(name.text, b);
            }
        } else if (stmt instanceof AssignOrCallStat) {
            visitAssignOrCall((AssignOrCallStat) stmt, scope);
        } else if (stmt instanceof DoStat) {
            visitBlock(((DoStat) stmt).body, scope);
        } else if (stmt instanceof WhileStat) {
            WhileStat s = (WhileStat) stmt;
            visitExpr(s.cond, scope);
            visitBlock(s.body, scope);
        } else if (stmt instanceof RepeatStat) {
            RepeatStat s = (RepeatStat) stmt;
            Scope body = new Scope(scope);
            visitStatInScope(s.body, body);
            visitExpr(s.cond, body); // `until` can see locals declared in the body
        } else if (stmt instanceof IfStat) {
            IfStat s = (IfStat) stmt;
            for (Node c : s.conds) visitExpr(c, scope);
            for (Block b : s.blocks) visitBlock(b, scope);
            if (s.elseBlock != null) visitBlock(s.elseBlock, scope);
        } else if (stmt instanceof NumericForStat) {
            NumericForStat s = (NumericForStat) stmt;
            visitExpr(s.start, scope);
            visitExpr(s.stop, scope);
            if (s.step != null) visitExpr(s.step, scope);
            Scope loop = new Scope(scope);
            loop.declare(s.var.text, newLocal(s.var.text));
            visitStatInScope(s.body, loop);
        } else if (stmt instanceof GenericForStat) {
            GenericForStat s = (GenericForStat) stmt;
            for (Node e : s.exprs) visitExpr(e, scope);
            Scope loop = new Scope(scope);
            for (Tok n : s.names) loop.declare(n.text, newLocal(n.text));
            visitStatInScope(s.body, loop);
        } else if (stmt instanceof ReturnStat) {
            for (Node e : ((ReturnStat) stmt).exprs) visitExpr(e, scope);
        }
    }

    private void visitAssignOrCall(AssignOrCallStat s, Scope scope) {
        for (Node e : s.rhs) visitExpr(e, scope);
        if (s.isCall) {
            visitExpr(s.lhs.get(0), scope);
            return;
        }
        for (int i = 0; i < s.lhs.size(); i++) {
            Node target = s.lhs.get(i);
            Node rhsExpr = i < s.rhs.size() ? s.rhs.get(i) : null;
            if (target instanceof NameExpr) {
                Tok tok = ((NameExpr) target).tok;
                Binding b = scope.resolveLocal(tok.text);
                if (b == null) b = resolveOrCreateGlobal(scope, tok.text);
                b.occurrences.add(tok);
                if (rhsExpr instanceof FunctionExpr) b.eligible = true;
            } else if (target instanceof IndexExpr) {
                IndexExpr ix = (IndexExpr) target;
                visitExpr(ix.base, scope);
                if (ix.indexExpr != null) visitExpr(ix.indexExpr, scope);
            }
        }
    }

    private void visitFunctionBody(FunctionBody fb, Scope outer) {
        Scope fnScope = new Scope(outer);
        for (Tok p : fb.params) fnScope.declare(p.text, newLocal(p.text));
        visitStatInScope(fb.block, fnScope);
    }

    private void visitExpr(Node e, Scope scope) {
        if (e == null) return;
        if (e instanceof NameExpr) {
            referenceName(scope, ((NameExpr) e).tok);
        } else if (e instanceof IndexExpr) {
            IndexExpr ix = (IndexExpr) e;
            visitExpr(ix.base, scope);
            if (ix.indexExpr != null) visitExpr(ix.indexExpr, scope);
        } else if (e instanceof CallExpr) {
            CallExpr c = (CallExpr) e;
            if (c.base instanceof IndexExpr && ((IndexExpr) c.base).field != null) {
                IndexExpr ix = (IndexExpr) c.base;
                visitExpr(ix.base, scope);
                if (ix.base instanceof NameExpr && scope.resolveLocal(((NameExpr) ix.base).tok.text) != null) {
                    Binding b = globalByText(ix.field.text);
                    b.eligible = true;
                    b.occurrences.add(ix.field);
                }
            } else {
                visitExpr(c.base, scope);
            }
            for (Node a : c.args) visitExpr(a, scope);
        } else if (e instanceof MethodCallExpr) {
            MethodCallExpr m = (MethodCallExpr) e;
            visitExpr(m.base, scope);
            if (m.base instanceof NameExpr && scope.resolveLocal(((NameExpr) m.base).tok.text) != null) {
                Binding b = globalByText(m.method.text);
                b.eligible = true;
                b.occurrences.add(m.method);
            }
            for (Node a : m.args) visitExpr(a, scope);
        } else if (e instanceof ParenExpr) {
            visitExpr(((ParenExpr) e).inner, scope);
        } else if (e instanceof FunctionExpr) {
            visitFunctionBody(((FunctionExpr) e).body, scope);
        } else if (e instanceof BinExpr) {
            visitExpr(((BinExpr) e).left, scope);
            visitExpr(((BinExpr) e).right, scope);
        } else if (e instanceof UnExpr) {
            visitExpr(((UnExpr) e).operand, scope);
        } else if (e instanceof TableExpr) {
            for (TableField f : ((TableExpr) e).fields) {
                if (f.keyExpr != null) visitExpr(f.keyExpr, scope);
                if (f.key != null && f.value instanceof FunctionExpr) {
                    Binding b = globalByText(f.key.text);
                    b.eligible = true;
                    b.occurrences.add(f.key);
                }
                visitExpr(f.value, scope);
            }
        }
    }

    private void referenceName(Scope scope, Tok tok) {
        Binding b = scope.resolveLocal(tok.text);
        if (b == null) b = globalByText(tok.text);
        b.occurrences.add(tok);
    }

    private Binding resolveOrCreateGlobal(Scope scope, String name) {
        Binding local = scope.resolveLocal(name);
        return local != null ? local : globalByText(name);
    }
}

/**
 * Original-name -> replacement, via XXH3-64 keyed by one build-wide seed. Globals (and project-local
 * table/method function names, which share the same renaming rule) are a pure function of (seed, name), so the
 * same call anywhere in the build mangles identically. Locals additionally avoid colliding with any name already
 * used in their own file -- safe, since locals are never visible outside it.
 */
final class NameGenerator {
    private final long seed;

    NameGenerator(long seed) {
        this.seed = seed;
    }

    String mangleGlobal(String name) {
        return "_" + Long.toHexString(XXH3.hash64(name, seed));
    }

    String mangleLocal(String name, Set<String> reserved) {
        int salt = 0;
        String candidate = mangleGlobal(name);
        while (reserved.contains(candidate)) {
            candidate = "_" + Long.toHexString(XXH3.hash64(name + '\u0000' + (++salt), seed));
        }
        reserved.add(candidate);
        return candidate;
    }
}

/** Optional mangler.config: `seed = N` to pin the build seed, `exclude = a, b, c` to add exclusions. */
final class MangleConfig {
    static final Set<String> DEFAULT_EXCLUDED = new HashSet<>(Arrays.asList(
            "init", "final", "update", "fixed_update", "on_message", "on_input", "on_reload", "main"));

    final Set<String> excluded = new HashSet<>(DEFAULT_EXCLUDED);
    Long pinnedSeed = null;

    static MangleConfig loadOrDefault(Path file) {
        MangleConfig cfg = new MangleConfig();
        if (file == null || !Files.exists(file)) return cfg;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int hash = raw.indexOf('#');
                String line = (hash < 0 ? raw : raw.substring(0, hash)).trim();
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.equals("seed")) {
                    try {
                        cfg.pinnedSeed = Long.parseUnsignedLong(value);
                    } catch (NumberFormatException ignored) {}
                } else if (key.startsWith("exclude")) {
                    for (String n : value.split(",")) {
                        String t = n.trim();
                        if (!t.isEmpty()) cfg.excluded.add(t);
                    }
                }
            }
        } catch (IOException ignored) {}
        return cfg;
    }
}

/** Entry point: tokenize -> parse -> resolve scope -> allocate names -> splice source text. */
public final class Mangler {

    public static final class Result {
        public final String output;
        public final int renamedCount;

        public Result(String output, int renamedCount) {
            this.output = output;
            this.renamedCount = renamedCount;
        }
    }

    private final long seed;
    private final Set<String> excluded;

    public Mangler(long seed, Set<String> excluded) {
        this.seed = seed;
        this.excluded = excluded != null ? excluded : MangleConfig.DEFAULT_EXCLUDED;
    }

    /**
     * Names of global/project-table functions this one file, alone, can tell are functions. Union these across
     * every file in the build before calling {@link #mangle(String, Set)}, so a file that only *calls* such a
     * function (and never defines it) still renames that call site.
     */
    public static Set<String> scanGlobalFunctionNames(String source) {
        try {
            List<Binding> bindings = new ScopeResolver().resolve(new Parser(new Lexer(source).tokenize()).parseChunk());
            Set<String> names = new HashSet<>();
            for (Binding b : bindings) {
                if (b.kind == Binding.Kind.GLOBAL && b.eligible) names.add(b.name);
            }
            return names;
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
    }

    public Result mangle(String source) {
        return mangle(source, Collections.emptySet());
    }

    /** Never throws on malformed input: returns the source unchanged rather than risk breaking the build. */
    public Result mangle(String source, Set<String> knownGlobalFunctionNames) {
        List<Tok> tokens;
        Block chunk;
        try {
            tokens = new Lexer(source).tokenize();
            chunk = new Parser(tokens).parseChunk();
        } catch (RuntimeException e) {
            return new Result(source, 0);
        }

        List<Binding> bindings = new ScopeResolver().resolve(chunk);
        Set<String> reserved = new HashSet<>();
        for (Tok t : tokens) {
            if (t.type == T.NAME) reserved.add(t.text);
        }

        NameGenerator gen = new NameGenerator(seed);
        List<int[]> spans = new ArrayList<>(); // [start, end] for sort/splice
        List<String> replacements = new ArrayList<>();
        int renamed = 0;

        for (Binding b : bindings) {
            if (b.kind != Binding.Kind.GLOBAL) continue;
            boolean isFn = b.eligible || knownGlobalFunctionNames.contains(b.name);
            if (!isFn || excluded.contains(b.name)) continue;
            String newName = gen.mangleGlobal(b.name);
            reserved.add(newName);
            for (Tok t : b.occurrences) {
                spans.add(new int[]{t.start, t.end});
                replacements.add(newName);
            }
            renamed++;
        }
        
        for (Binding b : bindings) {
            if (b.kind != Binding.Kind.LOCAL || !b.eligible || excluded.contains(b.name)) continue;
            String newName = gen.mangleLocal(b.name, reserved);
            for (Tok t : b.occurrences) {
                spans.add(new int[]{t.start, t.end});
                replacements.add(newName);
            }
            renamed++;
        }

        return new Result(splice(source, spans, replacements), renamed);
    }

    private static String splice(String source, List<int[]> spans, List<String> replacements) {
        if (spans.isEmpty()) return source;
        Integer[] order = new Integer[spans.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(spans.get(b)[0], spans.get(a)[0])); // back-to-front
        StringBuilder sb = new StringBuilder(source);
        int lastStart = Integer.MAX_VALUE;
        for (int i : order) {
            int[] span = spans.get(i);
            if (span[0] >= lastStart) continue;
            sb.replace(span[0], span[1], replacements.get(i));
            lastStart = span[0];
        }
        return sb.toString();
    }
}
