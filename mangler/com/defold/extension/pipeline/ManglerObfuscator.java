package com.defold.extension.pipeline;

import com.defold.extension.pipeline.ILuaObfuscator;
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
 *
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

    private Path root;                                             // found lazily from the first real file path we see
    private Mangler mangler;                                       // built on first use
    private final Map<Path, String> unreadable = new HashMap<>();  // project Lua files we could not parse -> why
    private final Set<Path> projectFiles = new HashSet<>();        // every project Lua file the scan saw

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
    public String obfuscate(String input, String path, String buildVariant) throws IOException {
        if (disabled) return input;
        Path file = locate(path);
        if (file == null) return input;
        String problem = unreadable.get(file);
        if (problem != null) {
            throw new IOException(
                "mangler cannot read " + file + " (" + problem + "); fix it, or set disabled = 1 under [mangler] in game.project"
            );
        }
        return mangler().mangle(input);
    }

    private Path getProjectRoot(Path file) {
        if (root != null) return root;
        Path current = file.getParent();
        while (current != null) {
            if (Files.exists(current.resolve("game.project"))) {
                root = current;
                return root;
            }
            current = current.getParent();
        }
        return null;
    }

    /** Scans the whole project once, on first use, so that every file is renamed against the same facts. */
    private synchronized Mangler mangler() throws IOException {
        if (mangler != null) return mangler;
        Map<Path, Scan> scans = new HashMap<>();
        for (Path file : luaFiles()) {
            try {
                scans.put(file, Scan.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                unreadable.put(file, e.getMessage());
            }
        }
        scans.keySet().forEach(unreadable::remove);
        mangler = new Mangler(seed, excluded, scans.values());
        projectFiles.addAll(scans.keySet());
        projectFiles.addAll(unreadable.keySet());
        return mangler;
    }

    private Path locate(String path) throws IOException {
        Path p = Paths.get(path).toRealPath();
        Path projectRoot = getProjectRoot(p);
        if (projectRoot == null) return null;
        if (mangler == null) mangler();
        return projectFiles.contains(p) ? p : null;
    }

    private List<Path> luaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String n = dir.getFileName().toString();
                boolean skip = !dir.equals(root) && n.equals("build");
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
