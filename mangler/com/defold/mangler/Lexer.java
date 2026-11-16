package com.defold.mangler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits Lua 5.1 / LuaJIT source into tokens that remember their offsets, so renames can be spliced into the text. */
final class Lexer {

    enum Kind { NAME, KEYWORD, STRING, NUMBER, OP, EOF }

    record Token(Kind kind, String text, int start, int end) {}

    private static final Set<String> KEYWORDS = Set.of(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if",
        "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
    );

    // One alternative per token class, tried in this order. Quoted strings use possessive loops so that very
    // long literals cannot overflow the regex engine's stack.
    private static final Pattern TOKEN = Pattern.compile(
        "(?<skip>\\s+|--\\[(?<e1>=*)\\[.*?\\]\\k<e1>\\]|--(?!\\[=*\\[)[^\\n]*)"
        + "|(?<str>\\[(?<e2>=*)\\[.*?\\]\\k<e2>\\]"
        + "|\"[^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+\""
        + "|'[^'\\\\\\n]*+(?:\\\\.[^'\\\\\\n]*+)*+')"
        + "|(?<num>0[xX][0-9a-fA-F]*(?:\\.[0-9a-fA-F]*)?(?:[pP][+-]?\\d+)?[uUlLi]*"
        + "|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?[uUlLi]*)"
        + "|(?<name>[A-Za-z_\\u0080-\\uFFFF][A-Za-z0-9_\\u0080-\\uFFFF]*)"
        + "|(?<op>\\.\\.\\.|\\.\\.|==|~=|<=|>=|::|[-+*/%^#<>=(){}\\[\\];:,.])",
        Pattern.DOTALL
    );

    static List<Token> lex(String src) {
        Matcher m = TOKEN.matcher(src);
        List<Token> out = new ArrayList<>();
        int pos = src.startsWith("\uFEFF") ? 1 : 0;
        if (src.startsWith("#", pos)) pos = Math.max(src.indexOf('\n', pos), pos);  // `#!` line, skipped by Lua's loader
        while (pos < src.length()) {
            m.region(pos, src.length());
            if (!m.lookingAt()) throw new IllegalArgumentException("unexpected character at offset " + pos);
            String text = m.group();
            if (m.group("str") != null)
                out.add(new Token(Kind.STRING, text, pos, m.end()));
            else if (m.group("num") != null)
                out.add(new Token(Kind.NUMBER, text, pos, m.end()));
            else if (m.group("name") != null)
                out.add(new Token(KEYWORDS.contains(text) ? Kind.KEYWORD : Kind.NAME, text, pos, m.end()));
            else if (m.group("op") != null)
                out.add(new Token(Kind.OP, text, pos, m.end()));
            pos = m.end();
        }
        out.add(new Token(Kind.EOF, "", pos, pos));
        return out;
    }
}
