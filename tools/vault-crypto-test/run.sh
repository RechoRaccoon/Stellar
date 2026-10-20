#!/bin/sh
# Compiles Stellar's Private Sync crypto and merge with their tests and runs
# them on the JVM (needs tools/ios-typecheck/setup.sh's compiler).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../shared/src/commonMain/kotlin/com/mediaviewer/util"
KC=${STELLAR_TC_HOME:-$HOME/.stellar-typecheck}/kotlinc/bin/kotlinc
LIB=${KOTLINX_SERIALIZATION_JARS:-$(ls /opt/gradle-*/lib/kotlinx-serialization-core-jvm-*.jar | head -1):$(ls /opt/gradle-*/lib/kotlinx-serialization-json-jvm-*.jar | head -1)}
OUT=${TMPDIR:-/tmp}/vault-crypto-test
mkdir -p "$OUT"
"$KC" -cp "$LIB" "$HERE/VaultCryptoTest.kt" "$HERE/JsonMergeTest.kt" "$SRC/crypto/VaultCrypto.kt" "$SRC/JsonMerge.kt" -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/test.jar:$LIB" VaultCryptoTestKt
