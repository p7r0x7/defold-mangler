package com.defold.extension.pipeline;

import com.defold.mangler.Mangler;
import com.defold.mangler.Scan;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.DefaultFileSystem;
import com.dynamo.bob.util.BobProjectProperties;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * disabled = 0               ; 1 turns the plugin off
 * seed = 1234567890123456789 ; optional; random for every build if absent
 * exclude = name1, name2     ; names never to rename
 * </pre>
 */
public final class ManglerObfuscator implements ILuaObfuscator {

    private static final List<String> LUA = List.of(".lua", ".script", ".gui_script", ".render_script");

    private final boolean disabled;
    private final long seed;
    private final Set<String> excluded;

    private File projectRoot;
    private Mangler mangler;
    private final Map<File, String> unreadable = new HashMap<>();     // scanned files that failed to parse
    private final Set<File> projectFiles = new HashSet<>();           // every Lua file under the project root
    private final Map<Object, Scan> scans = new HashMap<>();          // keyed by File, or by path for stragglers

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
            throw new InstantiationException(e.getMessage());
        }
    }

    @Override
    public synchronized String obfuscate(String input, String path, String buildVariant) throws Exception {
        if (disabled) {
            return input;
        }

        File root = getProjectRoot(path);
        if (root == null) {
            throw new Exception("Unable to find project root for " + path);
        }

        ensureBaseline(root);

        File file = locate(path);
        if (file != null) {
            String problem = unreadable.get(file);
            if (problem != null) {
                throw new IOException(
                    "mangler cannot read " + file + " (" + problem + "); fix it, or set disabled = 1 under [mangler] in game.project"
                );
            }
        }

        // Files outside the pre-scan set (generated ones, say) are keyed by path so repeated calls reuse the scan.
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

    private File getProjectRoot(String path) {
        if (projectRoot != null) {
            return projectRoot;
        }
        File current = new File(path).getParentFile();
        while (current != null) {
            File gameProject = new File(current, "game.project");
            if (gameProject.exists()) {
                projectRoot = current;
                break;
            }
            current = current.getParentFile();
        }
        return projectRoot;
    }

    /** Reads every Lua file under the project root once, so the mangler sees the whole name space up front. */
    private void ensureBaseline(File root) throws IOException {
        if (mangler != null) return;
        for (File file : luaFiles(root)) {
            projectFiles.add(file);
            try {
                scans.put(file, Scan.of(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                unreadable.put(file, e.getMessage());
            }
        }
        projectFiles.addAll(unreadable.keySet());
        mangler = new Mangler(seed, excluded, scans.values());
    }

    /** Maps a path Bob hands us to the canonical file we scanned, or null if it is not a project Lua file. */
    private File locate(String path) {
        try {
            File f = new File(path).getCanonicalFile();
            return projectFiles.contains(f) ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    private List<File> luaFiles(File root) throws IOException {
        List<File> out = new ArrayList<>();
        Files.walkFileTree(root.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // `build/` only exists at the project root; skip it so we don't pick up our own output.
                boolean skip = !dir.equals(root.toPath()) && dir.getFileName().toString().equals("build");
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String n = file.getFileName().toString();
                if (LUA.stream().anyMatch(n::endsWith)) out.add(file.toFile());
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}