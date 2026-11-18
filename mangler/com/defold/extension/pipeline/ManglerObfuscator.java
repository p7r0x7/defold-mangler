package com.defold.extension.pipeline;

import com.defold.mangler.Mangler;
import com.defold.mangler.Scan;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.DefaultFileSystem;
import com.dynamo.bob.util.BobProjectProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hooks {@link Mangler} into Bob's Lua pipeline. Settings live in game.project:
 * <pre>
 * [mangler]
 * disabled = 0                 ; 1 turns the plugin off
 * seed = 1234567890123456789   ; optional; random for every build if absent
 * exclude = name1, name2       ; names never to rename
 * </pre>
 */
public final class ManglerObfuscator implements ILuaObfuscator {

    private static final List<String> LUA = List.of(".lua", ".script", ".gui_script", ".render_script");

    private final boolean disabled;
    private final long seed;
    private final Set<String> excluded;

    private Path root;
    private Mangler mangler;
    private final Map<Path, String> unreadable = new HashMap<>();
    private final Set<Path> projectFiles = new HashSet<>();
    private final Map<Object, Scan> scans = new HashMap<>();

    public ManglerObfuscator() throws InstantiationException {
        try {
            Project project = new Project(new DefaultFileSystem());
            project.loadProjectFile();
            BobProjectProperties props = project.getProjectProperties();
            disabled = props.getBooleanValue("mangler", "disabled", false);
            String s = props.getStringValue("mangler", "seed", "").trim();
            seed = s.isEmpty() ? new SecureRandom().nextLong() : Long.parseUnsignedLong(s);
            excluded = Arrays.stream(props.getStringValue("mangler", "exclude", "").split(","))
                    .map(String::trim).filter(n -> !n.isEmpty()).collect(Collectors.toSet());
        } catch (Exception e) {
            throw new InstantiationException("mangler: " + e);
        }
    }

    @Override
    public synchronized String obfuscate(String input, String path, String buildVariant) throws IOException {
        if (disabled || getProjectRoot(path) == null) return input;
        ensureBaseline();

        Path file = locate(path);
        if (file != null) {
            String problem = unreadable.get(file);
            if (problem != null) {
                throw new IOException(
                    "mangler cannot read " + file + " (" + problem + "); fix it, or set disabled = 1 under [mangler] in game.project"
                );
            }
        }

        Object key = file != null ? file : path;
        if (!scans.containsKey(key)) {
            try {
                scans.put(key, Scan.of(input));
            } catch (IllegalArgumentException e) {
                throw new IOException(
                    "mangler cannot read " + path + " (" + e.getMessage() + "); fix it, or set disabled = 1 under [mangler] in game.project"
                );
            }
            mangler = new Mangler(seed, excluded, scans.values());
        }
        return mangler.mangle(input);
    }

    private Path getProjectRoot(String path) {
        if (root != null) return root;
        Path current = Paths.get(path).toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("game.project"))) {
                root = current;
                return root;
            }
            current = current.getParent();
        }
        return null;
    }

    private void ensureBaseline() throws IOException {
        if (mangler != null) return;
        for (Path file : luaFiles()) {
            projectFiles.add(file);
            try {
                scans.put(file, Scan.of(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                unreadable.put(file, e.getMessage());
            }
        }
        mangler = new Mangler(seed, excluded, scans.values());
    }

    private Path locate(String path) {
        try {
            Path p = Paths.get(path).toRealPath();
            return projectFiles.contains(p) ? p : null;
        } catch (IOException e) {
            return null;
        }
    }

    private List<Path> luaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                boolean skip = !dir.equals(root) && dir.getFileName().toString().equals("build");
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (LUA.stream().anyMatch(n::endsWith)) out.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
