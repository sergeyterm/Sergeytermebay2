Synthetic device-test fixture: `app/src/androidTest/assets/rotated-av.mp4`.

Generated locally from FFmpeg testsrc2 and sine generators: six seconds, 320×180,
24 fps, H.264 with a one-second closed GOP and two B-frames, AAC mono 48 kHz.
The display matrix is a clockwise 90° rotation (FFprobe reports -90).
No user media is included.

```sh
ffmpeg -f lavfi -i 'testsrc2=size=320x180:rate=24:duration=6' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=6' \
  -c:v libx264 -pix_fmt yuv420p -g 24 -keyint_min 24 -sc_threshold 0 -bf 2 \
  -c:a aac -b:a 64k -movflags +faststart fixture.mp4
ffmpeg -display_rotation:v:0 -90 -i fixture.mp4 -c copy rotated-av.mp4
```

The fixture is used only by the test APK, never bundled in the editor APK.
These integration tests have been compiled, but have not yet run on Android.
