package com.defold.mangler;

import com.defold.extension.pipeline.ILuaObfuscator;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.DefaultFileSystem;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Wires {@link Mangler} into Bob's Lua build pipeline (same plugin mechanism as extension-prometheus). */
public final class ManglerObfuscator implements ILuaObfuscator {

    private static final String CONFIG_FILE = "mangler.config";

    private File projectRoot;
    private boolean disabled;
    private Long seed;
    private MangleConfig config;
    private Set<String> globalFunctionRegistry; // project-wide, built once per build

    public ManglerObfuscator() throws InstantiationException {
        try {
            Project project = new Project(new DefaultFileSystem());
            project.loadProjectFile();
            disabled = project.getProjectProperties().getBooleanValue("mangler", "disabled", false);
        } catch (Exception e) {
            throw new InstantiationException(e.getMessage());
        }
    }

    @Override
    public String obfuscate(String input, String path, String buildVariant) {
        if (disabled || !"release".equals(buildVariant)) return input;
        try {
            File root = findProjectRoot(path);
            ensureInitialized(root);
            return new Mangler(seed, config.excluded).mangle(input, globalFunctionRegistry).output;
        } catch (Exception e) {
            return input; // never fail the build because of this transform
        }
    }

    private synchronized void ensureInitialized(File root) {
        if (config != null) return;
        config = MangleConfig.loadOrDefault(root != null ? new File(root, CONFIG_FILE).toPath() : null);
        seed = config.pinnedSeed != null ? config.pinnedSeed : new SecureRandom().nextLong();
        globalFunctionRegistry = scanProject(root);
    }

    private File findProjectRoot(String path) {
        if (projectRoot != null) return projectRoot;
        for (File dir = new File(path).getParentFile(); dir != null; dir = dir.getParentFile()) {
            if (new File(dir, "game.project").exists()) return projectRoot = dir;
        }
        return null;
    }

    /** One-time scan of every Lua file under the project, so a file that only calls a global (never defines it)
     *  still renames that call site consistently with whichever file does define it. */
    private static Set<String> scanProject(File root) {
        Set<String> names = new HashSet<>();
        if (root == null) return names;
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(ManglerObfuscator::isLuaLike).forEach(p -> {
                try {
                    names.addAll(Mangler.scanGlobalFunctionNames(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)));
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
        return names;
    }

    private static boolean isLuaLike(Path p) {
        if (!Files.isRegularFile(p) || p.toString().contains(File.separator + "build" + File.separator)) return false;
        String name = p.getFileName().toString();
        return name.endsWith(".lua")
            || name.endsWith(".script")
            || name.endsWith(".gui_script")
            || name.endsWith(".render_script");
    }
}
