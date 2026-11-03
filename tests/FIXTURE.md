# Synthetic Android test media

Generate with `bash tools/make-test-fixture.sh` (FFmpeg >= 6). All files are
excluded from git and bundled only in the Android test APK, not the editor.
No user media or downloaded footage is used.

| Asset | Video | Audio | Purpose |
| --- | --- | --- | --- |
| rotated-av.mp4 | 6 s,320×180,24 fps,H.264,1 s GOP,2 B-frames,90° clockwise display matrix | AAC mono48kHz440Hz | trim,rotation,remux,sequence |
| chroma-green.mp4 | 2 s,320×180,24 fps;green#00ff00 background,red rectangle x120 y45 w80 h90 | AAC mono48kHz440Hz | chroma/pixel checks |
| different-av.mp4 | 2 s,240×320,30 fps,H.264 | AAC mono44.1kHz880Hz | mixed input geometry/audio |
| color-red.mp4 | 2 s,160×90,24 fps,solid red | none | filters,transitions,background |
| color-blue.mp4 | 2 s,160×90,24 fps,solid blue | none | overlays/compositing |

All outputs use yuv420p and fast-start MP4. FFprobe reports the clockwise display
matrix as -90°. The movie presentation duration of rotated-av is exactly6s even
when an Android extractor reports padding in the video-track duration.
