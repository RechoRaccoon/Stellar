#!/bin/sh
# One-time setup for check.py: the Kotlin/Native compiler and the source
# code of the libraries Stellar's shared module uses, all from GitHub
# (so it works where Maven Central can't be reached). About 2 GB.
#
#   sh tools/ios-typecheck/setup.sh            # into ~/.stellar-typecheck
#   STELLAR_TC_HOME=/some/dir sh tools/ios-typecheck/setup.sh
#
# Versions follow build.gradle.kts / shared/build.gradle.kts — change them
# here when those change.
set -e
HOME_DIR="${STELLAR_TC_HOME:-$HOME/.stellar-typecheck}"
KOTLIN=2.1.21
mkdir -p "$HOME_DIR/src"
cd "$HOME_DIR"

if [ ! -d kotlin-native ]; then
  curl -fsSL -o kn.tar.gz "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN/kotlin-native-prebuilt-linux-x86_64-$KOTLIN.tar.gz"
  tar xzf kn.tar.gz && mv "kotlin-native-prebuilt-linux-x86_64-$KOTLIN" kotlin-native && rm kn.tar.gz
fi
if [ ! -d kotlinc ]; then
  curl -fsSL -o kc.zip "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN/kotlin-compiler-$KOTLIN.zip"
  unzip -q kc.zip && rm kc.zip
fi

clone() { # repo tag dir
  [ -d "src/$3" ] || git clone -q --depth 1 --branch "$2" "https://github.com/$1" "src/$3"
}
# Compose Multiplatform (a fork of all of androidx): only the parts needed.
if [ ! -d src/cmc ]; then
  git clone -q --depth 1 --branch v1.8.2 --filter=blob:none --sparse https://github.com/JetBrains/compose-multiplatform-core src/cmc
  git -C src/cmc sparse-checkout set compose/runtime compose/ui compose/animation compose/foundation \
    compose/material3 compose/material collection annotation lifecycle savedstate datastore
fi
clone JetBrains/compose-multiplatform v1.8.2 cmp
clone JetBrains/skiko v0.9.4.2 skiko
clone Kotlin/kotlinx.coroutines 1.10.2 coroutines
clone Kotlin/kotlinx-atomicfu 0.27.0 atomicfu
clone Kotlin/kotlinx.serialization v1.8.1 serialization
clone Kotlin/kotlinx-io 0.6.0 kxio
clone coil-kt/coil 3.2.0 coil
clone square/okio parent-3.11.0 okio
clone ktorio/ktor 3.1.3 ktor
echo "Ready: $HOME_DIR"
