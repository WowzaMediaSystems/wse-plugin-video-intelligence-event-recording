#!/bin/bash

builddir="${1:-.}"

if [ ! -f "$(realpath "$builddir")/gradlew" ]; then
	echo "Error: gradlew not found ($(realpath "$builddir")/gradlew)"
	exit 1
fi

gradle_cmd="${2:-build}"

echo "${gradle_cmd}ing: $(realpath "$builddir")"
docker run --platform linux/amd64 -v "$(realpath "$builddir")":/code -e GRADLECMD="$gradle_cmd" -e INTERACTIVE="false" wowza/wse-plugin-builder:4.10.0
