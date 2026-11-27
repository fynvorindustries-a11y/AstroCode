#!/usr/bin/env bash
# Reproducible build + test run using only a JDK 17+, JavaFX and Gson jars (no network needed).
# Usage: ./build.sh [build|test|all]   (default: all)
# Override locations with: JAVAFX_LIB, GSON_JAR, JUNIT_JAR
set -euo pipefail
cd "$(dirname "$0")"
JAVAFX_LIB="${JAVAFX_LIB:-/usr/share/openjfx/lib}"
GSON_JAR="${GSON_JAR:-/usr/share/java/gson.jar}"
JUNIT_JAR="${JUNIT_JAR:-/usr/share/java/junit-platform-console-standalone.jar}"
FX_MODS=javafx.controls,javafx.graphics,javafx.swing,javafx.media
OUT=build/classes; TOUT=build/test-classes
MODE="${1:-all}"

build() {
  rm -rf "$OUT"; mkdir -p "$OUT"
  ( cd src/main/java && javac -Xlint:-options -encoding UTF-8 -d "../../../$OUT" \
      --module-path "$JAVAFX_LIB" --add-modules $FX_MODS -cp "$GSON_JAR" $(find com -name '*.java') )
  cp -r src/main/resources/. "$OUT"/
  echo "BUILD OK"
}

tests() {
  rm -rf "$TOUT"; mkdir -p "$TOUT"
  javac -encoding UTF-8 -d "$TOUT" --module-path "$JAVAFX_LIB" --add-modules javafx.base,javafx.graphics -cp "$OUT:$GSON_JAR:$JUNIT_JAR" $(find src/test -name '*.java')
  FX=(--module-path "$JAVAFX_LIB" --add-modules javafx.base,javafx.graphics -Dprism.order=sw)
  CP="$TOUT:$OUT:$GSON_JAR"
  fail=0
  echo "== JUnit"; java "${FX[@]}" -jar "$JUNIT_JAR" -cp "$CP" --scan-classpath "$TOUT" --disable-banner --disable-ansi-colors \
      --details=summary || fail=1
  for t in com.averolauncher.rank.RankSystemTests com.averolauncher.rank.RankE2ETest com.averolauncher.update.incremental.IncrementalUpdateTests; do
    echo "== $t"; java "${FX[@]}" -cp "$CP" "$t" > build/$t.log 2>&1 || fail=1
    tail -1 build/$t.log; grep -q '^FAIL\|  FAIL ' build/$t.log && { echo "  failures in build/$t.log"; fail=1; }
  done
  [ $fail -eq 0 ] && echo "ALL TESTS PASSED" || { echo "TESTS FAILED"; exit 1; }
}

case "$MODE" in build) build;; test) tests;; all) build; tests;; *) echo "usage: $0 [build|test|all]"; exit 2;; esac
