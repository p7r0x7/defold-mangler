package com.defold.mangler;

import com.defold.extension.pipeline.ILuaObfuscator;
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
 *
 * Only the project's own Lua files are scanned and rewritten. Anything else Bob hands over (library
 * dependencies) is returned as is, so a library's API and its use of the engine are never disturbed.
 */
public final class ManglerObfuscator implements ILuaObfuscator {

    private static final List<String> LUA = List.of(".lua", ".script", ".gui_script", ".render_script");

    private final boolean disabled;
    private final Path root;
    private final long seed;
    private final Set<String> excluded;

    private Mangler mangler;                                  // built on first use
    private final Map<Path, String> unreadable = new HashMap<>();  // project Lua files we could not parse -> why
    private final Set<Path> projectFiles = new HashSet<>();        // every project Lua file the scan saw

    public ManglerObfuscator() throws InstantiationException {
        try {
            Project project = new Project(new DefaultFileSystem());
            project.loadProjectFile();
            BobProjectProperties props = project.getProjectProperties();
            disabled = props.getBooleanValue("mangler", "disabled", false);
            String dir = project.getRootDirectory();
            root = Paths.get(dir == null || dir.isEmpty() ? "." : dir).toAbsolutePath().normalize();
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
        if (disabled || !"release".equals(buildVariant)) return input;
        Mangler m = mangler();
        Path file = locate(path);
        if (file == null) return input;
        String problem = unreadable.get(file);
        if (problem != null) {
            throw new IOException(
                "mangler cannot read " + file + " (" + problem + "); fix it, or set disabled = 1 under [mangler] in game.project"
            );
        }
        return m.mangle(input);
    }

    /** Scans the whole project once, on first use, so that every file is renamed against the same facts. */
    private synchronized Mangler mangler() throws IOException {
        if (mangler != null) return mangler;
        Map<Path, Scan> scans = new HashMap<>();
        for (Path file : luaFiles()) {
            try {
                scans.put(file, Scan.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                unreadable.put(file, e.getMessage());  // only a problem if it turns out to be part of the build
            }
        }
        scans.keySet().forEach(unreadable::remove);
        mangler = new Mangler(seed, excluded, scans.values());
        projectFiles.addAll(scans.keySet());
        projectFiles.addAll(unreadable.keySet());
        return mangler;
    }

    /** Bob names project files like "/main/main.script", relative to the project root. */
    private Path locate(String path) {
        for (Path p : new Path[]{root.resolve(path.startsWith("/") ? path.substring(1) : path), Paths.get(path)}) {
            p = p.toAbsolutePath().normalize();
            if (projectFiles.contains(p)) return p;
        }
        return null;
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
