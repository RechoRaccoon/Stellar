#!/bin/sh
# Runs the plain-Kotlin half of the iOS VRM code against real .vrm files.
#   sh tools/vrm-test/run.sh path/to/a.vrm [more.vrm …]            checks
#   sh tools/vrm-test/run.sh --render out-folder a.vrm [more.vrm …]  pictures (see Render.kt)
#   sh tools/vrm-test/run.sh --lift [a.vrm …]                        body/hand tracking maths (see LiftTest.kt)
# Needs kotlinc (tools/ios-typecheck/setup.sh downloads one) and Java.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="${STELLAR_TC_HOME:-$HOME/.stellar-typecheck}"
SRC="$HERE/../../shared/src/iosMain/kotlin/com/mediaviewer/vrm"
OUT="$TC/out/vrm-test.jar"
mkdir -p "$TC/out"
"$TC/kotlinc/bin/kotlinc" -nowarn -d "$OUT" -include-runtime \
  "$HERE/Shims.kt" "$HERE/PlatformShims.kt" "$HERE/VrmTest.kt" "$HERE/Render.kt" "$HERE/LiftTest.kt" \
  "$SRC/Quaternion.kt" "$SRC/OneEuroFilter.kt" "$SRC/VrmFile.kt" "$SRC/GltfDoc.kt" \
  "$SRC/AvatarRetargeter.kt" "$SRC/VrmSpring.kt" "$SRC/VisionLift.kt" 2>&1 | grep -v "JAVA_TOOL_OPTIONS" || true
if [ "$1" = "--render" ]; then
  shift
  java -Xmx3g -Djava.awt.headless=true -cp "$OUT" RenderKt "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
elif [ "$1" = "--lift" ]; then
  shift
  java -Xmx2g -cp "$OUT" LiftTestKt "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
else
  java -Xmx2g -cp "$OUT" VrmTestKt "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
fi
