# Defold-Mangler

Irreversibly mangles project-defined identifiers in Lua scripts prior to building, with the exception of Defold callbacks and Lua builtins; release builds only.

What's this for? Can improve the strength of obfuscation.

## Use

Add

```
https://github.com/p7r0x7/defold-mangler/archive/refs/tags/v<DEFOLD VERSION>.zip
```

to your dependencies in game.project. Optional settings, also in game.project:

```
[mangler]
disabled = 0                  ; 1 turns the plugin off
seed = 1234567890123456789    ; random for every build if absent
exclude = name1, name2        ; identifiers that must never be renamed
```

`./rebuild.sh` rebuilds the jar. `./test.sh` runs the test suite.
