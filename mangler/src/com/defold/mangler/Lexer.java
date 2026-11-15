package com.defold.mangler;

import java.util.*;

/** Minimal Lua 5.1 tokenizer. Tracks exact source offsets so renaming can splice the original text. */
public final class Lexer {

    public enum T {
        NAME, NUMBER, STRING, AND, BREAK, DO, ELSE, ELSEIF, END,
        FALSE, FOR, FUNCTION, GOTO, IF, IN, LOCAL, NIL, NOT, OR,
        REPEAT, RETURN, THEN, TRUE, UNTIL, WHILE, PLUS, MINUS,
        STAR, SLASH, PERCENT, CARET, HASH, AMP, TILDE, PIPE, SHL,
        SHR, DSLASH, EQ, NE, LE, GE, LT, GT, ASSIGN, LPAREN,
        RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, DCOLON,
        SEMI, COLON, COMMA, DOT, DDOT, ELLIPSIS, EOF
    }

    public static final class Tok {
        public final T type;
        public final String text;
        public final int start, end;

        Tok(T type, String text, int start, int end) {
            this.type = type;
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    public static final class LexException extends RuntimeException {
        LexException(String m) {
            super(m);
        }
    }

    private static final Map<String, T> KEYWORDS = new HashMap<>();
    static {
        for (T k : new T[]{
                T.AND, T.BREAK, T.DO, T.ELSE, T.ELSEIF, T.END,
                T.FALSE, T.FOR, T.FUNCTION, T.GOTO, T.IF,
                T.IN, T.LOCAL, T.NIL, T.NOT, T.OR, T.REPEAT,
                T.RETURN, T.THEN, T.TRUE, T.UNTIL, T.WHILE
        }) {
            KEYWORDS.put(k.name().toLowerCase(Locale.ROOT), k);
        }
    }

    private final String src;
    private final int len;
    private int pos = 0;

    public Lexer(String src) {
        this.src = src;
        this.len = src.length();
    }

    public List<Tok> tokenize() {
        List<Tok> out = new ArrayList<>();
        Tok t;
        do {
            t = next();
            out.add(t);
        } while (t.type != T.EOF);
        return out;
    }

    private char peek() {
        return pos < len ? src.charAt(pos) : '\0';
    }

    private char peek(int o) {
        int p = pos + o;
        return p < len ? src.charAt(p) : '\0';
    }

    private char adv() {
        return src.charAt(pos++);
    }

    private void skipSpaceAndComments() {
        while (pos < len) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else if (c == '-' && peek(1) == '-') {
                pos += 2;
                int save = pos;
                Integer lvl = tryLongBracket();
                if (lvl != null) {
                    readLongBracket(lvl);
                } else {
                    pos = save;
                    while (pos < len && peek() != '\n') pos++;
                }
            } else {
                break;
            }
        }
    }

    private Integer tryLongBracket() {
        int save = pos;
        if (peek() != '[') return null;
        int p = pos + 1, eqs = 0;
        while (p < len && src.charAt(p) == '=') {
            eqs++;
            p++;
        }
        if (p < len && src.charAt(p) == '[') {
            pos = p + 1;
            return eqs;
        }
        pos = save;
        return null;
    }

