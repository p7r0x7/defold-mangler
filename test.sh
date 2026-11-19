#!/bin/sh

# Needs JDK >= 25 and luajit.

set -eu
cd "$(dirname "$0")"
src=mangler/com/defold/mangler
cp=".build/classes"

rm -rf .build; mkdir -p "$cp"
javac --release 25 -Xlint:all -d "$cp" $src/*.java test/Check.java

java -cp "$cp" com.defold.mangler.Check

status=0
for dir in test/fixtures/*/; do
    name=$(basename "$dir")

    excludes=""
    [ -f "$dir/exclude.txt" ] && excludes="$dir/exclude.txt"

    # 1. mangle
    out=".build/$name"
    java -cp "$cp" com.defold.mangler.Check mangle "$dir" "$out" "$excludes"

    # 2. lexer-based kept/gone verification
    kept=""; gone=""
    [ -f "$dir/kept.txt" ] && kept="$dir/kept.txt"
    [ -f "$dir/gone.txt" ] && gone="$dir/gone.txt"
    java -cp "$cp" com.defold.mangler.Check verify "$out" "$kept" "$gone"

    # 3. runtime equivalence
    (cd "$dir" && luajit main.lua) > ".build/$name.expected"
    (cd "$out" && luajit main.lua) > ".build/$name.actual"
    if ! diff -u ".build/$name.expected" ".build/$name.actual"; then
        echo "FAIL $name: runtime output differs" >&2
        status=1; continue
    fi

    # 4. determinism: two independent manglings must be byte-identical
    out2=".build/$name.again"
    java -cp "$cp" com.defold.mangler.Check mangle "$dir" "$out2" "$excludes"
    if ! diff -rq "$out" "$out2" >/dev/null; then
        echo "FAIL $name: mangling is not deterministic" >&2
        status=1; continue
    fi

    echo "$name: ok"
done

rm -rf .build
exit $status