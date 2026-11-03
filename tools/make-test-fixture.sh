#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$PROJECT/build/fixtures" "$PROJECT/app/src/androidTest/assets"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=24:duration=6' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=6' \
    -c:v libx264 -pix_fmt yuv420p -g 24 -keyint_min 24 -sc_threshold 0 -bf 2 \
    -c:a aac -b:a 64k -movflags +faststart -y "$PROJECT/build/fixtures/input.mp4"
ffmpeg -hide_banner -loglevel error -display_rotation:v:0 -90 -i "$PROJECT/build/fixtures/input.mp4" \
    -c copy -y "$PROJECT/app/src/androidTest/assets/rotated-av.mp4"
