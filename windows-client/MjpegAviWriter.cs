using System;
using System.Collections.Generic;
using System.IO;

namespace WiFiShareTray;

/// <summary>
/// Hand-rolled AVI v1 muxer that stores incoming JPEG frames verbatim
/// under the MJPG codec. Since the screen-cast stream already arrives
/// as JPEG, we just wrap each frame in an AVI container — no
/// re-encoding, no external dependencies (no FFmpeg, no Windows Media
/// Foundation P/Invoke). Output plays in VLC, MPC-HC, modern Media
/// Player, and most video editors.
///
/// AVI v1 size limit is 2 GB practical / 4 GB hard — fine for screen
/// recordings up to ~30 minutes at typical Balanced-mode bitrates.
/// </summary>
public sealed class MjpegAviWriter : IDisposable
{
    private readonly FileStream _fs;
    private readonly BinaryWriter _bw;
    private readonly int _width;
    private readonly int _height;
    private readonly object _lock = new();
    private readonly List<IndexEntry> _index = new();
    private bool _closed;

    // Fix-up positions — these fields in the AVI header depend on values
    // we only know at the end (total bytes, total frames, average rate).
    // We seek back and rewrite them in Dispose().
    private long _riffSizePos;
    private long _moviSizePos;
    private long _moviStart;
    private long _avihMicroSecPos;
    private long _avihMaxBytesPos;
    private long _avihTotalFramesPos;
    private long _strhRatePos;
    private long _strhLengthPos;
    private long _strhSuggestedBufSizePos;
    private long _strfBiSizeImagePos;

    private long _bytesWritten;
    private int _maxFrameSize;

    private readonly DateTime _startUtc = DateTime.UtcNow;

    public int FrameCount { get; private set; }
    public TimeSpan Elapsed => DateTime.UtcNow - _startUtc;
    public string Path { get; }

    private struct IndexEntry
    {
        public uint OffsetFromMovi;
        public uint Size;
    }

    public MjpegAviWriter(string path, int width, int height)
    {
        Path = path;
        _width = width;
        _height = height;
        _fs = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
        _bw = new BinaryWriter(_fs);
        WriteHeader();
    }

    /// <summary>
    /// Writes one JPEG frame as an `00dc` chunk inside the movi list.
    /// JPEG bytes are appended verbatim; pad byte added when length is
    /// odd so all chunks stay 16-bit aligned (AVI spec requires this).
    /// </summary>
    public void WriteFrame(byte[] jpeg)
    {
        if (jpeg == null || jpeg.Length == 0) return;
        lock (_lock)
        {
            if (_closed) return;
            var offset = (uint)(_fs.Position - _moviStart);
            _bw.Write(0x63643030); // '00dc' little-endian
            _bw.Write((uint)jpeg.Length);
            _bw.Write(jpeg);
            if ((jpeg.Length & 1) != 0) _bw.Write((byte)0); // word-align

            _index.Add(new IndexEntry { OffsetFromMovi = offset, Size = (uint)jpeg.Length });
            FrameCount++;
            _bytesWritten += jpeg.Length;
            if (jpeg.Length > _maxFrameSize) _maxFrameSize = jpeg.Length;
        }
    }

