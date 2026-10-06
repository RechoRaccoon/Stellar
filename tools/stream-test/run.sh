#!/bin/sh
# Pushes a real stream through the iOS RTMP publisher into ffmpeg and checks
# what arrives. Needs ffmpeg, Java and kotlinc (tools/ios-typecheck/setup.sh).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="${STELLAR_TC_HOME:-$HOME/.stellar-typecheck}"
SRC="$HERE/../../shared/src/iosMain/kotlin/com/mediaviewer/stream"
W="$TC/out/stream-test"; mkdir -p "$W"; rm -f "$W"/*.flv
"$TC/kotlinc/bin/kotlinc" -nowarn -d "$W/test.jar" -include-runtime "$HERE/Shims.kt" "$HERE/PlatformShims.kt" "$HERE/StreamTest.kt" "$SRC/RtmpPublisher.kt" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
FPS=30
ffmpeg -v error -y -f lavfi -i testsrc=size=320x240:rate=$FPS -t 3 -c:v libx264 -profile:v baseline -bf 0 -g 30 -pix_fmt yuv420p -bsf:v h264_mp4toannexb -f h264 "$W/in.h264"
ffmpeg -v error -y -f lavfi -i sine=frequency=440:sample_rate=44100 -t 3 -c:a aac -b:a 96k -ac 1 -f adts "$W/in.aac"
PORT=19350
ffmpeg -v error -y -listen 1 -timeout 15 -i "rtmp://127.0.0.1:$PORT/live/testkey" -c copy "$W/received.flv" &
SERVER=$!
sleep 1
java -cp "$W/test.jar" StreamTestKt "rtmp://127.0.0.1:$PORT/live" testkey "$W/in.h264" "$W/in.aac" $FPS 2>&1 | grep -v JAVA_TOOL_OPTIONS
wait $SERVER || true
echo "--- what ffmpeg received:"
ffprobe -v error -show_entries stream=codec_name,width,height,nb_frames,sample_rate -show_entries format=duration -of compact "$W/received.flv"
ffmpeg -v error -i "$W/received.flv" -f null - && echo "decodes without errors"
