package com.defold.mangler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Test driver. With two arguments, mangles every .lua file of one directory (as a single project) into another.
 * With none, runs the self-tests.
 */
public final class Check {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            selfTest();
            return;
        }
        Map<Path, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.list(Paths.get(args[0]))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".lua"))::iterator) {
                sources.put(p, Files.readString(p));
            }
        }
        List<Scan> scans = new ArrayList<>();
        for (String s : sources.values()) scans.add(Scan.of(s));
        Mangler m = new Mangler(42L, Set.of(), scans);
        Path out = Files.createDirectories(Paths.get(args[1]));
        for (Map.Entry<Path, String> e : sources.entrySet()) {
            Files.writeString(out.resolve(e.getKey().getFileName()), m.mangle(e.getValue()));
        }
    }

    private static void selfTest() {
        for (String bad : new String[]{"x = = 1", "local s = 'open", "foo(", "x = 1 & 2", "local t = {", "--[[ open"}) {
            try {
                Scan.of(bad);
                throw new AssertionError("should not parse: " + bad);
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        // Huge literals must not blow the regex engine's stack.
        Scan.of("x = \"" + "a".repeat(5_000_000) + "\"");
        Scan.of("--[[" + "a\n".repeat(2_000_000) + "]] x = [==[" + "b".repeat(2_000_000) + "]==]");

        Scan.of("#!/usr/bin/env luajit\nfor i = 1, 3 do if i == 2 then goto continue end ::continue:: end");
        if (!Scan.of("local t = { f = function() end } return t[\"f\"]").strings.contains("f")) throw new AssertionError("strings");

        String src = "\uFEFFlocal function f() end -- c\nlocal s = 'f'\nreturn f()";
        Mangler m = new Mangler(7L, Set.of(), List.of(Scan.of(src)));
        String out = m.mangle(src);
        if (!out.equals(m.mangle(src))) throw new AssertionError("not deterministic");
        if (!out.contains("'f'") || !out.contains("-- c") || !out.startsWith("\uFEFF")) throw new AssertionError(out);
        if (out.contains("function f(") || out.contains("return f(")) throw new AssertionError(out);
        if (!new Mangler(7L, Set.of("f"), List.of(Scan.of(src))).mangle(src).equals(src)) throw new AssertionError("exclude ignored");
        System.out.println("self-test: ok");
    }
}
