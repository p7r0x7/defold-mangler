package com.defold.mangler;

import com.defold.mangler.Lexer.T;
import com.defold.mangler.Lexer.Tok;

import java.util.ArrayList;
import java.util.List;

/** Minimal Lua AST + recursive-descent parser: just enough structure to resolve scope for function identifiers. */
public final class Parser {

    public static abstract class Node {}

    public static final class Block extends Node {
        public final List<Node> stmts = new ArrayList<>();
    }

    public static final class LocalFunctionStat extends Node {
        public Tok name;
        public FunctionBody body;
    }

    public static final class FunctionStat extends Node {
        public final List<Tok> path = new ArrayList<>();
        public boolean isMethod;
        public FunctionBody body;
    }

    public static final class LocalStat extends Node {
        public final List<Tok> names = new ArrayList<>();
        public final List<Node> exprs = new ArrayList<>();
    }

    public static final class AssignOrCallStat extends Node {
        public final List<Node> lhs = new ArrayList<>();
        public final List<Node> rhs = new ArrayList<>();
        public boolean isCall;
    }

    public static final class DoStat extends Node {
        public Block body;
    }

    public static final class WhileStat extends Node {
        public Node cond;
        public Block body;
    }

    public static final class RepeatStat extends Node {
        public Block body;
        public Node cond;
    }

    public static final class IfStat extends Node {
        public final List<Node> conds = new ArrayList<>();
        public final List<Block> blocks = new ArrayList<>();
        public Block elseBlock;
    }

    public static final class NumericForStat extends Node {
        public Tok var;
        public Node start, stop, step;
        public Block body;
    }

    public static final class GenericForStat extends Node {
        public final List<Tok> names = new ArrayList<>();
        public final List<Node> exprs = new ArrayList<>();
        public Block body;
    }

    public static final class ReturnStat extends Node {
        public final List<Node> exprs = new ArrayList<>();
    }

    public static final class BreakStat extends Node {}
    public static final class GotoStat extends Node {}
    public static final class LabelStat extends Node {}

    public static final class NilExpr extends Node {}
    public static final class TrueExpr extends Node {}
    public static final class FalseExpr extends Node {}
    public static final class VarargExpr extends Node {}
    public static final class NumberExpr extends Node {}
    public static final class StringExpr extends Node {}
    public static final class NameExpr extends Node {
        public Tok tok;
    }

    /** base.field (field = table key, never renamed) or base[expr]. */
    public static final class IndexExpr extends Node {
        public Node base;
        public Tok field;
        public Node indexExpr;
    }

    public static final class CallExpr extends Node {
        public Node base;
        public final List<Node> args = new ArrayList<>();
    }

    /** base:method(args) -- method name is a table key. */
    public static final class MethodCallExpr extends Node {
        public Node base;
        public Tok method;
        public final List<Node> args = new ArrayList<>();
    }

    public static final class ParenExpr extends Node {
        public Node inner;
    }

    public static final class FunctionExpr extends Node {
        public FunctionBody body;
    }

    public static final class BinExpr extends Node {
        public Node left, right;
    }

    public static final class UnExpr extends Node {
        public Node operand;
    }

    public static final class TableField extends Node {
        public Tok key;
        public Node keyExpr;
        public Node value;
    }

    public static final class TableExpr extends Node {
        public final List<TableField> fields = new ArrayList<>();
    }

    public static final class FunctionBody extends Node {
        public final List<Tok> params = new ArrayList<>();
        public Block block;
    }

    public static final class ParseException extends RuntimeException {
        ParseException(String m) {
            super(m);
        }
    }

    private final List<Tok> toks;
    private int pos = 0;

    public Parser(List<Tok> toks) {
        this.toks = toks;
    }

    public Block parseChunk() {
        Block b = parseBlock();
        expect(T.EOF);
        return b;
    }

    private Tok cur() {
        return toks.get(pos);
    }

    private Tok peekAt(int o) {
        int p = pos + o;
        return p < toks.size() ? toks.get(p) : toks.get(toks.size() - 1);
    }

    private boolean check(T t) {
        return cur().type == t;
    }

    private Tok advance() {
        Tok t = cur();
        if (t.type != T.EOF) pos++;
        return t;
    }

    private Tok expect(T t) {
        if (!check(t)) throw new ParseException("expected " + t + " got " + cur().type);
        return advance();
    }

    private boolean match(T t) {
        if (check(t)) {
            advance();
            return true;
        }
        return false;
    }

    private static boolean isBlockEnd(T t) {
        return t == T.EOF || t == T.END || t == T.ELSE || t == T.ELSEIF || t == T.UNTIL;
    }

    private Block parseBlock() {
        Block b = new Block();
        while (!isBlockEnd(cur().type)) {
            if (check(T.RETURN)) {
                b.stmts.add(parseReturn());
                break;
            }
            Node s = parseStatement();
            if (s != null) b.stmts.add(s);
        }
        return b;
    }

