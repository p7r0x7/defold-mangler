package com.defold.mangler;

import com.defold.mangler.Lexer.Token;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces every project-defined identifier with `_<base32 of the name's XXH3, keyed by the seed>`.
 *
 * The new name depends only on the old name, so the same name is renamed the same way in every file. That is
 * what lets one file call a function another file defines, and it is also why no collision handling is needed:
 * renaming is one-to-one, so every binding in the program stays exactly as it was.
 *
 * Which names are renamed is decided once for the whole project:
 *
 *  - a local variable is always renamed (it is scope-resolved, so it can never be reached from outside);
 *  - a global is renamed if the project assigns it somewhere;
 *  - a field is renamed if the project assigns it somewhere, and only at sites where the chain is rooted in a
 *    project-owned global or in a local/literal. Field accesses on a global the project never assigns are
 *    looked up by the engine, not by Lua (`go.delete`, `_G.name`, `socket.http.request`,
 *    `rand.ffi_ptrs().entropy`), so those tokens are left alone individually rather than pinning the field
 *    name across the whole project;
 *  - a name that also appears as a table-key string literal (`t["name"]`, `{["name"] = ...}`) is left alone,
 *    because it could be reached from Lua without an identifier in sight. Non-key strings (arguments,
 *    `require` paths, log messages) do not pin anything.
 *
 * Defold gives every project the shared state, so a global assigned in one script is visible in every other
 * one. There is no per-file environment that could mask an engine global, and renaming is therefore transparent
 * as long as it is uniform: `pinned = used - definedGlobals` correctly captures "the engine owns this name"
 * because the project never assigns it anywhere. Once the project does assign a name, it owns it everywhere.
 *
 * Defold lifecycle callbacks and Lua string/file method names are the two exceptions. The engine looks callbacks
 * up by name on the script table, and the string/file metatables dispatch methods by name; neither can find
 * `_a1b2c3` where it expects `init` or `format`. They are excluded unconditionally.
 */
public final class Mangler {

    private static final Set<String> ALWAYS_EXCLUDED = Set.of(
        // Defold lifecycle callbacks
        "init", "final", "update", "fixed_update", "on_message", "on_input", "on_reload",
        "on_window_resize", "on_window_focus",
        // Lua string methods
        "byte", "char", "dump", "find", "format", "gmatch", "gsub", "len", "lower", "match",
        "rep", "reverse", "sub", "upper", "gfind",
        // Lua file-handle methods
        "close", "flush", "lines", "read", "seek", "setvbuf", "write"
    );

    private final long seed;
    private final Set<String> userExcluded = new HashSet<>();
    private final Set<String> definedGlobals;  // globals the project assigns to, project-wide
    private final Set<String> renamed = new HashSet<>();  // globals and fields to rename, project-wide

    public Mangler(long seed, Set<String> userExcluded, Collection<Scan> project) {
        this.seed = seed;
        this.userExcluded.addAll(userExcluded);

        Set<String> defined = new HashSet<>(), used = new HashSet<>(), strings = new HashSet<>();
        Set<String> globals = new HashSet<>();
        for (Scan s : project) {
            defined.addAll(s.definedGlobals);
            defined.addAll(s.definedFields);
            globals.addAll(s.definedGlobals);
            strings.addAll(s.strings);
            for (Token t : s.globalRefs) used.add(t.text());
        }
        this.definedGlobals = globals;

        // A global the project reads but never assigns belongs to the engine (`print`, `hash`, `go`); a name
        // that is also a table-key string literal may be looked up as a string (`t["name"]`).
        Set<String> pinned = new HashSet<>(used);
        pinned.removeAll(globals);
        pinned.addAll(strings);

        for (String name : defined) {
            if (!pinned.contains(name) && !isProtectedName(name)) renamed.add(name);
        }
    }

    private boolean isProtectedName(String name) {
        return ALWAYS_EXCLUDED.contains(name) || userExcluded.contains(name) || name.startsWith("__");
    }

    /** A local shadows nothing the engine or a metatable looks up, so only user exclusions and VM names apply. */
    private boolean isProtectedLocal(String name) {
        return userExcluded.contains(name) || name.startsWith("__");
    }

    public String mangle(String source) {
        Scan s = Scan.of(source);
        List<Token> hits = new ArrayList<>();
        for (Scan.Local l : s.locals) {
            if (!isProtectedLocal(l.name)) hits.addAll(l.uses);
        }
        for (Token t : s.globalRefs) {
            if (renamed.contains(t.text())) hits.add(t);
        }
        for (Scan.FieldRef f : s.fieldRefs) {
            if (renamed.contains(f.token().text()) && isProjectField(f)) hits.add(f.token());
        }
        hits.sort(Comparator.comparingInt(Token::start));

        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        for (Token t : hits) {
            out.append(source, at, t.start())
               .append('_')
               .append(base32(XXH3.hash64(t.text(), seed)));
            at = t.end();
        }
        return out.append(source, at, source.length()).toString();
    }

    /**
     * A field may be renamed only if its chain is rooted in a global the project owns. A null root means the
     * chain starts from a local or a literal, which the project also owns; a root the project never assigns
     * is an engine table whose keys must stay as written.
     */
    private boolean isProjectField(Scan.FieldRef f) {
        return f.root() == null || definedGlobals.contains(f.root());
    }

    private static String base32(long value) {
        char[] alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
        char[] result = new char[13];
        for (int i = 12; i >= 0; i--) {
            result[i] = alphabet[(int) (value & 31)];
            value >>>= 5;
        }
        return new String(result);
    }
}
