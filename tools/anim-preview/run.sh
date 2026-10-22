#!/usr/bin/env bash
# Превью анимаций тем же движком, что и в моде. Нужны JDK 17+ и gson (есть в Gradle).
#   tools/anim-preview/run.sh clip red_cast
#   tools/anim-preview/run.sh loco run
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GSON="${GSON_JAR:-$(ls /opt/gradle*/lib/gson-*.jar 2>/dev/null | head -1)}"
if [ -z "$GSON" ]; then
  GSON="$(find "$HOME/.gradle" -name 'gson-2*.jar' 2>/dev/null | head -1)"
fi
BUILD="$ROOT/tools/anim-preview/build"
mkdir -p "$BUILD"
javac -encoding UTF-8 -nowarn -cp "$GSON" -d "$BUILD" \
  "$ROOT"/src/main/java/com/kira/jujutsuneon/anim/*.java "$ROOT/tools/anim-preview/Preview.java"
java -Djava.awt.headless=true -cp "$BUILD:$GSON" Preview "${1:-loco}" "${2:-run}" "$ROOT"
