#!/bin/sh

# Rebuilds the plugin against latest bob.jar. Needs JDK >= 25.

set -eu; jar=mangler/plugins/share/pluginMangler.jar

[ -f bob.jar ] || curl -fsSL -o bob.jar https://github.com/defold/defold/releases/latest/download/bob.jar

rm -rf .build; mkdir -p .build ${jar%/*}

javac --release 25 -cp bob.jar -d .build $(find mangler/com/defold -name '*.java')

jar cf $jar -C .build .; rm -rf .build bob.jar
