using System;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace WiFiShareTray;

/// <summary>
/// WinForms window that displays the phone's MJPEG screen-cast stream
/// directly — bypasses the browser entirely so the PIN doesn't need to
/// be re-entered (we already hold it for the tray's mount/queue).
///
/// Parses multipart/x-mixed-replace by hand instead of relying on a
/// WebView2 control — that would add a 100MB+ dependency for one
/// PictureBox.
/// </summary>
public sealed class ScreenStreamForm : Form
{
    private readonly PictureBox _pic;
    private readonly Label _statusLabel;
    private CancellationTokenSource? _cts;
    private readonly string _baseUrl;
    private readonly string? _password;
    private long _frameCount;

    public ScreenStreamForm(string baseUrl, string? password)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _password = password;

        Text = "Phone screen — WiFi Share";
        Size = new Size(960, 540);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.Black;
        Icon = LoadAppIcon();
        KeyPreview = true;

        _pic = new PictureBox
        {
            Dock = DockStyle.Fill,
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.Black,
        };
        _statusLabel = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 22,
            TextAlign = ContentAlignment.MiddleLeft,
            Padding = new Padding(10, 0, 10, 0),
            ForeColor = Color.Gainsboro,
            BackColor = Color.FromArgb(20, 20, 24),
            Font = new Font("Segoe UI", 9f),
            Text = "Connecting…",
        };
        Controls.Add(_pic);
        Controls.Add(_statusLabel);

        KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Escape) Close();
            else if (e.KeyCode == Keys.F11) ToggleFullscreen();
        };
        _pic.DoubleClick += (_, _) => ToggleFullscreen();
        FormClosing += (_, _) => Stop();
    }

    private FormBorderStyle _prevBorder;
    private FormWindowState _prevState;
    private bool _fullscreen;
    private void ToggleFullscreen()
    {
        if (!_fullscreen)
        {
            _prevBorder = FormBorderStyle;
            _prevState = WindowState;
            FormBorderStyle = FormBorderStyle.None;
            WindowState = FormWindowState.Maximized;
            _statusLabel.Visible = false;
            _fullscreen = true;
        }
        else
        {
            FormBorderStyle = _prevBorder;
            WindowState = _prevState;
            _statusLabel.Visible = true;
            _fullscreen = false;
        }
    }

    private static Icon? LoadAppIcon()
    {
        try
        {
            var exe = System.Reflection.Assembly.GetExecutingAssembly().Location;
            return string.IsNullOrEmpty(exe) ? null : Icon.ExtractAssociatedIcon(exe);
        }
        catch { return null; }
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        Start();
    }

    private void Start()
    {
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    private void Stop()
    {
        try { _cts?.Cancel(); } catch { }
    }

    private async Task Loop(CancellationToken ct)
    {
        // Reuse the same auth-aware HttpClient builder that QueuePoller
        // uses, so credential / TLS quirks stay consistent across the app.
        var client = AuthHttp.Build(new Uri(_baseUrl), _password, Timeout.InfiniteTimeSpan);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var resp = await client.GetAsync(
                    "/api/screen",
                    HttpCompletionOption.ResponseHeadersRead,
                    ct);

                if (resp.StatusCode == HttpStatusCode.Unauthorized)
                {
                    UpdateStatus("Wrong PIN — reconnect from the tray menu first");
                    return;
                }
                if ((int)resp.StatusCode == 503)
                {
                    // Phone hasn't started cast yet (or just stopped) —
                    // wait and try again instead of giving up.
                    UpdateStatus("Cast is off on the phone — waiting…");
                    try { await Task.Delay(2000, ct); } catch { return; }
                    continue;
                }
                if (!resp.IsSuccessStatusCode)
                {
                    UpdateStatus($"HTTP {(int)resp.StatusCode} — retrying");
                    try { await Task.Delay(2000, ct); } catch { return; }
                    continue;
                }

                var contentType = resp.Content.Headers.ContentType?.ToString() ?? "";
                var boundary = ExtractBoundary(contentType);
                if (boundary == null)
                {
                    UpdateStatus("Invalid MJPEG header from phone");
                    return;
                }

                await using var stream = await resp.Content.ReadAsStreamAsync(ct);
                await ReadMjpegLoop(stream, boundary, ct);
            }
            catch (OperationCanceledException) { return; }
            catch (Exception ex)
            {
                UpdateStatus($"Disconnected: {ex.Message}");
                try { await Task.Delay(2000, ct); } catch { return; }
            }
        }
    }

    /// <summary>
    /// Pulls the multipart boundary string out of the Content-Type header,
    /// returning it with the leading "--" already prepended so callers can
    /// match raw bytes directly.
    /// </summary>
    private static string? ExtractBoundary(string contentType)
    {
        const string key = "boundary=";
        var idx = contentType.IndexOf(key, StringComparison.OrdinalIgnoreCase);
        if (idx < 0) return null;
        var rest = contentType.Substring(idx + key.Length).Trim().Trim('"');
        var sep = rest.IndexOf(';');
        if (sep >= 0) rest = rest.Substring(0, sep);
        return "--" + rest.Trim();
    }

    private async Task ReadMjpegLoop(Stream stream, string boundary, CancellationToken ct)
    {
        var br = new BufferedNetReader(stream);
        long framesThisSecond = 0;
        var lastFpsTick = DateTime.UtcNow;
        long? bytesPerFrame = null;

        while (!ct.IsCancellationRequested)
        {
            var line = await br.ReadLineAsync(ct);
            if (line == null) return;
            // Tolerate extra CRLFs / non-boundary lines until we hit the boundary
            if (!line.StartsWith(boundary)) continue;

            int contentLength = -1;
            while (true)
            {
                line = await br.ReadLineAsync(ct);
                if (line == null) return;
                if (line.Length == 0) break;
                var ci = line.IndexOf(':');
                if (ci > 0 && line.Substring(0, ci).Equals(
                        "Content-Length", StringComparison.OrdinalIgnoreCase))
                {
                    int.TryParse(line.Substring(ci + 1).Trim(), out contentLength);
                }
            }
            if (contentLength <= 0) continue;

            var jpeg = await br.ReadExactlyAsync(contentLength, ct);
            if (jpeg == null) return;
            bytesPerFrame = jpeg.Length;
            DisplayFrame(jpeg);
            _frameCount++;
            framesThisSecond++;

            var now = DateTime.UtcNow;
            var elapsed = (now - lastFpsTick).TotalSeconds;
            if (elapsed >= 1)
            {
                var fps = framesThisSecond / elapsed;
                UpdateStatus(
                    $"Live · {fps:0.0} fps · {(bytesPerFrame ?? 0) / 1024} KB/frame · {_frameCount} total");
                framesThisSecond = 0;
                lastFpsTick = now;
            }
        }
    }

    private void DisplayFrame(byte[] jpeg)
    {
        Image? newImg;
        try
        {
            using var ms = new MemoryStream(jpeg);
            newImg = Image.FromStream(ms);
        }
        catch
        {
            return; // skip corrupt frame
        }
        try
        {
            BeginInvoke(new Action(() =>
            {
                var old = _pic.Image;
                _pic.Image = newImg;
                old?.Dispose();
            }));
        }
        catch (InvalidOperationException)
        {
            // Form was closed while a frame was in flight — drop it.
            newImg.Dispose();
        }
    }

    private void UpdateStatus(string text)
    {
        try { BeginInvoke(new Action(() => _statusLabel.Text = text)); } catch { }
    }
}