    private String readLongBracket(int level) {
        if (peek() == '\r') pos++;
        if (peek() == '\n') pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= len) throw new LexException("unterminated long bracket");
            if (peek() == ']') {
                int p = pos + 1, eqs = 0;
                while (p < len && src.charAt(p) == '=') {
                    eqs++;
                    p++;
                }
                if (eqs == level && p < len && src.charAt(p) == ']') {
                    pos = p + 1;
                    return sb.toString();
                }
            }
            sb.append(adv());
        }
    }

    private Tok next() {
        skipSpaceAndComments();
        int start = pos;
        if (pos >= len) return new Tok(T.EOF, "", start, start);
        char c = peek();

        if (Character.isLetter(c) || c == '_') {
            while (pos < len && (Character.isLetterOrDigit(peek()) || peek() == '_')) pos++;
            String text = src.substring(start, pos);
            T kw = KEYWORDS.get(text);
            return new Tok(kw != null ? kw : T.NAME, text, start, pos);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return readNumber(start);
        }
        if (c == '"' || c == '\'') {
            return readShortString(start, c);
        }
        if (c == '[') {
            int save = pos;
            Integer lvl = tryLongBracket();
            if (lvl != null) {
                readLongBracket(lvl);
                return new Tok(T.STRING, src.substring(start, pos), start, pos);
            }
            pos = save;
            pos++;
            return new Tok(T.LBRACKET, "[", start, pos);
        }
        switch (c) {
            case ':':
                pos++;
                if (peek() == ':') {
                    pos++;
                    return tok(T.DCOLON, start);
                }
                return tok(T.COLON, start);
            case '.':
                pos++;
                if (peek() == '.') {
                    pos++;
                    if (peek() == '.') {
                        pos++;
                        return tok(T.ELLIPSIS, start);
                    }
                    return tok(T.DDOT, start);
                }
                return tok(T.DOT, start);
            case '=':
                pos++;
                if (peek() == '=') {
                    pos++;
                    return tok(T.EQ, start);
                }
                return tok(T.ASSIGN, start);
            case '~':
                pos++;
                if (peek() == '=') {
                    pos++;
                    return tok(T.NE, start);
                }
                return tok(T.TILDE, start);
            case '<':
                pos++;
                if (peek() == '=') {
                    pos++;
                    return tok(T.LE, start);
                }
                if (peek() == '<') {
                    pos++;
                    return tok(T.SHL, start);
                }
                return tok(T.LT, start);
            case '>':
                pos++;
                if (peek() == '=') {
                    pos++;
                    return tok(T.GE, start);
                }
                if (peek() == '>') {
                    pos++;
                    return tok(T.SHR, start);
                }
                return tok(T.GT, start);
            case '/':
                pos++;
                if (peek() == '/') {
                    pos++;
                    return tok(T.DSLASH, start);
                }
                return tok(T.SLASH, start);
            case '+': pos++; return tok(T.PLUS, start);
            case '-': pos++; return tok(T.MINUS, start);
            case '*': pos++; return tok(T.STAR, start);
            case '%': pos++; return tok(T.PERCENT, start);
            case '^': pos++; return tok(T.CARET, start);
            case '#': pos++; return tok(T.HASH, start);
            case '&': pos++; return tok(T.AMP, start);
            case '|': pos++; return tok(T.PIPE, start);
            case '(': pos++; return tok(T.LPAREN, start);
            case ')': pos++; return tok(T.RPAREN, start);
            case '{': pos++; return tok(T.LBRACE, start);
            case '}': pos++; return tok(T.RBRACE, start);
            case ']': pos++; return tok(T.RBRACKET, start);
            case ';': pos++; return tok(T.SEMI, start);
            case ',': pos++; return tok(T.COMMA, start);
            default:
                throw new LexException("unexpected character '" + c + "'");
        }
    }

    private Tok tok(T type, int start) {
        return new Tok(type, src.substring(start, pos), start, pos);
    }

    private Tok readNumber(int start) {
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2;
            while (pos < len && (isHex(peek()) || peek() == '.')) pos++;
            if (peek() == 'p' || peek() == 'P') {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (pos < len && Character.isDigit(peek())) pos++;
            }
        } else {
            while (pos < len && (Character.isDigit(peek()) || peek() == '.')) pos++;
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (pos < len && Character.isDigit(peek())) pos++;
            }
        }
        while (pos < len && "uUlL".indexOf(peek()) >= 0) pos++;
        return new Tok(T.NUMBER, src.substring(start, pos), start, pos);
    }

    private Tok readShortString(int start, char quote) {
        pos++;
        while (true) {
            if (pos >= len || peek() == '\n') throw new LexException("unterminated string");
            char c = adv();
            if (c == quote) break;
            if (c == '\\' && pos < len) pos++;
        }
        return new Tok(T.STRING, src.substring(start, pos), start, pos);
    }

    private static boolean isHex(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