    private Node parseReturn() {
        expect(T.RETURN);
        ReturnStat r = new ReturnStat();
        if (!isBlockEnd(cur().type) && !check(T.SEMI)) {
            r.exprs.add(parseExpr());
            while (match(T.COMMA)) r.exprs.add(parseExpr());
        }
        match(T.SEMI);
        return r;
    }

    private Node parseStatement() {
        switch (cur().type) {
            case SEMI:
                advance();
                return null;
            case IF:
                return parseIf();
            case WHILE: {
                advance();
                WhileStat s = new WhileStat();
                s.cond = parseExpr();
                expect(T.DO);
                s.body = parseBlock();
                expect(T.END);
                return s;
            }
            case DO: {
                advance();
                DoStat d = new DoStat();
                d.body = parseBlock();
                expect(T.END);
                return d;
            }
            case FOR:
                return parseFor();
            case REPEAT: {
                advance();
                RepeatStat s = new RepeatStat();
                s.body = parseBlock();
                expect(T.UNTIL);
                s.cond = parseExpr();
                return s;
            }
            case FUNCTION:
                return parseFunctionStat();
            case LOCAL:
                return parseLocal();
            case DCOLON:
                advance();
                expect(T.NAME);
                expect(T.DCOLON);
                return new LabelStat();
            case BREAK:
                advance();
                return new BreakStat();
            case GOTO:
                advance();
                expect(T.NAME);
                return new GotoStat();
            default:
                return parseAssignOrCall();
        }
    }

    private Node parseIf() {
        IfStat s = new IfStat();
        expect(T.IF);
        s.conds.add(parseExpr());
        expect(T.THEN);
        s.blocks.add(parseBlock());
        while (check(T.ELSEIF)) {
            advance();
            s.conds.add(parseExpr());
            expect(T.THEN);
            s.blocks.add(parseBlock());
        }
        if (match(T.ELSE)) s.elseBlock = parseBlock();
        expect(T.END);
        return s;
    }

    private Node parseFor() {
        expect(T.FOR);
        Tok first = expect(T.NAME);
        if (check(T.ASSIGN)) {
            advance();
            NumericForStat s = new NumericForStat();
            s.var = first;
            s.start = parseExpr();
            expect(T.COMMA);
            s.stop = parseExpr();
            if (match(T.COMMA)) s.step = parseExpr();
            expect(T.DO);
            s.body = parseBlock();
            expect(T.END);
            return s;
        }
        GenericForStat s = new GenericForStat();
        s.names.add(first);
        while (match(T.COMMA)) s.names.add(expect(T.NAME));
        expect(T.IN);
        s.exprs.add(parseExpr());
        while (match(T.COMMA)) s.exprs.add(parseExpr());
        expect(T.DO);
        s.body = parseBlock();
        expect(T.END);
        return s;
    }

    private Node parseFunctionStat() {
        expect(T.FUNCTION);
        FunctionStat s = new FunctionStat();
        s.path.add(expect(T.NAME));
        while (check(T.DOT)) {
            advance();
            s.path.add(expect(T.NAME));
        }
        if (match(T.COLON)) {
            s.isMethod = true;
            s.path.add(expect(T.NAME));
        }
        s.body = parseFunctionBody(s.isMethod);
        return s;
    }

    private Node parseLocal() {
        expect(T.LOCAL);
        if (match(T.FUNCTION)) {
            LocalFunctionStat s = new LocalFunctionStat();
            s.name = expect(T.NAME);
            s.body = parseFunctionBody(false);
            return s;
        }
        LocalStat s = new LocalStat();
        s.names.add(expect(T.NAME));
        skipAttrib();
        while (match(T.COMMA)) {
            s.names.add(expect(T.NAME));
            skipAttrib();
        }
        if (match(T.ASSIGN)) {
            s.exprs.add(parseExpr());
            while (match(T.COMMA)) s.exprs.add(parseExpr());
        }
        return s;
    }

    private void skipAttrib() {
        if (check(T.LT)) {
            advance();
            expect(T.NAME);
            expect(T.GT);
        }
    }

    private FunctionBody parseFunctionBody(boolean isMethod) {
        FunctionBody fb = new FunctionBody();
        expect(T.LPAREN);
        if (isMethod) fb.params.add(new Tok(T.NAME, "self", -1, -1));
        if (!check(T.RPAREN)) {
            parseParam(fb);
            while (match(T.COMMA)) parseParam(fb);
        }
        expect(T.RPAREN);
        fb.block = parseBlock();
        expect(T.END);
        return fb;
    }

    private void parseParam(FunctionBody fb) {
        if (check(T.ELLIPSIS)) {
            advance();
            return;
        }
        fb.params.add(expect(T.NAME));
    }

    private Node parseAssignOrCall() {
        Node first = parseSuffixedExpr();
        if (check(T.ASSIGN) || check(T.COMMA)) {
            AssignOrCallStat s = new AssignOrCallStat();
            s.lhs.add(first);
            while (match(T.COMMA)) s.lhs.add(parseSuffixedExpr());
            expect(T.ASSIGN);
            s.rhs.add(parseExpr());
            while (match(T.COMMA)) s.rhs.add(parseExpr());
            return s;
        }
        if (!(first instanceof CallExpr) && !(first instanceof MethodCallExpr)) {
            throw new ParseException("expected statement");
        }
        AssignOrCallStat s = new AssignOrCallStat();
        s.isCall = true;
        s.lhs.add(first);
        return s;
    }