/// <summary>
/// Tiny line/length reader over a raw network stream. Buffers ahead so
/// we don't make a syscall per byte while parsing MJPEG part headers.
/// </summary>
internal sealed class BufferedNetReader
{
    private readonly Stream _stream;
    private readonly byte[] _buf = new byte[128 * 1024];
    private int _start;
    private int _end;

    public BufferedNetReader(Stream s) { _stream = s; }

    public async Task<string?> ReadLineAsync(CancellationToken ct)
    {
        var sb = new StringBuilder(64);
        while (true)
        {
            if (_start >= _end)
            {
                _start = 0;
                _end = await _stream.ReadAsync(_buf, 0, _buf.Length, ct);
                if (_end == 0) return sb.Length == 0 ? null : sb.ToString();
            }
            byte b = _buf[_start++];
            if (b == (byte)'\n')
            {
                var s = sb.ToString();
                if (s.Length > 0 && s[^1] == '\r') s = s.Substring(0, s.Length - 1);
                return s;
            }
            sb.Append((char)b);
        }
    }

    public async Task<byte[]?> ReadExactlyAsync(int n, CancellationToken ct)
    {
        var result = new byte[n];
        int written = 0;
        while (written < n)
        {
            int avail = _end - _start;
            if (avail > 0)
            {
                int take = Math.Min(avail, n - written);
                Buffer.BlockCopy(_buf, _start, result, written, take);
                _start += take;
                written += take;
                continue;
            }
            _start = 0;
            _end = await _stream.ReadAsync(_buf, 0, _buf.Length, ct);
            if (_end == 0) return null;
        }
        return result;
    }
}
