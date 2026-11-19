package com.defold.mangler;

import com.defold.mangler.Lexer.Kind;
import com.defold.mangler.Lexer.Token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test driver.
 *
 *   Check                                   runs the self-tests
 *   Check mangle <in> <out> [excludes]      mangles a project of .lua files
 *   Check verify <out> <kept> <gone>        lexer-based kept/gone check on the mangled output
 *
 * <kept> and <gone> list identifiers, whitespace-separated (newlines or spaces, either works); lines starting
 * with # are comments. <kept> names must still appear as NAME tokens somewhere in the mangled project; <gone>
 * names must not. Occurrences inside strings and comments do not count.
 */
public final class Check {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { selfTest(); return; }
        switch (args[0]) {
            case "mangle" -> mangle(args[1], args[2], args.length > 3 ? args[3] : "");
            case "verify" -> verify(args[1], args[2], args[3]);
            default -> throw new IllegalArgumentException(
                "usage: Check (self-test | mangle <in> <out> [excludes] | verify <out> <kept> <gone>)");
        }
    }

    // ---- mangle mode ---------------------------------------------------------------------------------------

    private static void mangle(String inDir, String outDir, String excludesFile) throws IOException {
        Set<String> excludes = readNames(excludesFile);
        Map<Path, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.list(Paths.get(inDir))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".lua"))::iterator) {
                sources.put(p, Files.readString(p));
            }
        }
        List<Scan> scans = new ArrayList<>();
        for (String s : sources.values()) scans.add(Scan.of(s));
        Mangler m = new Mangler(42L, excludes, scans);
        Path out = Files.createDirectories(Paths.get(outDir));
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            Files.writeString(out.resolve(e.getKey().getFileName()), m.mangle(e.getValue()));
        }
    }

    // ---- verify mode ---------------------------------------------------------------------------------------

    private static void verify(String mangledDir, String keptFile, String goneFile) throws IOException {
        Set<String> kept = readNames(keptFile);
        Set<String> gone = readNames(goneFile);
        Set<String> present = new HashSet<>();
        try (Stream<Path> files = Files.list(Paths.get(mangledDir))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".lua"))::iterator) {
                for (Token t : Lexer.lex(Files.readString(p))) {
                    if (t.kind() == Kind.NAME) present.add(t.text());
                }
            }
        }
        List<String> errors = new ArrayList<>();
        for (String k : kept)
            if (!present.contains(k)) errors.add("'" + k + "' should still appear as an identifier but does not");
        for (String g : gone)
            if (present.contains(g)) errors.add("'" + g + "' should have been renamed but still appears as an identifier");
        if (!errors.isEmpty()) {
            for (String e : errors) System.err.println("verify: " + e);
            throw new AssertionError(errors.size() + " verification error(s)");
        }
    }

    /** Reads a whitespace-separated list of identifiers; blank lines and lines starting with # are ignored. */
    private static Set<String> readNames(String file) {
        if (file == null || file.isEmpty()) return Set.of();
        try {
            Path p = Paths.get(file);
            if (!Files.exists(p)) return Set.of();
            return Files.readAllLines(p).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .flatMap(s -> Arrays.stream(s.split("\\s+")))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    // ---- self-tests ----------------------------------------------------------------------------------------

    private static void selfTest() {
        for (String bad : new String[]{"x = = 1", "local s = 'open", "foo(", "x = 1 & 2", "local t = {", "--[[ open"}) {
            try { Scan.of(bad); throw new AssertionError("should not parse: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        Scan.of("x = \"" + "a".repeat(5_000_000) + "\"");
        Scan.of("--[[" + "a\n".repeat(2_000_000) + "]] x = [==[" + "b".repeat(2_000_000) + "]==]");
        Scan.of("#!/usr/bin/env luajit\nfor i = 1, 3 do if i == 2 then goto continue end ::continue:: end");

        // string-key tracking: only t["name"] and { ["name"] = ... } positions count
        assertTrue(Scan.of("local t = {} t[\"f\"] = 1 return t.f").strings.contains("f"), "t[\"f\"] should track f");
        assertTrue(Scan.of("local t = { [\"g\"] = 1 } return t.g").strings.contains("g"), "{[\"g\"]=1} should track g");
        assertTrue(Scan.of("local x = \"h\" return x").strings.isEmpty(), "plain string value should not track h");
        assertTrue(Scan.of("local t = { \"a\", \"b\" } return t").strings.isEmpty(), "array-element strings should not track");

        // determinism, BOM, string, comment preservation
        String src = "\uFEFFlocal function f() end -- c\nlocal s = 'f'\nreturn f()";
        Mangler m = new Mangler(7L, Set.of(), List.of(Scan.of(src)));
        String out = m.mangle(src);
        assertTrue(out.equals(m.mangle(src)), "mangle should be deterministic");
        assertTrue(out.contains("'f'") && out.contains("-- c") && out.startsWith("\uFEFF"),
                "strings, comments, BOM should be preserved");
        assertTrue(!out.contains("function f(") && !out.contains("return f("), "f should be renamed");
        assertTrue(Pattern.compile("local function _[a-z2-7]{13}\\(\\) end").matcher(out).find(),
                "renamed identifier should be _<13 base32 chars>, got: " + out);

        // user exclusion
        String excluded = new Mangler(7L, Set.of("f"), List.of(Scan.of(src))).mangle(src);
        assertTrue(excluded.contains("function f(") && excluded.contains("return f("),
                "excluded f should not be renamed");

        // seed sensitivity
        String a = m.mangle("local function f() end");
        String b = new Mangler(8L, Set.of(), List.of(Scan.of("local function f() end")))
                .mangle("local function f() end");
        assertTrue(!a.equals(b), "different seeds should produce different mangled names");

        // every local is renamed, not just function-bound ones
        String localStr = new Mangler(7L, Set.of(), List.of(Scan.of("local x = 1 print(x)")))
                .mangle("local x = 1 print(x)");
        assertTrue(!localStr.contains("local x") && !localStr.contains("(x)"),
                "non-function locals should also be renamed, got: " + localStr);

        // project-defined shadow of a stdlib name: local rename is transparent
        String shadowSrc = "local function type(x) return 't' end print(type(1))";
        String shadowMangled = new Mangler(7L, Set.of(), List.of(Scan.of(shadowSrc))).mangle(shadowSrc);
        assertTrue(!shadowMangled.contains("function type(") && shadowMangled.contains("print(_"),
                "local shadow of `type` should be renamed consistently, got: " + shadowMangled);

        System.out.println("self-test: ok");
    }

    private static void assertTrue(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }
}
