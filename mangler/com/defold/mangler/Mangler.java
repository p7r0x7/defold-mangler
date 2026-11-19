package com.defold.mangler;

import com.defold.mangler.Lexer.Token;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces function names with `_<XXH3 of the name, keyed by the seed>`.
 *
 * The new name depends only on the old name, so the same name is renamed the same way in every file. That is
 * what lets one file call a function another file defines, and it is also why no collision handling is needed:
 * renaming is one-to-one, so every binding in the program stays exactly as it was.
 *
 * Which names are renamed is decided once for the whole project:
 *
 *  - a local variable is renamed if it is ever bound to a function (decided per variable, by scope);
 *  - any other name is renamed if the project binds it to a function as a global or as a table field, unless
 *    it is excluded or could be reached by something other than a Lua identifier (see the constructor).
 *
 * Field accesses on a global the project does not define are looked up by the engine, not by Lua
 * (`go.delete`, `_G.name`, `socket.http.request`, `rand.ffi_ptrs().entropy`), so those tokens are left
 * alone individually rather than pinning the field name across the whole project.
 */
public final class Mangler {

    private static final Set<String> BUILTIN_EXCLUDED = Set.of(
        // looked up by the engine
        "init", "final", "update", "fixed_update", "on_message", "on_input", "on_reload",
        // methods of strings and files, which are called as `s:format()` with no global in sight
        "byte", "char", "dump", "find", "format", "gmatch", "gsub", "len", "lower", "match", "rep",
        "reverse", "sub", "upper", "close", "flush", "lines", "read", "seek", "setvbuf", "write"
    );

    private final long seed;
    private final Set<String> excluded = new HashSet<>(BUILTIN_EXCLUDED);
    private final Set<String> defined;  // globals the project assigns to, across the whole project
    private final Set<String> renamed = new HashSet<>();  // global and field names, across the whole project

    public Mangler(long seed, Set<String> userExcluded, Collection<Scan> project) {
        this.seed = seed;
        excluded.addAll(userExcluded);

        Set<String> fns = new HashSet<>(), definedGlobals = new HashSet<>(), used = new HashSet<>(), strings = new HashSet<>();
        for (Scan s : project) {
            fns.addAll(s.definedFns);
            definedGlobals.addAll(s.definedGlobals);
            strings.addAll(s.strings);
            for (Token t : s.globalRefs) used.add(t.text());
        }
        this.defined = definedGlobals;

        // Names that something other than a Lua identifier may rely on stay as they are:
        //  - a global the project reads but never assigns belongs to the engine (`print`, `hash`, `go`);
        //  - a name that is also a whole string literal may be looked up as a string (`t["name"]`, `_G["name"]`).
        // Field accesses on those engine globals are handled per token in mangle(); see Scan.FieldRef.
        Set<String> pinned = new HashSet<>(used);
        pinned.removeAll(definedGlobals);
        pinned.addAll(strings);

        for (String name : fns) {
            if (!pinned.contains(name) && !isProtected(name)) renamed.add(name);
        }
    }

    private boolean isProtected(String name) {
        return excluded.contains(name) || name.startsWith("__");  // __index, __call, ... are the VM's names
    }

    public String mangle(String source) {
        Scan s = Scan.of(source);
        List<Token> hits = new ArrayList<>();
        for (Scan.Local l : s.locals) {
            if (l.fn && !isProtected(l.name)) hits.addAll(l.uses);
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
            out.append(source, at, t.start()).append('_').append(Long.toHexString(XXH3.hash64(t.text(), seed)));
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
        return f.root() == null || defined.contains(f.root());
    }
}