    private static int binPrec(T t) {
        switch (t) {
            case OR: return 1;
            case AND: return 2;
            case LT: case GT: case LE: case GE: case NE: case EQ: return 3;
            case PIPE: return 4;
            case TILDE: return 5;
            case AMP: return 6;
            case SHL: case SHR: return 7;
            case DDOT: return 9;
            case PLUS: case MINUS: return 10;
            case STAR: case SLASH: case DSLASH: case PERCENT: return 11;
            case CARET: return 14;
            default: return -1;
        }
    }

    private static boolean rightAssoc(T t) {
        return t == T.DDOT || t == T.CARET;
    }

    private Node parseExpr() {
        return parseBinExpr(0);
    }

    private Node parseBinExpr(int minPrec) {
        Node left = parseUnary();
        while (true) {
            T t = cur().type;
            int prec = binPrec(t);
            if (prec < 0 || prec < minPrec) break;
            advance();
            Node right = parseBinExpr(rightAssoc(t) ? prec : prec + 1);
            BinExpr be = new BinExpr();
            be.left = left;
            be.right = right;
            left = be;
        }
        return left;
    }

    private Node parseUnary() {
        if (check(T.NOT) || check(T.MINUS) || check(T.HASH) || check(T.TILDE)) {
            advance();
            UnExpr u = new UnExpr();
            u.operand = parseBinExpr(12);
            return u;
        }
        return parseSuffixedExpr();
    }

    private Node parsePrimaryExpr() {
        switch (cur().type) {
            case NIL: advance(); return new NilExpr();
            case TRUE: advance(); return new TrueExpr();
            case FALSE: advance(); return new FalseExpr();
            case ELLIPSIS: advance(); return new VarargExpr();
            case NUMBER: advance(); return new NumberExpr();
            case STRING: advance(); return new StringExpr();
            case FUNCTION: {
                advance();
                FunctionExpr fe = new FunctionExpr();
                fe.body = parseFunctionBody(false);
                return fe;
            }
            case LBRACE: return parseTable();
            case LPAREN: {
                advance();
                Node inner = parseExpr();
                expect(T.RPAREN);
                ParenExpr p = new ParenExpr();
                p.inner = inner;
                return p;
            }
            case NAME: {
                NameExpr n = new NameExpr();
                n.tok = advance();
                return n;
            }
            default:
                throw new ParseException("unexpected token " + cur().type);
        }
    }

    private Node parseSuffixedExpr() {
        Node base = parsePrimaryExpr();
        while (true) {
            switch (cur().type) {
                case DOT: {
                    advance();
                    IndexExpr ix = new IndexExpr();
                    ix.base = base;
                    ix.field = expect(T.NAME);
                    base = ix;
                    break;
                }
                case LBRACKET: {
                    advance();
                    IndexExpr ix = new IndexExpr();
                    ix.base = base;
                    ix.indexExpr = parseExpr();
                    expect(T.RBRACKET);
                    base = ix;
                    break;
                }
                case COLON: {
                    advance();
                    MethodCallExpr mc = new MethodCallExpr();
                    mc.base = base;
                    mc.method = expect(T.NAME);
                    mc.args.addAll(parseCallArgs());
                    base = mc;
                    break;
                }
                case LPAREN: case STRING: case LBRACE: {
                    CallExpr ce = new CallExpr();
                    ce.base = base;
                    ce.args.addAll(parseCallArgs());
                    base = ce;
                    break;
                }
                default:
                    return base;
            }
        }
    }

    private List<Node> parseCallArgs() {
        List<Node> args = new ArrayList<>();
        if (check(T.LPAREN)) {
            advance();
            if (!check(T.RPAREN)) {
                args.add(parseExpr());
                while (match(T.COMMA)) args.add(parseExpr());
            }
            expect(T.RPAREN);
        } else if (check(T.STRING)) {
            advance();
            args.add(new StringExpr());
        } else if (check(T.LBRACE)) {
            args.add(parseTable());
        } else {
            throw new ParseException("expected call arguments");
        }
        return args;
    }

    private Node parseTable() {
        expect(T.LBRACE);
        TableExpr t = new TableExpr();
        while (!check(T.RBRACE)) {
            TableField f = new TableField();
            if (check(T.LBRACKET)) {
                advance();
                f.keyExpr = parseExpr();
                expect(T.RBRACKET);
                expect(T.ASSIGN);
                f.value = parseExpr();
            } else if (check(T.NAME) && peekAt(1).type == T.ASSIGN) {
                f.key = advance();
                advance();
                f.value = parseExpr();
            } else {
                f.value = parseExpr();
            }
            t.fields.add(f);
            if (!match(T.COMMA) && !match(T.SEMI)) break;
        }
        expect(T.RBRACE);
        return t;
    }
}
