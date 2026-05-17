using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using FFmpeg.AutoGen;

namespace WiFiShareTray;

/// <summary>
/// Connects to <c>/ws/screen</c>, reads the H.264 hello envelope +
/// annex-B access units, decodes via FFmpeg and yields BGRA <see cref="Bitmap"/>s
/// (one per decoded frame) through <see cref="FrameDecoded"/>.
///
/// All FFmpeg calls happen on a single dedicated decode thread.
/// Bitmaps emitted by FrameDecoded are owned by the caller — typical
/// use is to assign into a PictureBox.Image and dispose the previous.
/// </summary>
public sealed class H264StreamDecoder : IDisposable
{
    private readonly string _baseUrl;
    private readonly string? _password;
    private readonly string? _bearerToken;
    private CancellationTokenSource? _cts;

    public event Action<Bitmap>? FrameDecoded;
    public event Action<string>? StatusChanged;
    public event Action<string>? Failed;
    public event Action<byte[]>? RawAccessUnit; // for AVI recording etc.

    private long _frameCount;
    public long FrameCount => _frameCount;
    public int Width { get; private set; }
    public int Height { get; private set; }

    public H264StreamDecoder(string baseUrl, string? password, string? bearerToken)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _password = password;
        _bearerToken = bearerToken;
    }

    public void Start()
    {
        if (_cts != null) return;
        if (!FFmpegSetup.Configure())
        {
            Failed?.Invoke("FFmpeg DLLs not found. " + (FFmpegSetup.FailureReason ?? ""));
            return;
        }
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        _cts = null;
    }

    public void Dispose() => Stop();

    private async Task Loop(CancellationToken ct)
    {
        var backoffMs = 500;
        while (!ct.IsCancellationRequested)
        {
            ClientWebSocket? ws = null;
            try
            {
                ws = new ClientWebSocket();
                // The phone accepts EITHER `Authorization: Bearer <token>`
                // (preferred) or `Authorization: Basic <base64 user:PIN>`.
                // ClientWebSocket lets us add arbitrary handshake headers,
                // so unlike browser-side WebSocket we *can* authenticate
                // cleanly without cookie gymnastics.
                if (!string.IsNullOrEmpty(_bearerToken))
                {
                    ws.Options.SetRequestHeader("Authorization", "Bearer " + _bearerToken);
                }
                else if (!string.IsNullOrEmpty(_password))
                {
                    var basic = Convert.ToBase64String(Encoding.UTF8.GetBytes($"user:{_password}"));
                    ws.Options.SetRequestHeader("Authorization", "Basic " + basic);
                }
                // NanoWSD inherits NanoHTTPD's 5 s socket-read timeout
                // even after the WS upgrade — so if WE don't send
                // anything inbound for 5 s, the server times out the
                // read() and closes the connection (manifests as
                // periodic drops every ~5 s). .NET-managed protocol
                // pings reset the timer; 3 s is safely under the limit.
                ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(3);
                var wsUri = new Uri(_baseUrl.Replace("http://", "ws://").Replace("https://", "wss://")
                    + "/ws/screen");
                // Initial connect: show "Connecting"; reconnects: stay
                // quiet so the user doesn't see "Disconnected" flash in
                // and out for every WiFi blip — the last decoded frame
                // remains visible on the PictureBox until new ones arrive.
                if (_frameCount == 0)
                {
                    StatusChanged?.Invoke("Connecting (H.264)…");
                }
                else
                {
                    StatusChanged?.Invoke($"Reconnecting · {_frameCount} frames so far");
                }
                await ws.ConnectAsync(wsUri, ct);
                backoffMs = 500;
                await ReadLoop(ws, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception)
            {
                // Don't surface raw exception text — usually scary-looking
                // socket errors that mean "WiFi blipped". The retry loop
                // is silent past the first connect; if it takes more than
                // one cycle we update status with a counter.
                if (_frameCount == 0)
                {
                    StatusChanged?.Invoke("Connecting…");
                }
            }
            finally
            {
                try { ws?.Dispose(); } catch { }
            }
            try { await Task.Delay(backoffMs, ct); } catch { return; }
            backoffMs = Math.Min(backoffMs * 2, 4_000);
        }
    }

    private async Task ReadLoop(ClientWebSocket ws, CancellationToken ct)
    {
        // First frame is a text envelope: {type, codec, width, height, configBase64}.
        // Everything after is binary annex-B H.264.
        byte[]? configBytes = null;
        AVCodecContextHandle? codec = null;
        SwsContextHandle? sws = null;
        AVPacketHandle? packet = null;
        AVFrameHandle? frame = null;
        AVFrameHandle? bgraFrame = null;
        try
        {
            var buf = new byte[256 * 1024];
            using var ms = new MemoryStream();
            while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
            {
                ms.SetLength(0);
                WebSocketReceiveResult result;
                do
                {
                    result = await ws.ReceiveAsync(new ArraySegment<byte>(buf), ct);
                    if (result.MessageType == WebSocketMessageType.Close) return;
                    ms.Write(buf, 0, result.Count);
                } while (!result.EndOfMessage);

                var bytes = ms.ToArray();
                if (result.MessageType == WebSocketMessageType.Text)
                {
                    // hello envelope
                    using var doc = JsonDocument.Parse(bytes);
                    var root = doc.RootElement;
                    var type = root.TryGetProperty("type", out var t) ? t.GetString() : null;
                    if (type == "error")
                    {
                        var reason = root.TryGetProperty("reason", out var r) ? r.GetString() : "unknown";
                        Failed?.Invoke("H.264 stream unavailable: " + reason);
                        return;
                    }
                    if (type != "hello") continue;
                    Width = root.TryGetProperty("width", out var w) ? w.GetInt32() : 0;
                    Height = root.TryGetProperty("height", out var h) ? h.GetInt32() : 0;
                    var b64 = root.TryGetProperty("configBase64", out var c) ? c.GetString() : "";
                    configBytes = Convert.FromBase64String(b64 ?? "");
                    StatusChanged?.Invoke($"H.264 · {Width}×{Height}");

                    // Init decoder now that we know dimensions + SPS/PPS.
                    codec = OpenDecoder(configBytes);
                    sws = OpenScaler(Width, Height);
                    packet = AVPacketHandle.Allocate();
                    frame = AVFrameHandle.Allocate();
                    bgraFrame = AVFrameHandle.AllocateBgra(Width, Height);
                    continue;
                }
                if (codec == null || packet == null || frame == null || bgraFrame == null || sws == null)
                {
                    // Frame arrived before hello — drop it.
                    continue;
                }
                DecodeAccessUnit(codec, sws, packet, frame, bgraFrame, bytes);
                RawAccessUnit?.Invoke(bytes);
            }
        }
        finally
        {
            packet?.Dispose();
            frame?.Dispose();
            bgraFrame?.Dispose();
            sws?.Dispose();
            codec?.Dispose();
        }
    }

    private unsafe void DecodeAccessUnit(
        AVCodecContextHandle codec,
        SwsContextHandle sws,
        AVPacketHandle packet,
        AVFrameHandle frame,
        AVFrameHandle bgraFrame,
        byte[] data)
    {
        fixed (byte* dataPtr = data)
        {
            packet.Ptr->data = dataPtr;
            packet.Ptr->size = data.Length;
            var ret = ffmpeg.avcodec_send_packet(codec.Ptr, packet.Ptr);
            if (ret < 0) return;
            while (true)
            {
                ret = ffmpeg.avcodec_receive_frame(codec.Ptr, frame.Ptr);
                if (ret == ffmpeg.AVERROR(ffmpeg.EAGAIN) || ret == ffmpeg.AVERROR_EOF) break;
                if (ret < 0) break;

                // YUV → BGRA scale (sws_scale handles the colorspace
                // convert + any resize if frame is padded).
                sws_scale(sws, frame, bgraFrame);

                // Wrap the BGRA buffer in a managed Bitmap. We copy
                // into a fresh Bitmap because the AVFrame buffer gets
                // overwritten on the next receive — sharing the
                // pointer would race with the next frame.
                var bmp = BgraFrameToBitmap(bgraFrame, Width, Height);
                _frameCount++;
                FrameDecoded?.Invoke(bmp);

                ffmpeg.av_frame_unref(frame.Ptr);
            }
        }
    }

    private unsafe void sws_scale(
        SwsContextHandle sws, AVFrameHandle src, AVFrameHandle dst)
    {
        // sws_scale signature: src_data, src_linesize, src_slice_y,
        //                      src_slice_h, dst_data, dst_linesize
        // Use the byte pointer arrays from AVFrame directly.
        ffmpeg.sws_scale(
            sws.Ptr,
            src.Ptr->data,
            src.Ptr->linesize,
            0,
            src.Ptr->height,
            dst.Ptr->data,
            dst.Ptr->linesize);
    }

    private static unsafe Bitmap BgraFrameToBitmap(AVFrameHandle bgra, int width, int height)
    {
        var stride = bgra.Ptr->linesize[0];
        // PixelFormat.Format32bppArgb expects BGRA in little-endian
        // memory order (B, G, R, A bytes) — matches FFmpeg's AV_PIX_FMT_BGRA.
        var bmp = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        var rect = new Rectangle(0, 0, width, height);
        var bd = bmp.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        try
        {
            byte* src = bgra.Ptr->data[0];
            byte* dst = (byte*)bd.Scan0;
            for (int y = 0; y < height; y++)
            {
                Buffer.MemoryCopy(src + y * stride, dst + y * bd.Stride,
                    bd.Stride, Math.Min(bd.Stride, stride));
            }
        }
        finally
        {
            bmp.UnlockBits(bd);
        }
        return bmp;
    }

    private static unsafe AVCodecContextHandle OpenDecoder(byte[] extradata)
    {
        var codec = ffmpeg.avcodec_find_decoder(AVCodecID.AV_CODEC_ID_H264);
        if (codec == null) throw new InvalidOperationException("FFmpeg H.264 decoder not present");
        var ctx = ffmpeg.avcodec_alloc_context3(codec);
        if (ctx == null) throw new InvalidOperationException("avcodec_alloc_context3 failed");
        // Hand the SPS+PPS to the decoder so it can parse the
        // first frame correctly — Annex-B in extradata is supported
        // by the H.264 parser.
        ctx->extradata = (byte*)ffmpeg.av_malloc((ulong)extradata.Length + (ulong)ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE);
        ctx->extradata_size = extradata.Length;
        fixed (byte* src = extradata)
        {
            Buffer.MemoryCopy(src, ctx->extradata, extradata.Length, extradata.Length);
        }
        // Tune for low latency — screen cast is mostly P-frames between
        // 1 s keyframes, so reordering is rare; tell the decoder not
        // to buffer for B-frame reorder lookahead.
        ctx->flags |= ffmpeg.AV_CODEC_FLAG_LOW_DELAY;
        var ret = ffmpeg.avcodec_open2(ctx, codec, null);
        if (ret < 0)
        {
            ffmpeg.avcodec_free_context(&ctx);
            throw new InvalidOperationException($"avcodec_open2 failed ({ret})");
        }
        return new AVCodecContextHandle(ctx);
    }

    private static unsafe SwsContextHandle OpenScaler(int width, int height)
    {
        var ctx = ffmpeg.sws_getContext(
            width, height, AVPixelFormat.AV_PIX_FMT_YUV420P,
            width, height, AVPixelFormat.AV_PIX_FMT_BGRA,
            ffmpeg.SWS_BILINEAR, null, null, null);
        if (ctx == null) throw new InvalidOperationException("sws_getContext failed");
        return new SwsContextHandle(ctx);
    }
}