    private void WriteHeader()
    {
        // RIFF[size]AVI
        _bw.Write(0x46464952); // 'RIFF'
        _riffSizePos = _fs.Position;
        _bw.Write(0u); // placeholder for total size
        _bw.Write(0x20495641); // 'AVI '

        // LIST[hdrlSize]hdrl
        _bw.Write(0x5453494C); // 'LIST'
        var hdrlSizePos = _fs.Position;
        _bw.Write(0u);
        var hdrlStart = _fs.Position;
        _bw.Write(0x6C726468); // 'hdrl'

        // avih chunk (main AVI header) — 56 bytes
        _bw.Write(0x68697661); // 'avih'
        _bw.Write(56u);
        _avihMicroSecPos = _fs.Position;
        _bw.Write(0u);            // dwMicroSecPerFrame (fix later)
        _avihMaxBytesPos = _fs.Position;
        _bw.Write(0u);            // dwMaxBytesPerSec (fix later)
        _bw.Write(0u);            // dwPaddingGranularity
        _bw.Write(0x10u);         // dwFlags: AVIF_HASINDEX
        _avihTotalFramesPos = _fs.Position;
        _bw.Write(0u);            // dwTotalFrames (fix later)
        _bw.Write(0u);            // dwInitialFrames
        _bw.Write(1u);            // dwStreams
        _bw.Write(0u);            // dwSuggestedBufferSize (fix later by strh)
        _bw.Write((uint)_width);  // dwWidth
        _bw.Write((uint)_height); // dwHeight
        _bw.Write(0u); _bw.Write(0u); _bw.Write(0u); _bw.Write(0u); // dwReserved[4]

        // LIST[strlSize]strl
        _bw.Write(0x5453494C); // 'LIST'
        var strlSizePos = _fs.Position;
        _bw.Write(0u);
        var strlStart = _fs.Position;
        _bw.Write(0x6C727473); // 'strl'

        // strh chunk — 56 bytes
        _bw.Write(0x68727473); // 'strh'
        _bw.Write(56u);
        _bw.Write(0x73646976); // fccType = 'vids'
        _bw.Write(0x47504A4D); // fccHandler = 'MJPG'
        _bw.Write(0u);          // dwFlags
        _bw.Write((ushort)0);   // wPriority
        _bw.Write((ushort)0);   // wLanguage
        _bw.Write(0u);          // dwInitialFrames
        _strhRatePos = _fs.Position;
        _bw.Write(1u);          // dwScale (denominator) — fix later
        _bw.Write(24u);         // dwRate (numerator) — fix later (we patch both)
        _bw.Write(0u);          // dwStart
        _strhLengthPos = _fs.Position;
        _bw.Write(0u);          // dwLength (frame count) — fix later
        _strhSuggestedBufSizePos = _fs.Position;
        _bw.Write(0u);          // dwSuggestedBufferSize — fix later
        _bw.Write(unchecked((uint)-1)); // dwQuality
        _bw.Write(0u);          // dwSampleSize (0 = variable)
        _bw.Write((short)0); _bw.Write((short)0);   // rcFrame.left, top
        _bw.Write((short)_width); _bw.Write((short)_height); // right, bottom

        // strf chunk (BITMAPINFOHEADER for video) — 40 bytes
        _bw.Write(0x66727473); // 'strf'
        _bw.Write(40u);
        _bw.Write(40u);             // biSize
        _bw.Write(_width);          // biWidth
        _bw.Write(_height);         // biHeight
        _bw.Write((ushort)1);       // biPlanes
        _bw.Write((ushort)24);      // biBitCount
        _bw.Write(0x47504A4D);      // biCompression = 'MJPG'
        _strfBiSizeImagePos = _fs.Position;
        _bw.Write((uint)(_width * _height * 3)); // biSizeImage placeholder
        _bw.Write(0);               // biXPelsPerMeter
        _bw.Write(0);               // biYPelsPerMeter
        _bw.Write(0u);              // biClrUsed
        _bw.Write(0u);              // biClrImportant

        var strlEnd = _fs.Position;
        Patch32(strlSizePos, (uint)(strlEnd - strlStart));

        var hdrlEnd = _fs.Position;
        Patch32(hdrlSizePos, (uint)(hdrlEnd - hdrlStart));

        // LIST[size]movi
        _bw.Write(0x5453494C); // 'LIST'
        _moviSizePos = _fs.Position;
        _bw.Write(0u);
        _moviStart = _fs.Position;
        _bw.Write(0x69766F6D); // 'movi'
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_closed) return;
            _closed = true;
            try
            {
                FinalizeFile();
            }
            finally
            {
                _bw.Dispose();
                _fs.Dispose();
            }
        }
    }

    private void FinalizeFile()
    {
        // Cap movi size, then write idx1 chunk.
        var moviEnd = _fs.Position;
        var moviSize = (uint)(moviEnd - (_moviSizePos + 4));
        Patch32(_moviSizePos, moviSize);

        _fs.Seek(moviEnd, SeekOrigin.Begin);
        // idx1[16 * n]
        _bw.Write(0x31786469); // 'idx1'
        _bw.Write((uint)(_index.Count * 16));
        foreach (var e in _index)
        {
            _bw.Write(0x63643030);   // 'AVIIF_KEYFRAME' chunk id '00dc' — MJPG every frame is a keyframe
            _bw.Write(0x10u);        // AVIIF_KEYFRAME
            _bw.Write(e.OffsetFromMovi); // offset from start of movi+4 (first byte of '00dc')
            _bw.Write(e.Size);
        }

        // Patch RIFF size = total file size - 8.
        var fileSize = (uint)_fs.Position;
        Patch32(_riffSizePos, fileSize - 8);

        // Compute timing. We use elapsed wallclock vs frame count to
        // derive an average framerate, which AVI represents as
        // dwRate/dwScale. Microseconds-per-frame goes into avih.
        var elapsedSec = Math.Max(0.001, (DateTime.UtcNow - _startUtc).TotalSeconds);
        var avgFps = FrameCount / elapsedSec;
        if (avgFps <= 0 || double.IsNaN(avgFps) || double.IsInfinity(avgFps)) avgFps = 24;
        var microsecPerFrame = (uint)Math.Round(1_000_000.0 / avgFps);
        var maxBytesPerSec = (uint)Math.Round(avgFps * Math.Max(_maxFrameSize, 1));

        // Encode framerate as numerator/denominator. Scale=1000 lets us
        // express fps to 0.001 precision without floating-point in the
        // header.
        var scale = 1000u;
        var rate = (uint)Math.Round(avgFps * scale);

        Patch32(_avihMicroSecPos, microsecPerFrame);
        Patch32(_avihMaxBytesPos, maxBytesPerSec);
        Patch32(_avihTotalFramesPos, (uint)FrameCount);

        Patch32(_strhRatePos, scale);          // dwScale
        Patch32(_strhRatePos + 4, rate);       // dwRate
        Patch32(_strhLengthPos, (uint)FrameCount);
        Patch32(_strhSuggestedBufSizePos, (uint)_maxFrameSize);

        _fs.Flush();
    }

    private void Patch32(long pos, uint value)
    {
        var save = _fs.Position;
        _fs.Seek(pos, SeekOrigin.Begin);
        _bw.Write(value);
        _fs.Seek(save, SeekOrigin.Begin);
    }
}
