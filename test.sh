#!/bin/sh

# Needs JDK >= 25 and luajit

set -eu
cd "$(dirname "$0")"
src=mangler/com/defold/mangler

rm -rf .build; mkdir -p .build/classes
javac --release 25 -Xlint:all -d .build/classes $src/*.java test/Check.java
java -cp .build/classes com.defold.mangler.Check

for dir in test/fixtures/*/; do
    name=$(basename "$dir")
    java -cp .build/classes com.defold.mangler.Check "$dir" ".build/$name"
    (cd "$dir" && luajit main.lua) > ".build/$name.expected"
    (cd ".build/$name" && luajit main.lua) > ".build/$name.actual"
    diff ".build/$name.expected" ".build/$name.actual"
    for n in $(cat "$dir/gone.txt"); do
        if cat ".build/$name"/*.lua | grep -v KEEP | grep -qw -- "$n"; then echo "$name: '$n' was not renamed"; exit 1; fi
    done
    for n in $(cat "$dir/kept.txt"); do
        if ! cat ".build/$name"/*.lua | grep -qw -- "$n"; then echo "$name: '$n' should have been left alone"; exit 1; fi
    done
    echo "$name: ok"
done

rm -rf .build