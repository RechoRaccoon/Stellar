#!/bin/sh
# One-time setup for check.py, after tools/ios-typecheck/setup.sh (which
# brings the Kotlin compiler and the library sources): the plain Java
# libraries the desktop code uses, as jars. Versions follow
# shared/build.gradle.kts — change them here when those change.
#
#   sh tools/desktop-typecheck/setup.sh
set -e
HOME_DIR="${STELLAR_TC_HOME:-$HOME/.stellar-typecheck}"
# Google's mirror of Maven Central (Maven Central itself rate-limits).
M="${MAVEN_MIRROR:-https://maven-central.storage-download.googleapis.com/maven2}"
mkdir -p "$HOME_DIR/jars"
cd "$HOME_DIR/jars"
get() { f=$(basename "$1"); [ -s "$f" ] || curl -fsSL -o "$f" "$M/$1"; }
get com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
get com/squareup/okio/okio-jvm/3.11.0/okio-jvm-3.11.0.jar
get com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
get org/bytedeco/javacv/1.5.11/javacv-1.5.11.jar
get org/bytedeco/javacpp/1.5.11/javacpp-1.5.11.jar
get org/bytedeco/ffmpeg/7.1-1.5.11/ffmpeg-7.1-1.5.11.jar
for m in base graphics controls web swing media; do
  get org/openjfx/javafx-$m/21.0.6/javafx-$m-21.0.6-linux.jar
done
echo "Ready: $HOME_DIR/jars"
