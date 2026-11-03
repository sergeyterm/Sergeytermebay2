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
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x00ff00:size=320x180:rate=24:duration=2' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' \
    -vf 'drawbox=x=120:y=45:w=80:h=90:color=red:t=fill' \
    -c:v libx264 -pix_fmt yuv420p -g 24 -bf 2 -c:a aac -b:a 64k -movflags +faststart \
    -y "$PROJECT/app/src/androidTest/assets/chroma-green.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=240x320:rate=30:duration=2' \
    -f lavfi -i 'sine=frequency=880:sample_rate=44100:duration=2' \
    -c:v libx264 -pix_fmt yuv420p -g 30 -bf 2 -c:a aac -b:a 64k -movflags +faststart \
    -y "$PROJECT/app/src/androidTest/assets/different-av.mp4"
for COLOR in red blue; do
    ffmpeg -hide_banner -loglevel error -f lavfi -i "color=c=$COLOR:size=160x90:rate=24:duration=2" \
        -c:v libx264 -pix_fmt yuv420p -g 24 -bf 2 -an -movflags +faststart \
        -y "$PROJECT/app/src/androidTest/assets/color-$COLOR.mp4"
done