// ── Thin handle types ─────────────────────────────────────────────
// FFmpeg's C structs are unmanaged — wrap each in a small IDisposable
// so the using/finally cleanup paths read naturally.

internal sealed unsafe class AVCodecContextHandle : IDisposable
{
    public AVCodecContext* Ptr;
    public AVCodecContextHandle(AVCodecContext* p) { Ptr = p; }
    public void Dispose()
    {
        if (Ptr != null)
        {
            var p = Ptr;
            ffmpeg.avcodec_free_context(&p);
            Ptr = null;
        }
    }
}

internal sealed unsafe class SwsContextHandle : IDisposable
{
    public SwsContext* Ptr;
    public SwsContextHandle(SwsContext* p) { Ptr = p; }
    public void Dispose()
    {
        if (Ptr != null) { ffmpeg.sws_freeContext(Ptr); Ptr = null; }
    }
}

internal sealed unsafe class AVPacketHandle : IDisposable
{
    public AVPacket* Ptr;
    public AVPacketHandle(AVPacket* p) { Ptr = p; }
    public static AVPacketHandle Allocate()
    {
        var p = ffmpeg.av_packet_alloc();
        if (p == null) throw new InvalidOperationException("av_packet_alloc failed");
        return new AVPacketHandle(p);
    }
    public void Dispose()
    {
        if (Ptr != null) { var p = Ptr; ffmpeg.av_packet_free(&p); Ptr = null; }
    }
}

internal sealed unsafe class AVFrameHandle : IDisposable
{
    public AVFrame* Ptr;
    public AVFrameHandle(AVFrame* p) { Ptr = p; }
    public static AVFrameHandle Allocate()
    {
        var p = ffmpeg.av_frame_alloc();
        if (p == null) throw new InvalidOperationException("av_frame_alloc failed");
        return new AVFrameHandle(p);
    }
    public static AVFrameHandle AllocateBgra(int width, int height)
    {
        var p = ffmpeg.av_frame_alloc();
        if (p == null) throw new InvalidOperationException("av_frame_alloc failed");
        p->format = (int)AVPixelFormat.AV_PIX_FMT_BGRA;
        p->width = width;
        p->height = height;
        var ret = ffmpeg.av_frame_get_buffer(p, 32);
        if (ret < 0)
        {
            ffmpeg.av_frame_free(&p);
            throw new InvalidOperationException("av_frame_get_buffer failed");
        }
        return new AVFrameHandle(p);
    }
    public void Dispose()
    {
        if (Ptr != null) { var p = Ptr; ffmpeg.av_frame_free(&p); Ptr = null; }
    }
}
