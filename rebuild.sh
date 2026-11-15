#!/bin/sh

set -eu; src=mangler/src jar=mangler/plugins/share/pluginMangler.jar

[ -f bob.jar ] || {
    url="$(curl -fsSL -o /dev/null -w '%{url_effective}' https://github.com/defold/defold/releases/latest)"
    curl -fsSL -o bob.jar "https://github.com/defold/defold/releases/download/${url##*/}/bob.jar"   
}

rm -rf build; mkdir -p build ${jar%/*}

find $src -name '*.java' -exec javac -cp bob.jar -d build {} +

jar cf $jar -C build .

rm -rf bob.jar build
