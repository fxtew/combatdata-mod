#!/bin/sh
# Compiles and runs the core tests without Gradle or Minecraft. Requires JDK 21+.
set -e
cd "$(dirname "$0")/.."
OUT=build/core-tests
rm -rf "$OUT" && mkdir -p "$OUT"
JAVAC="${JAVAC:-javac}"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
$JAVAC -Xlint:all -encoding UTF-8 -d "$OUT" $(find src/main/java/dev/combatdata/core src/test/java/dev/combatdata/core -name '*.java')
java -Dstdout.encoding=UTF-8 -cp "$OUT" dev.combatdata.core.CoreTests
