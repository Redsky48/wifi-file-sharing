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
    private readonly ToolStrip _toolbar;
    private readonly ToolStripButton _recordBtn;
    private readonly ToolStripLabel _recIndicator;
    private readonly System.Windows.Forms.Timer _recTimer;
    private CancellationTokenSource? _cts;
    private readonly string _baseUrl;
    private readonly string? _password;
    private long _frameCount;

    // Recording state — protected by [_recLock] because the MJPEG loop
    // (background task) writes frames while the UI thread starts/stops.
    private readonly object _recLock = new();
    private MjpegAviWriter? _avi;
    private int _lastFrameWidth, _lastFrameHeight;

    public ScreenStreamForm(string baseUrl, string? password)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _password = password;

        Text = "Phone screen — WiFi Share";
        Size = new Size(960, 580);
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

        _recordBtn = new ToolStripButton("● Record")
        {
            ForeColor = Color.Tomato,
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            DisplayStyle = ToolStripItemDisplayStyle.Text,
        };
        _recordBtn.Click += (_, _) => ToggleRecording();
        _recIndicator = new ToolStripLabel("")
        {
            ForeColor = Color.Tomato,
            Font = new Font("Segoe UI", 9f),
        };
        var saveAsBtn = new ToolStripButton("Save current frame…")
        {
            DisplayStyle = ToolStripItemDisplayStyle.Text,
            ForeColor = Color.Gainsboro,
        };
        saveAsBtn.Click += (_, _) => SaveCurrentFrame();
        _toolbar = new ToolStrip
        {
            Dock = DockStyle.Top,
            BackColor = Color.FromArgb(20, 20, 24),
            ForeColor = Color.Gainsboro,
            GripStyle = ToolStripGripStyle.Hidden,
            RenderMode = ToolStripRenderMode.System,
            Padding = new Padding(6, 2, 6, 2),
        };
        _toolbar.Items.AddRange(new ToolStripItem[]
        {
            _recordBtn,
            _recIndicator,
            new ToolStripSeparator(),
            saveAsBtn,
        });

        Controls.Add(_pic);
        Controls.Add(_toolbar);
        Controls.Add(_statusLabel);

        _recTimer = new System.Windows.Forms.Timer { Interval = 500 };
        _recTimer.Tick += (_, _) => UpdateRecIndicator();

        KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Escape) Close();
            else if (e.KeyCode == Keys.F11) ToggleFullscreen();
            else if (e.KeyCode == Keys.R && e.Control) ToggleRecording();
        };
        _pic.DoubleClick += (_, _) => ToggleFullscreen();
        FormClosing += (_, _) => { Stop(); StopRecording(silent: true); };
    }

    private void ToggleRecording()
    {
        lock (_recLock)
        {
            if (_avi != null) { StopRecording(silent: false); return; }
        }
        if (_lastFrameWidth == 0 || _lastFrameHeight == 0)
        {
            MessageBox.Show(this,
                "Wait for the first frame to arrive before starting a recording.",
                "Phone screen", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        var defaultDir = Environment.GetFolderPath(Environment.SpecialFolder.MyVideos);
        if (string.IsNullOrEmpty(defaultDir) || !Directory.Exists(defaultDir))
            defaultDir = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
        var defaultName = $"wifishare-screen-{DateTime.Now:yyyyMMdd-HHmmss}.avi";
        using var dlg = new SaveFileDialog
        {
            Title = "Save screen recording",
            Filter = "AVI video (*.avi)|*.avi",
            FileName = defaultName,
            InitialDirectory = defaultDir,
            OverwritePrompt = true,
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            lock (_recLock)
            {
                _avi = new MjpegAviWriter(dlg.FileName, _lastFrameWidth, _lastFrameHeight);
            }
            _recordBtn.Text = "■ Stop recording";
            _recTimer.Start();
            UpdateRecIndicator();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Failed to start recording: " + ex.Message,
                "Phone screen", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void StopRecording(bool silent, string? prefixMessage = null)
    {
        MjpegAviWriter? toClose = null;
        lock (_recLock)
        {
            toClose = _avi;
            _avi = null;
        }
        if (toClose == null) return;
        var path = toClose.Path;
        var frames = toClose.FrameCount;
        var elapsed = toClose.Elapsed;
        try { toClose.Dispose(); } catch { }
        _recTimer.Stop();
        if (IsHandleCreated && !IsDisposed)
        {
            BeginInvoke(new Action(() =>
            {
                _recordBtn.Text = "● Record";
                _recIndicator.Text = "";
            }));
        }
        if (!silent && File.Exists(path))
        {
            var size = new FileInfo(path).Length;
            // Default action: open the saved file in Explorer (select it).
            BeginInvoke(new Action(() =>
            {
                var header = prefixMessage == null ? "" : prefixMessage + "\n\n";
                var openIt = MessageBox.Show(this,
                    $"{header}Saved {frames} frames over {elapsed.TotalSeconds:0.0}s ({size / 1024 / 1024} MB)\n\n{path}\n\nOpen in Explorer?",
                    prefixMessage != null ? "Screen sharing stopped — recording saved" : "Recording saved",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Information);
                if (openIt == DialogResult.Yes)
                {
                    try
                    {
                        System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\"");
                    }
                    catch { }
                }
            }));
        }
    }

    /// <summary>
    /// Called from the tray when the phone-side cast is stopped (either
    /// the user pressed Stop in the WiFi Share app, or revoked it from
    /// Android's system panel). Behavior:
    ///   - if we were recording: auto-finalize the AVI, show a notice,
    ///     and leave the window open so the user can see what happened
    ///     and click through to the saved file.
    ///   - if we weren't recording: close the window — there's nothing
    ///     to show, and the auto-reconnect loop would just spin forever.
    /// </summary>
    public void NotifyRemoteStopped()
    {
        if (!IsHandleCreated || IsDisposed) return;
        BeginInvoke(new Action(() =>
        {
            MjpegAviWriter? hadRecording;
            lock (_recLock) hadRecording = _avi;

            // Stop the stream loop either way — the /api/screen endpoint
            // is returning 503 now, no point retrying until the user
            // starts cast again.
            Stop();

            if (hadRecording != null)
            {
                _statusLabel.Text = "Screen sharing stopped — saving recording…";
                StopRecording(silent: false,
                    prefixMessage: "The phone stopped sharing its screen.");
                // Leave the window open so the user sees the save dialog
                // and can choose to keep it or close it manually.
            }
            else
            {
                // Quiet path — just close.
                Close();
            }
        }));
    }

    private void UpdateRecIndicator()
    {
        MjpegAviWriter? a;
        lock (_recLock) a = _avi;
        if (a == null) { _recIndicator.Text = ""; return; }
        var e = a.Elapsed;
        _recIndicator.Text = $"REC {e:mm\\:ss} · {a.FrameCount} frames";
    }

    private void SaveCurrentFrame()
    {
        Image? img = _pic.Image;
        if (img == null)
        {
            MessageBox.Show(this, "No frame yet.", "Phone screen",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var dlg = new SaveFileDialog
        {
            Title = "Save current frame",
            Filter = "JPEG image (*.jpg)|*.jpg|PNG image (*.png)|*.png",
            FileName = $"wifishare-frame-{DateTime.Now:yyyyMMdd-HHmmss}.jpg",
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var fmt = dlg.FilterIndex == 2
                ? System.Drawing.Imaging.ImageFormat.Png
                : System.Drawing.Imaging.ImageFormat.Jpeg;
            img.Save(dlg.FileName, fmt);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Save failed: " + ex.Message, "Phone screen",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
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
            // Mirror the frame into the AVI muxer if recording is on.
            // Lock-free fast path: read the field, only enter lock if set.
            if (_avi != null)
            {
                lock (_recLock)
                {
                    _avi?.WriteFrame(jpeg);
                }
            }
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
        _lastFrameWidth = newImg.Width;
        _lastFrameHeight = newImg.Height;
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
