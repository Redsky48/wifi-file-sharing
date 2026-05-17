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
    private readonly string? _bearerToken;
    private long _frameCount;

    // Recording state — protected by [_recLock] because the MJPEG loop
    // (background task) writes frames while the UI thread starts/stops.
    private readonly object _recLock = new();
    private MjpegAviWriter? _avi;
    private int _lastFrameWidth, _lastFrameHeight;

    // Remote-input state. Mouse-down stashes the start point; mouse-up
    // decides tap vs. swipe based on travel distance. RemoteInput is
    // enabled by default but degrades gracefully if the phone hasn't
    // turned on the accessibility service — we just get 503 silently.
    private System.Net.Http.HttpClient? _inputHttp;
    private Point? _dragStartPic;
    private Point? _dragLastPic;
    private bool _dragOpen;             // true between drag/start and drag/end
    private DateTime _dragStartTime;
    private DateTime _dragLastEmitTime;
    private bool _remoteInputEnabled = true;
    private const int DragThresholdPx = 8;
    // Per-segment dispatch duration on phone — short = snappy.
    private const int DragSegmentMs = 35;
    // Throttle live mouse-move emits so we don't flood the WS server
    // with hundreds of POSTs per second.
    private const int LiveDragEmitMs = 30;

    // H.264 decoder — lazy-init when the MJPEG loop detects the phone
    // switched modes. Pipes decoded BGRA Bitmaps into the same
    // PictureBox the MJPEG path uses, so the rest of the form (Record,
    // remote input, fullscreen) keeps working.
    private H264StreamDecoder? _h264Decoder;

    public ScreenStreamForm(string baseUrl, string? password, string? bearerToken = null)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _password = password;
        _bearerToken = bearerToken;

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
        FormClosing += (_, _) =>
        {
            // If a drag was open on the phone, cancel it cleanly so the
            // finger doesn't stay "down" past the viewer closing.
            if (_dragOpen)
            {
                try { _ = PostInputAsync("/api/input/drag?phase=cancel&nx=0&ny=0"); }
                catch { }
            }
            Stop();
            StopRecording(silent: true);
            _inputHttp?.Dispose();
            try { _h264Decoder?.Dispose(); } catch { }
        };

        // Remote input — mouse-on-PictureBox → POST to phone.
        _pic.MouseDown += OnPicMouseDown;
        _pic.MouseMove += OnPicMouseMove;
        _pic.MouseUp += OnPicMouseUp;
        _pic.MouseWheel += OnPicMouseWheel;
        // PictureBox doesn't get keyboard focus by default — Form's
        // KeyDown is enough for system keys.
        KeyDown += OnFormKeyDown;
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

    // ── Remote input ───────────────────────────────────────────────

    private void OnPicMouseDown(object? sender, MouseEventArgs e)
    {
        if (!_remoteInputEnabled || e.Button != MouseButtons.Left) return;
        _dragStartPic = e.Location;
        _dragLastPic = e.Location;
        _dragStartTime = DateTime.UtcNow;
        _dragLastEmitTime = DateTime.UtcNow;
        _dragOpen = false; // becomes true once we cross travel threshold
    }

    /// <summary>
    /// Live-drag pump: once the user has moved past the click/drag
    /// threshold we open a "drag session" on the phone via
    /// /api/input/drag?phase=start. Subsequent MouseMove emits (throttled)
    /// send phase=move; MouseUp sends phase=end. The phone chains the
    /// strokes via Accessibility's continueStroke — finger never lifts
    /// mid-drag, so scroll/pan feels continuous instead of stop-start.
    /// </summary>
    private void OnPicMouseMove(object? sender, MouseEventArgs e)
    {
        if (!_remoteInputEnabled) return;
        if (e.Button != MouseButtons.Left || _dragStartPic == null) return;
        var start = _dragStartPic.Value;
        var nStart = MapToNormalized(start);
        if (nStart == null) return;

        // First-time threshold cross — open the drag session at the
        // original mouse-down point. We delay this until we actually
        // see movement so plain clicks fall through to the tap path
        // on mouse-up.
        if (!_dragOpen)
        {
            var moved = (e.X - start.X) * (e.X - start.X) + (e.Y - start.Y) * (e.Y - start.Y);
            if (moved < DragThresholdPx * DragThresholdPx) return;
            _dragOpen = true;
            _ = PostInputAsync(
                $"/api/input/drag?phase=start&nx={F(nStart.Value.X)}&ny={F(nStart.Value.Y)}");
            _dragLastPic = start;
        }

        var now = DateTime.UtcNow;
        if ((now - _dragLastEmitTime).TotalMilliseconds < LiveDragEmitMs) return;
        // Skip jitter — if the mouse hasn't really moved since last
        // emit, don't bother spamming.
        if (_dragLastPic != null)
        {
            var dx = e.X - _dragLastPic.Value.X;
            var dy = e.Y - _dragLastPic.Value.Y;
            if (dx * dx + dy * dy < 4) return;
        }

        var nMove = MapToNormalized(e.Location);
        if (nMove == null) return;
        _dragLastPic = e.Location;
        _dragLastEmitTime = now;
        _ = PostInputAsync(
            $"/api/input/drag?phase=move&nx={F(nMove.Value.X)}&ny={F(nMove.Value.Y)}" +
            $"&duration={DragSegmentMs}");
    }

    private void OnPicMouseUp(object? sender, MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left || _dragStartPic == null) return;
        var start = _dragStartPic.Value;
        var end = e.Location;
        var wasDrag = _dragOpen;
        _dragStartPic = null;
        _dragLastPic = null;
        _dragOpen = false;

        var nEnd = MapToNormalized(end);
        var nStart = MapToNormalized(start);
        if (nStart == null) return;

        if (!wasDrag)
        {
            // Never crossed threshold → treat as tap at start position.
            _ = PostInputAsync(
                $"/api/input/tap?nx={F(nStart.Value.X)}&ny={F(nStart.Value.Y)}");
            return;
        }

        // Drag session is open on the phone — close it with the final
        // release point.
        if (nEnd == null) nEnd = nStart;
        _ = PostInputAsync(
            $"/api/input/drag?phase=end&nx={F(nEnd.Value.X)}&ny={F(nEnd.Value.Y)}" +
            $"&duration={DragSegmentMs}");
    }

    private void OnPicMouseWheel(object? sender, MouseEventArgs e)
    {
        if (!_remoteInputEnabled) return;
        var n = MapToNormalized(e.Location);
        if (n == null) return;
        // Wheel delta is 120 per notch. Positive (wheel up) means we
        // want content scrolled up → drag finger DOWN. As normalized
        // fraction of screen height, one notch ≈ 0.20 of height (~200 px
        // on a 1080-wide screen feels right). Server multiplies ndy by
        // real screen height.
        var ndy = -e.Delta * 0.20f / 120f;
        _ = PostInputAsync(
            $"/api/input/scroll?nx={F(n.Value.X)}&ny={F(n.Value.Y)}&ndy={F(ndy)}");
    }

    // Invariant-culture float formatting — never emit "," as the decimal
    // separator (would break the server query parser on Latvian locales).
    private static string F(double v) => v.ToString("0.######",
        System.Globalization.CultureInfo.InvariantCulture);

    private void OnFormKeyDown(object? sender, KeyEventArgs e)
    {
        // Browser-back / similar — translate select keys into phone
        // global actions. The earlier handler caught Esc / F11 / Ctrl+R
        // first; this is the fallthrough.
        if (e.Handled) return;
        string? action = e.KeyCode switch
        {
            Keys.BrowserBack => "back",
            Keys.BrowserHome => "home",
            _ => null,
        };
        if (action != null && _remoteInputEnabled)
        {
            _ = PostInputAsync($"/api/input/key?action={action}");
            e.Handled = true;
        }
    }

    /// <summary>
    /// Translates a PictureBox mouse coordinate into a 0..1 normalized
    /// (x, y) fraction of the displayed image — independent of the
    /// captured frame's pixel dimensions, which only the phone knows
    /// match against its real screen.
    /// </summary>
    private (double X, double Y)? MapToNormalized(Point picLocal)
    {
        if (_lastFrameWidth <= 0 || _lastFrameHeight <= 0) return null;
        var box = _pic.ClientSize;
        if (box.Width <= 0 || box.Height <= 0) return null;
        var scale = Math.Min(
            (double)box.Width / _lastFrameWidth,
            (double)box.Height / _lastFrameHeight);
        var drawW = _lastFrameWidth * scale;
        var drawH = _lastFrameHeight * scale;
        var offsetX = (box.Width - drawW) / 2.0;
        var offsetY = (box.Height - drawH) / 2.0;
        var relX = (picLocal.X - offsetX) / drawW;
        var relY = (picLocal.Y - offsetY) / drawH;
        if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null;
        return (relX, relY);
    }

    /// <summary>
    /// Translates a mouse coordinate inside the PictureBox into the
    /// captured frame's pixel coordinate. Kept for reference but no
    /// longer used for input — see <see cref="MapToNormalized"/>.
    /// </summary>
    private Point? MapToPhoneCoords(Point picLocal)
    {
        if (_lastFrameWidth <= 0 || _lastFrameHeight <= 0) return null;
        // PictureBox.Zoom: uniform scale, centered, letterboxed.
        var box = _pic.ClientSize;
        if (box.Width <= 0 || box.Height <= 0) return null;
        var scale = Math.Min(
            (double)box.Width / _lastFrameWidth,
            (double)box.Height / _lastFrameHeight);
        var drawW = _lastFrameWidth * scale;
        var drawH = _lastFrameHeight * scale;
        var offsetX = (box.Width - drawW) / 2.0;
        var offsetY = (box.Height - drawH) / 2.0;
        var relX = (picLocal.X - offsetX) / drawW;
        var relY = (picLocal.Y - offsetY) / drawH;
        if (relX < 0 || relX > 1 || relY < 0 || relY > 1) return null;
        return new Point(
            (int)Math.Round(relX * _lastFrameWidth),
            (int)Math.Round(relY * _lastFrameHeight));
    }

    private async System.Threading.Tasks.Task PostInputAsync(string relativeUrl)
    {
        try
        {
            if (_inputHttp == null)
            {
                _inputHttp = AuthHttp.Build(new Uri(_baseUrl), _password, TimeSpan.FromSeconds(5), _bearerToken);
            }
            using var req = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post, relativeUrl);
            using var resp = await _inputHttp.SendAsync(req);
            if ((int)resp.StatusCode == 503)
            {
                _remoteInputEnabled = false;
                UpdateStatus("Remote input off — enable in Settings → Accessibility on the phone");
            }
        }
        catch { /* best-effort */ }
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
        var client = AuthHttp.Build(new Uri(_baseUrl), _password, Timeout.InfiniteTimeSpan, _bearerToken);

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
                    // Peek at the body to figure out *why* — the phone
                    // distinguishes "cast off" from "cast running but in
                    // H.264 mode". H.264 needs the browser viewer
                    // (WebCodecs); MJPEG modes work in this WinForms loop.
                    var body = await SafeReadBody(resp);
                    if (body.Contains("\"codec\":\"h264\""))
                    {
                        SwitchToH264View();
                        return;
                    }
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

    private static async Task<string> SafeReadBody(System.Net.Http.HttpResponseMessage resp)
    {
        try { return await resp.Content.ReadAsStringAsync(); }
        catch { return ""; }
    }

    /// <summary>
    /// Phone is in H.264 mode — start the FFmpeg-based WebSocket decoder
    /// and pipe decoded BGRA bitmaps into the same PictureBox the MJPEG
    /// loop normally feeds. This means Record / mouse-input / fullscreen
    /// all keep working unchanged — only the frame source changes.
    /// </summary>
    private void SwitchToH264View()
    {
        if (!IsHandleCreated || IsDisposed) return;
        if (_h264Decoder != null) return;
        BeginInvoke(new Action(() =>
        {
            _statusLabel.Text = "Loading FFmpeg decoder…";
            // Stop the MJPEG read loop — we'll be receiving frames via
            // the WebSocket decoder from now on.
            Stop();

            _h264Decoder = new H264StreamDecoder(_baseUrl, _password, _bearerToken);
            _h264Decoder.StatusChanged += msg => UpdateStatus(msg);
            _h264Decoder.Failed += msg =>
            {
                BeginInvoke(new Action(() =>
                {
                    MessageBox.Show(this,
                        msg + "\n\nDrop ffmpeg shared DLLs into lib/ffmpeg/ — see README.txt.",
                        "H.264 decoder unavailable",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    Close();
                }));
            };
            _h264Decoder.FrameDecoded += bmp =>
            {
                _lastFrameWidth = bmp.Width;
                _lastFrameHeight = bmp.Height;
                try
                {
                    BeginInvoke(new Action(() =>
                    {
                        var old = _pic.Image;
                        _pic.Image = bmp;
                        old?.Dispose();
                    }));
                }
                catch
                {
                    bmp.Dispose();
                }
            };
            _h264Decoder.Start();
            // Recording H.264 → AVI would mean re-encoding (since AVI
            // takes MJPEG); for now we keep Record MJPEG-only. The
            // RawAccessUnit event is exposed in the decoder for a
            // future MP4-mux recorder.
            _recordBtn.Enabled = false;
        }));
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
