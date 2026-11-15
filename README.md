# Defold-Mangler

Irreversibly mangles function names in Lua scripts prior to building; release builds only.

What's this for? Obfuscators don't touch function names.

## Use

Add

```
https://github.com/p7r0x7/defold-mangler/archive/refs/tags/v<DEFOLD VERSION>.zip
```

to your dependencies in game.project. Optional settings, also in game.project:

```
[mangler]
disabled = 0                  ; 1 turns the plugin off
seed = 1234567890123456789    ; pins the names; random for every build if absent
exclude = name1, name2        ; names that must never be renamed
```

## What is renamed

Every function name becomes `_<hash>`, where the hash depends only on the name and the seed, so a function and
its callers agree wherever they live. That covers

- local functions (`local function f`, `local f = function`),
- global functions (`function f`), and
- functions stored in tables (`function M.f`, `function M:f`, `M.f = function`, `{ f = function ... }`).

## What is left alone

- Engine callbacks (`init`, `update`, `on_message`, ...) and `__metamethods`.
- Anything the project uses but never defines: `print`, `hash`, `go`, and fields read off them (`go.delete`,
  `_G.name`, `socket.http.request`), even when you define a function field with the same name.
- A name that also appears as a whole string literal, since `t["name"]` and `_G["name"]` look names up as text.
- Strings, comments, and everything that is not a function name.

Add a name to `exclude` if something else reaches it by name: a name built at runtime (`self["on_" .. event]`),
native code calling into Lua, or a library whose API shares a name with one of your function fields.

A project Lua file this plugin cannot parse fails the release build with the file named, rather than shipping
a half-renamed game.

## Develop

`./rebuild.sh` rebuilds the jar. `test/run.sh` mangles the fixture projects and checks that they behave the same
under `lua5.1` before and after.
