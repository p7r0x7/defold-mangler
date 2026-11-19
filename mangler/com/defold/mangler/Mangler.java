package com.defold.mangler;

import com.defold.mangler.Lexer.Token;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renames every project-defined identifier to `_<base32(XXH3(name, seed))>`.
 *
 * The result depends only on the name, so the same identifier is renamed identically in every file. That
 * keeps cross-file references working and makes collision handling unnecessary: the rename is one-to-one,
 * so every binding in the program keeps its identity.
 *
 * Which names are renamed is decided once for the whole project:
 *
 *  - Locals: always. They are scope-resolved, so nothing outside can reach them.
 *  - Globals: if the project assigns them somewhere. A global the project only reads belongs to the engine
 *    (`print`, `go`) and must keep its name.
 *  - Fields: if the project assigns them, and only where the access chain is rooted in a project-owned
 *    global or in a local/literal. Chains rooted in an engine global (`go.delete`, `socket.http.request`,
 *    `rand.ffi_ptrs().entropy`) are looked up by the engine, so those tokens are left alone rather than
 *    pinning the field name across the whole project.
 *  - A name that also appears as a table-key string literal (`t["name"]`, `{["name"] = ...}`) is left
 *    alone, since it can be reached from Lua without an identifier in sight. Non-key strings (arguments,
 *    `require` paths, log messages) pin nothing.
 *
 * Defold scripts share one global state, so a global assigned in one script is visible everywhere. There is
 * no per-file environment that could mask an engine global, and renaming stays transparent as long as it is
 * uniform: `pinned = used - definedGlobals` captures "the engine owns this name" because the project never
 * assigns it anywhere. Once the project does assign a name, it owns it everywhere.
 *
 * Two classes of name are excluded unconditionally because the engine looks them up by string: Defold
 * lifecycle callbacks (looked up on the script table) and Lua string/file methods (dispatched by the
 * string/file metatables). Neither metatable can find `_a1b2c3` where it expects `init` or `format`.
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
    private final Set<String> definedGlobals;  // globals the project assigns to
    private final Set<String> renamed = new HashSet<>();  // globals and fields to rename

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

        // A global the project reads but never assigns is the engine's (`print`, `hash`, `go`); a name that
        // also appears as a table-key string literal can be reached as a string (`t["name"]`). Both stay put.
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

    private static final String BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    private static String base32(long value) {
        char[] result = new char[13];
        for (int i = 12; i >= 0; i--) {
            result[i] = BASE32_ALPHABET.charAt((int) (value & 31));
            value >>>= 5;
        }
        return new String(result);
    }
}
