FFmpeg shared DLLs for the WiFi Share tray
==========================================

The Windows tray uses FFmpeg.AutoGen to decode H.264 screen-cast frames
from the phone. The .NET package only ships P/Invoke bindings — you
need to drop the matching native DLLs into THIS folder before building.

The csproj copies every .dll in this folder into bin/.../ffmpeg/ on
build, and ScreenStreamForm calls ffmpeg.RootPath to that location at
startup.

What to put here
----------------
For H.264 decoding + YUV→BGR scaling we need just four DLLs:

    avcodec-61.dll
    avformat-61.dll
    avutil-59.dll
    swscale-8.dll

(The version-suffix numbers come from FFmpeg 7.x; for 6.x they're
avcodec-60, avformat-60, avutil-58, swscale-7. Pick ONE major
version and stick with it — mixing them across the four files crashes
on first call. FFmpeg.AutoGen 7.1.1 pairs with FFmpeg 7.x.)

Where to get them
-----------------
Easiest source: https://www.gyan.dev/ffmpeg/builds/
Download "ffmpeg-release-shared.7z" (currently FFmpeg 7.x release).
Extract — copy the four DLLs from bin/ here. The .exe and .a files
aren't needed.

Alternative: https://github.com/BtbN/FFmpeg-Builds/releases
("ffmpeg-master-latest-win64-lgpl-shared.zip" — same four DLLs in bin/)

License note
------------
The default shared builds are LGPL-licensed. If you also want non-free
codecs (GPL build), the size doubles and license obligations change.
For decode-only H.264 the LGPL shared build is enough.

Why DLLs aren't checked into git
--------------------------------
They're 20–30 MB and bumping them on every FFmpeg release would bloat
the history. .gitignore in this folder excludes *.dll so you can keep
your local copies without polluting commits.
