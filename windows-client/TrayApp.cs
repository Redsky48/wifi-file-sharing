using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;

namespace WiFiShareTray;

internal enum AppState { Scanning, Connecting, Connected, Disconnected }

public sealed class TrayApp : IDisposable
{
    private readonly NotifyIcon _tray;
    private readonly ContextMenuStrip _menu;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _backendItem;
    private readonly ToolStripMenuItem _pendingMenu;
    private readonly ToolStripSeparator _pendingSeparator;
    private readonly ToolStripMenuItem _devicesHeader;
    private readonly ToolStripSeparator _devicesSeparator;
    private readonly ToolStripMenuItem _openItem;
    private readonly ToolStripMenuItem _openWebItem;
    private readonly ToolStripMenuItem _disconnectItem;
    private readonly ToolStripMenuItem _autoConnectItem;
    private readonly ToolStripMenuItem _autoStartItem;
    private readonly ToolStripMenuItem _installItem;
    private readonly ToolStripMenuItem _uninstallItem;
    private readonly ToolStripMenuItem _installWinFspItem;
    private readonly ToolStripMenuItem _exitItem;
    private readonly ToolStripMenuItem _apiDocsItem;
    private readonly ToolStripMenuItem _viewScreenItem;
    private readonly ToolStripMenuItem _clipboardMenu;
    private readonly ToolStripMenuItem _clipboardSyncToggle;
    private readonly ToolStripMenuItem _pushClipboardItem;
    private readonly ToolStripMenuItem _pullClipboardItem;
    private string? _lastPhoneClipboard;
    private string? _lastMirroredClipboard;
    private readonly ToolStripMenuItem _castToTvMenu;

    private readonly Settings _settings = Settings.Load();
    private readonly MdnsBrowser _browser;
    private readonly Mounter _mounter = new();
    private readonly HealthChecker _health = new();
    private readonly QueuePoller _queue;
    private readonly IpcServer _ipc = new();
    private readonly PendingUploads _pending = new();
    private readonly LocalApiServer _localApi;
    private readonly PhoneEventSubscriber _phoneEvents;
    private readonly SsdpBrowser _ssdp = new();
    private readonly CastMediaServer _castServer = new();
    private readonly ClipboardWatcher _clipboardWatcher = new();
    private bool _phoneCasting;
    private readonly SynchronizationContext _ui;
    private readonly ToolStripMenuItem _settingsItem;
    private readonly ToolStripMenuItem _sendToItem;
    private readonly ToolStripMenuItem _shareTargetItem;

    private AppState _state = AppState.Scanning;
    private DiscoveredService? _connectedTo;
    private string? _connectedPassword; // held in-memory only; persisted DPAPI-encrypted in Settings
    private string? _connectedToken;    // Bearer token, preferred over PIN once paired
    private bool _autoConnect;
    private ScreenStreamForm? _screenForm;

    public TrayApp()
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _autoConnect = _settings.AutoConnect;
        _queue = new QueuePoller(_settings);
        _queue.FileReceived += OnFileReceived;
        _phoneEvents = new PhoneEventSubscriber(_settings);
        _phoneEvents.EventReceived += ev => Post(() => OnPhoneEvent(ev));

        _statusItem = new ToolStripMenuItem("Scanning…") { Enabled = false };
        _backendItem = new ToolStripMenuItem(BackendStatusText()) { Enabled = false };
        _pendingMenu = new ToolStripMenuItem("Pending: 0") { Visible = false };
        _pendingSeparator = new ToolStripSeparator { Visible = false };
        _devicesHeader = new ToolStripMenuItem("Devices") { Enabled = false };
        _devicesSeparator = new ToolStripSeparator();
        _openItem = new ToolStripMenuItem("Open in Explorer", null, (_, _) => _mounter.OpenInExplorer())
        {
            Enabled = false,
        };
        _openWebItem = new ToolStripMenuItem("Open phone's web page", null, (_, _) => OpenWebPage())
        {
            Enabled = false,
        };
        _disconnectItem = new ToolStripMenuItem("Disconnect", null, async (_, _) => await DisconnectAsync())
        {
            Enabled = false,
        };
        _autoConnectItem = new ToolStripMenuItem("Auto-connect when found")
        {
            Checked = _autoConnect,
            CheckOnClick = true,
        };
        _autoConnectItem.CheckedChanged += (_, _) =>
        {
            _autoConnect = _autoConnectItem.Checked;
            _settings.AutoConnect = _autoConnect;
            _settings.Save();
        };
        _settingsItem = new ToolStripMenuItem("Settings…", null, (_, _) => OpenSettings());

        _sendToItem = new ToolStripMenuItem("Send To: WiFi Share")
        {
            CheckOnClick = true,
            Checked = SendToShortcut.IsRegistered,
        };
        _sendToItem.CheckedChanged += (_, _) =>
        {
            try
            {
                if (_sendToItem.Checked) SendToShortcut.Register();
                else SendToShortcut.Unregister();
            }
            catch (Exception ex)
            {
                ShowBalloon("Send To toggle failed", ex.Message);
                // Reflect the actual on-disk state
                _sendToItem.Checked = SendToShortcut.IsRegistered;
            }
        };

        _shareTargetItem = new ToolStripMenuItem("Show in Windows Share (Win+H)…")
        {
            CheckOnClick = false,
        };
        _shareTargetItem.Click += (_, _) => ToggleShareTarget();
        // Async probe — Get-AppxPackage takes a second, don't block startup.
        _ = System.Threading.Tasks.Task.Run(() =>
        {
            var registered = WindowsShareTarget.IsRegistered;
            Post(() => _shareTargetItem.Text = registered
                ? "Remove from Windows Share"
                : "Show in Windows Share (Win+H)…");
        });

        _installItem = new ToolStripMenuItem("Install on this PC", null, (_, _) => DoInstall())
        {
            Visible = !Installer.IsInstalled,
        };
        _autoStartItem = new ToolStripMenuItem("Start with Windows")
        {
            CheckOnClick = true,
            Checked = Installer.IsAutoStartEnabled,
        };
        _autoStartItem.CheckedChanged += (_, _) => Installer.SetAutoStart(_autoStartItem.Checked);
        _uninstallItem = new ToolStripMenuItem("Uninstall…", null, (_, _) => DoUninstall())
        {
            Visible = Installer.IsInstalled,
        };

        _installWinFspItem = new ToolStripMenuItem(
            "Install WinFSP for better speed…",
            null,
            (_, _) => OpenWinFspDownload())
        {
            Visible = !WinFspManager.IsInstalled(),
        };
        _exitItem = new ToolStripMenuItem("Quit", null, (_, _) => Application.Exit());

        _apiDocsItem = new ToolStripMenuItem(
            "Local API docs (for agents)…",
            null,
            (_, _) => OpenLocalApiDocs());

        _viewScreenItem = new ToolStripMenuItem(
            "View phone screen…",
            null,
            (_, _) => OpenPhoneScreenViewer())
        {
            Visible = false, // shown only while phone is casting
        };

        _clipboardSyncToggle = new ToolStripMenuItem("Auto-mirror phone → PC")
        {
            CheckOnClick = true,
            Checked = _settings.ClipboardAutoMirror,
        };
        _clipboardSyncToggle.CheckedChanged += (_, _) =>
        {
            _settings.ClipboardAutoMirror = _clipboardSyncToggle.Checked;
            _settings.Save();
        };
        _pushClipboardItem = new ToolStripMenuItem(
            "Push PC clipboard → phone",
            null,
            (_, _) => PushClipboardToPhone());
        _pullClipboardItem = new ToolStripMenuItem(
            "Pull phone clipboard → PC",
            null,
            async (_, _) => await PullClipboardFromPhoneAsync());
        _clipboardMenu = new ToolStripMenuItem("Clipboard");
        _clipboardMenu.DropDownItems.AddRange(new ToolStripItem[]
        {
            _clipboardSyncToggle,
            new ToolStripSeparator(),
            _pushClipboardItem,
            _pullClipboardItem,
        });

        _castToTvMenu = new ToolStripMenuItem("Cast to TV")
        {
            Visible = false, // shown only when at least one DLNA renderer is discovered
        };

        _menu = new ContextMenuStrip();
        _menu.Items.AddRange(new ToolStripItem[]
        {
            _statusItem,
            _backendItem,
            _pendingMenu,
            _pendingSeparator,
            new ToolStripSeparator(),
            _devicesHeader,
            _devicesSeparator,
            _openItem,
            _openWebItem,
            _viewScreenItem,
            _clipboardMenu,
            _castToTvMenu,
            _disconnectItem,
            new ToolStripSeparator(),
            _autoConnectItem,
            _autoStartItem,
            _sendToItem,
            _shareTargetItem,
            _settingsItem,
            _apiDocsItem,
            new ToolStripSeparator(),
            _installItem,
            _uninstallItem,
            _installWinFspItem,
            _exitItem,
        });

        _tray = new NotifyIcon
        {
            Icon = BuildIcon(Color.Gray),
            Visible = true,
            Text = "WiFi Share — scanning…",
            ContextMenuStrip = _menu,
        };
        _tray.DoubleClick += (_, _) => _mounter.OpenInExplorer();

        // Clicking a tray notification runs whatever action the most-recent
        // ShowBalloon() registered. Cleared on click + on close so a stale
        // action doesn't fire after a different balloon shows up.
        _tray.BalloonTipClicked += (_, _) =>
        {
            var action = _balloonClickAction;
            _balloonClickAction = null;
            try { action?.Invoke(); } catch { /* ignore */ }
        };
        _tray.BalloonTipClosed += (_, _) => _balloonClickAction = null;

        _browser = new MdnsBrowser();
        _browser.Found += OnDeviceFound;
        _browser.Lost += OnDeviceLost;
        _browser.Found += d => LocalEvents.Push("device.found", new
        {
            name = d.Name,
            address = d.Address.ToString(),
            port = (int)d.Port,
            url = d.Url,
            authRequired = d.AuthRequired,
        });
        _browser.Lost += name => LocalEvents.Push("device.lost", new { name });
        _browser.Start();

        _health.Lost += OnPhoneVanished;

        // IPC: secondary launches (Send To) hand off their file paths
        // here so we can upload them via the connection this tray owns.
        _ipc.PathsReceived += paths => Post(() => QueueUploads(paths));
        _ipc.Start();

        _pending.Changed += () => Post(RebuildPendingMenu);
        _pending.Changed += () => LocalEvents.Push("pending.changed", new
        {
            count = _pending.Count,
            items = _pending.Snapshot(),
        });
        RebuildPendingMenu();

        // Loopback-only HTTP API so AI agents / scripts on this PC can
        // introspect tray state. URL is written to %LOCALAPPDATA%\WiFiShare\
        // local-api.json for stable discovery.
        _localApi = new LocalApiServer(
            getStatus: BuildStatusSnapshot,
            browser: _browser,
            pending: _pending,
            settings: _settings);
        _localApi.Start();
        if (_localApi.StartupError != null)
        {
            // Show once at startup so the user knows the API surface for
            // AI agents is unavailable — but don't block the tray itself.
            Post(() => ShowBalloon("Local API unavailable", _localApi.StartupError!));
        }

        // SSDP browsing + local media host for DLNA "Cast to TV". Both
        // are passive — no extra cost until the user discovers a TV and
        // clicks the cast item.
        _ssdp.Found += r => Post(() => OnDlnaRendererFound(r));
        _ssdp.Start();
        _castServer.Start();

        // PC → phone clipboard sync. Watcher fires for every clipboard
        // change (any app). We only push if AutoMirror is enabled and a
        // phone is currently paired. _lastMirroredClipboard double-duty
        // prevents the loop where: phone pushes → we paste into Windows
        // clipboard → watcher fires → we'd push it right back.
        _clipboardWatcher.ClipboardChanged += () => Post(OnPcClipboardChanged);

        Application.ApplicationExit += (_, _) => Cleanup();
    }

    private StatusSnapshot BuildStatusSnapshot()
    {
        var version = System.Reflection.Assembly.GetExecutingAssembly()
            .GetName().Version?.ToString() ?? "0";
        return new StatusSnapshot(
            State: _state.ToString(),
            ConnectedToName: _connectedTo?.Name,
            ConnectedToUrl: _connectedTo?.Url,
            ConnectedToPort: _connectedTo?.Port ?? 0,
            ConnectedToAuthRequired: _connectedTo?.AuthRequired ?? false,
            AutoConnect: _autoConnect,
            MountedDrive: _mounter.CurrentDrive,
            WinFspInstalled: WinFspManager.IsInstalled(),
            DeviceName: _settings.DeviceName,
            Version: version,
            PhoneIsCasting: _phoneCasting,
            PhoneScreenViewerUrl: PhoneScreenViewerUrl());
    }

    private void RebuildPendingMenu()
    {
        var items = _pending.Snapshot();
        _pendingMenu.DropDownItems.Clear();
        if (items.Count == 0)
        {
            _pendingMenu.Visible = false;
            _pendingSeparator.Visible = false;
            return;
        }

        _pendingMenu.Visible = true;
        _pendingSeparator.Visible = true;
        _pendingMenu.Text = $"Pending: {items.Count}";

        // Group by target device for clarity
        foreach (var group in items.GroupBy(p => p.TargetDisplayName))
        {
            var header = new ToolStripMenuItem($"→ {group.Key} ({group.Count()})")
            {
                Enabled = false,
            };
            _pendingMenu.DropDownItems.Add(header);

            foreach (var p in group)
            {
                var name = Path.GetFileName(p.FilePath);
                var sub = new ToolStripMenuItem(name);
                if (!string.IsNullOrEmpty(p.LastError))
                    sub.ToolTipText = p.LastError;
                var cancelItem = new ToolStripMenuItem(
                    "Cancel", null, (_, _) => _pending.Remove(p.Id));
                var openLocationItem = new ToolStripMenuItem(
                    "Show in Explorer", null, (_, _) =>
                    {
                        try
                        {
                            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                            {
                                FileName = "explorer.exe",
                                Arguments = "/select,\"" + p.FilePath + "\"",
                                UseShellExecute = true,
                            });
                        }
                        catch { /* ignore */ }
                    });
                sub.DropDownItems.Add(openLocationItem);
                sub.DropDownItems.Add(cancelItem);
                _pendingMenu.DropDownItems.Add(sub);
            }
        }

        _pendingMenu.DropDownItems.Add(new ToolStripSeparator());
        _pendingMenu.DropDownItems.Add(new ToolStripMenuItem(
            "Cancel all", null, (_, _) => _pending.Clear()));
    }

    /// <summary>
    /// Upload one or more local files. If multiple phones are visible the
    /// user is asked which one; if only one is found we send there
    /// directly. The chosen phone's saved PIN is used (or the user is
    /// prompted, exactly like the mount flow).
    /// </summary>
    public void QueueUploads(string[] paths)
    {
        if (paths.Length == 0) return;

        var devices = _browser.Snapshot().ToList();

        // No phone visible right now → try to enqueue against a previously
        // connected one so the user doesn't lose the file. mDNS Found
        // event will flush the queue when the phone comes back.
        if (devices.Count == 0)
        {
            var knownName = _settings.KnownDevices.Keys.FirstOrDefault();
            if (knownName == null)
            {
                ShowBalloon("No phones configured",
                    "Connect to a phone in the app first, then try again.");
                return;
            }
            EnqueueForLater(knownName, TrimName(knownName), paths, "Phone offline");
            return;
        }

        DiscoveredService? target;
        if (devices.Count == 1)
        {
            target = devices[0];
        }
        else
        {
            using var dlg = new SendTargetDialog(devices, _connectedTo, paths);
            if (dlg.ShowDialog() != DialogResult.OK) return;
            target = dlg.SelectedDevice;
            if (target == null) return;
        }

        SendToTargetAsync(target, paths);
    }

    private void EnqueueForLater(string deviceName, string displayName, string[] paths, string reason)
    {
        foreach (var p in paths.Where(File.Exists))
        {
            _pending.Add(deviceName, displayName, p, reason);
        }
        ShowBalloon($"Queued for {displayName}",
            $"{paths.Length} file(s). Will send when phone is online.",
            onClick: () => _menu.Show(System.Windows.Forms.Cursor.Position));
    }

    private void SendToTargetAsync(DiscoveredService target, string[] paths)
    {
        var deviceLabel = TrimName(target.Name);
        var password = _settings.GetPassword(target.Name);

        // If the phone requires PIN and we don't have one saved → ask.
        if (target.AuthRequired && string.IsNullOrEmpty(password))
        {
            var entered = AskPassword(deviceLabel, wrongPassword: false);
            if (entered == null) return;
            password = entered;
            _settings.SavePassword(target.Name, password);
        }

        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            int ok = 0;
            var failedPaths = new List<(string Path, string? Error)>();
            bool authFailed = false;
            foreach (var path in paths)
            {
                var outcome = await Uploader.UploadAsync(target.Url, password, path);
                if (outcome.Success) ok++;
                else
                {
                    failedPaths.Add((path, outcome.Error));
                    if (outcome.Error != null && outcome.Error.Contains("401"))
                        authFailed = true;
                }
            }
            Post(() =>
            {
                if (ok > 0)
                {
                    ShowBalloon(
                        $"Sent to {deviceLabel}",
                        ok == 1 ? Path.GetFileName(paths[0]) : $"{ok} file(s) sent",
                        onClick: _mounter.CurrentDrive != null ? _mounter.OpenInExplorer : null);
                }

                if (authFailed && failedPaths.Count > 0)
                {
                    // PIN rejected — prompt once, retry the failed batch.
                    var fresh = AskPassword(deviceLabel, wrongPassword: true);
                    if (fresh != null)
                    {
                        _settings.SavePassword(target.Name, fresh);
                        SendToTargetAsync(target, failedPaths.Select(f => f.Path).ToArray());
                        return;
                    }
                    // User cancelled the prompt — queue for later
                }

                if (failedPaths.Count > 0)
                {
                    // Network failure or rejected auth → put the leftovers
                    // into the pending queue. mDNS Found / next manual
                    // attempt will retry them.
                    foreach (var (path, err) in failedPaths)
                    {
                        _pending.Add(target.Name, deviceLabel, path, err);
                    }
                    ShowBalloon(
                        $"{failedPaths.Count} file(s) pending",
                        $"Will retry when {deviceLabel} is online.");
                }
            });
        });
    }

    private void OnDeviceFound(DiscoveredService svc)
    {
        Post(() =>
        {
            RebuildDeviceMenu();
            if (_state == AppState.Scanning && _autoConnect)
            {
                _ = ConnectAsync(svc);
            }

            // Flush any pending uploads queued for this device.
            var pending = _pending.SnapshotForDevice(svc.Name);
            if (pending.Count > 0)
            {
                _ = FlushPendingForDeviceAsync(svc, pending);
            }
        });
    }

    private async System.Threading.Tasks.Task FlushPendingForDeviceAsync(
        DiscoveredService device,
        List<PendingUpload> pending)
    {
        var password = _settings.GetPassword(device.Name);
        var deviceLabel = TrimName(device.Name);
        int sent = 0;

        foreach (var item in pending)
        {
            if (!File.Exists(item.FilePath))
            {
                _pending.Remove(item.Id);  // source vanished
                continue;
            }
            var outcome = await Uploader.UploadAsync(device.Url, password, item.FilePath);
            if (outcome.Success)
            {
                _pending.Remove(item.Id);
                sent++;
            }
            else
            {
                _pending.RecordFailure(item.Id, outcome.Error);
                if (outcome.Error != null && outcome.Error.Contains("401")) break; // PIN bad
            }
        }

        if (sent > 0)
        {
            Post(() => ShowBalloon(
                $"Pending → {deviceLabel}",
                $"Sent {sent} queued file(s).",
                onClick: _mounter.CurrentDrive != null ? _mounter.OpenInExplorer : null));
        }
    }

    private void OnDeviceLost(string name)
    {
        Post(() =>
        {
            RebuildDeviceMenu();
            // mDNS goodbye for the device we're connected to — phone is
            // shutting down. Disconnect immediately rather than waiting
            // for HealthChecker's miss-count to expire.
            if (_state == AppState.Connected &&
                _connectedTo != null &&
                string.Equals(_connectedTo.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                ShowBalloon("Phone went offline", "Drive unmounted.");
                _ = OnPhoneGoingOfflineAsync();
            }
        });
    }

    private async System.Threading.Tasks.Task OnPhoneGoingOfflineAsync()
    {
        await DisconnectAsync(silent: true);
        _state = AppState.Scanning;
        UpdateUi();
    }

    private void OnPhoneVanished()
    {
        Post(async () =>
        {
            ShowBalloon("Phone disconnected", "Drive unmounted.");
            await DisconnectAsync(silent: true);
            _state = AppState.Scanning;
            UpdateUi();
        });
    }

    private async System.Threading.Tasks.Task ConnectAsync(DiscoveredService svc)
    {
        _state = AppState.Connecting;
        _connectedTo = svc;
        UpdateUi();

        var label = TrimName(svc.Name);
        var deviceKey = svc.Name; // stable mDNS instance name
        var savedPassword = _settings.GetPassword(deviceKey);

        // 1. If phone requires auth and we have no saved password — prompt
        if (svc.AuthRequired && string.IsNullOrEmpty(savedPassword))
        {
            var entered = AskPassword(label, wrongPassword: false);
            if (entered == null)
            {
                _state = AppState.Scanning;
                _connectedTo = null;
                UpdateUi();
                return;
            }
            savedPassword = entered;
        }

        // 2. Probe authentication — only used to detect a wrong password.
        // Network glitches (timeout / connection refused) shouldn't kill
        // the connect attempt; mount has its own retries and timeouts.
        var probeResult = await ProbeAuthAsync(svc.Url, savedPassword);
        if (probeResult == AuthProbe.Unauthorized)
        {
            var entered = AskPassword(label, wrongPassword: !string.IsNullOrEmpty(savedPassword));
            if (entered == null)
            {
                _state = AppState.Scanning;
                _connectedTo = null;
                UpdateUi();
                return;
            }
            savedPassword = entered;
            probeResult = await ProbeAuthAsync(svc.Url, savedPassword);
            if (probeResult == AuthProbe.Unauthorized)
            {
                ShowBalloon("Wrong PIN", "Phone rejected the PIN.");
                _state = AppState.Scanning;
                _connectedTo = null;
                UpdateUi();
                return;
            }
        }
        // Unreachable → fall through; let the mount step surface the error.

        // Save (or refresh) password under this device's key
        if (!string.IsNullOrEmpty(savedPassword))
        {
            _settings.SavePassword(deviceKey, savedPassword);
        }
        else
        {
            // Phone doesn't need a password — clear any stale saved one
            _settings.ForgetDevice(deviceKey);
        }

        // 3. Actually mount
        var result = await _mounter.MountAsync(svc.Url, label, savedPassword);
        if (!result.Success)
        {
            ShowBalloon("Mount failed", result.Error ?? "unknown");
            _state = AppState.Scanning;
            _connectedTo = null;
            UpdateUi();
            return;
        }

        // For WebDAV, set the cosmetic drive label via registry. WinFSP
        // already advertises its own volume label so don't muck with that.
        if (result.Kind == MountKind.WebDav)
            DriveLabel.Apply(result.DriveLetter!, label);

        _state = AppState.Connected;
        UpdateUi();
        _connectedPassword = savedPassword;
        // Try to upgrade to a Bearer token. If we already have one for
        // this phone, just use it. Otherwise, do a one-shot /api/auth/pair
        // call using the PIN we just verified. Falls back to PIN-only if
        // pairing fails (e.g. older phone build without the endpoint).
        _connectedToken = await EnsurePairedTokenAsync(svc, savedPassword);
        _health.Start(svc.Url, savedPassword, _connectedToken);
        _queue.Start(svc.Url, savedPassword, _connectedToken);
        _phoneEvents.Start(svc.Url, savedPassword, _connectedToken);

        var backendName = result.Kind == MountKind.WinFsp ? "WinFSP" : "WebDAV";
        ShowBalloon(
            "Connected",
            $"Mounted as {result.DriveLetter}: ({label}) via {backendName}. Click to open.",
            onClick: () => _mounter.OpenInExplorer());
    }

    private string? AskPassword(string deviceName, bool wrongPassword)
    {
        string? answer = null;
        Action ui = () =>
        {
            using var dlg = new PasswordPrompt(deviceName, wrongPassword);
            if (dlg.ShowDialog() == DialogResult.OK && !string.IsNullOrEmpty(dlg.Password))
                answer = dlg.Password;
        };
        // Marshal to UI thread (we may be called from a worker)
        if (System.Windows.Forms.Form.ActiveForm?.InvokeRequired == true)
            System.Windows.Forms.Form.ActiveForm.Invoke(ui);
        else
            ui();
        return answer;
    }

    private enum AuthProbe { Ok, Unauthorized, Unreachable }

    private static async System.Threading.Tasks.Task<AuthProbe> ProbeAuthAsync(string url, string? password)
    {
        try
        {
            using var client = AuthHttp.Build(new Uri(url), password, TimeSpan.FromSeconds(10));
            using var resp = await client.GetAsync("/api/files");
            if ((int)resp.StatusCode == 401) return AuthProbe.Unauthorized;
            return AuthProbe.Ok;
        }
        catch
        {
            return AuthProbe.Unreachable;
        }
    }

    private async System.Threading.Tasks.Task DisconnectAsync(bool silent = false)
    {
        _health.Stop();
        _queue.Stop();
        _phoneEvents.Stop();
        _connectedPassword = null;
        _connectedToken = null;
        if (_phoneCasting)
        {
            _phoneCasting = false;
            _viewScreenItem.Visible = false;
            LocalEvents.Push("phone.screen.stopped", new { reason = "disconnect" });
        }
        if (_screenForm != null && !_screenForm.IsDisposed)
        {
            try { _screenForm.Close(); } catch { }
            _screenForm = null;
        }
        var letter = _mounter.CurrentDrive;
        var kind = _mounter.CurrentKind;
        if (letter != null && kind == MountKind.WebDav) DriveLabel.Clear(letter);
        await _mounter.UnmountAsync();
        _connectedTo = null;
        _state = AppState.Disconnected;
        UpdateUi();
        if (!silent) ShowBalloon("Disconnected", "Drive unmounted.");
    }

    private static string BackendStatusText()
    {
        return WinFspManager.IsInstalled()
            ? "Backend: WinFSP (full local-disk speed)"
            : "Backend: WebDAV (built-in)";
    }

    private void OpenSettings()
    {
        using var dlg = new SettingsDialog(_settings);
        dlg.ShowDialog();
    }

    /// <summary>
    /// Reacts to events the phone pushes over /api/events. The main use
    /// case right now is surfacing screen-cast start so the user knows
    /// they can open the live viewer.
    /// </summary>
    private void OnPhoneEvent(PhoneEvent ev)
    {
        switch (ev.Type)
        {
            case "screen.started":
                _phoneCasting = true;
                _viewScreenItem.Visible = true;
                var dims = "";
                var w = ev.GetInt("width");
                var h = ev.GetInt("height");
                if (w != null && h != null) dims = $" ({w}×{h})";
                ShowBalloon(
                    "Phone is sharing its screen",
                    "Click to open the live viewer in your browser.",
                    onClick: OpenPhoneScreenViewer);
                LocalEvents.Push("phone.screen.started",
                    new { width = w, height = h, viewerUrl = PhoneScreenViewerUrl() });
                break;
            case "screen.stopped":
                _phoneCasting = false;
                _viewScreenItem.Visible = false;
                LocalEvents.Push("phone.screen.stopped", new { reason = "phone" });
                // If the viewer window is open, notify it so it can
                // auto-save any in-progress recording or close itself.
                if (_screenForm != null && !_screenForm.IsDisposed)
                {
                    try { _screenForm.NotifyRemoteStopped(); } catch { }
                }
                break;
            case "clipboard.changed":
                if (_settings.ClipboardAutoMirror)
                {
                    // Fetch the actual full text asynchronously — the
                    // event only carries a preview to keep frames small.
                    _ = MirrorPhoneClipboardAsync();
                }
                break;
            // Other events flow through to /api/events on the tray side
            // already (via re-emission below) — agents can subscribe
            // there for everything.
        }
        // Mirror every phone event into the tray's local broadcaster so
        // local agents subscribed to ws://127.0.0.1:9099/ws/events see
        // both ends of the conversation without having to dial the phone.
        LocalEvents.Push("phone." + ev.Type, new
        {
            ts = ev.Ts,
            seq = ev.Seq,
            data = ev.DataJson,
        });
    }

    public bool IsPhoneCasting => _phoneCasting;
    public string? PhoneScreenViewerUrl() =>
        _connectedTo?.Url is { } baseUrl ? baseUrl.TrimEnd('/') + "/screen" : null;

    private void OpenPhoneScreenViewer()
    {
        var baseUrl = _connectedTo?.Url;
        if (string.IsNullOrEmpty(baseUrl))
        {
            ShowBalloon("No phone connected", "Connect to a phone first, then start screen cast from the phone app.");
            return;
        }

        // Reuse an existing viewer window if we already have one — avoids
        // a fresh HTTP connection (and a brief black flash) every click.
        if (_screenForm != null && !_screenForm.IsDisposed)
        {
            try
            {
                _screenForm.Show();
                _screenForm.BringToFront();
                _screenForm.Activate();
                return;
            }
            catch { _screenForm = null; }
        }

        var form = new ScreenStreamForm(baseUrl, _connectedPassword, _connectedToken);
        form.FormClosed += (_, _) => _screenForm = null;
        _screenForm = form;
        form.Show();
    }

    /// <summary>
    /// Returns a Bearer token for this phone, minting one if we don't
    /// already have it. The cached path uses Settings.KnownDeviceTokens
    /// (DPAPI-encrypted); cache misses fall through to POST /api/auth/pair
    /// with Basic auth. If pairing fails for any reason we return null
    /// and the caller falls back to PIN-only.
    /// </summary>
    private async System.Threading.Tasks.Task<string?> EnsurePairedTokenAsync(
        DiscoveredService svc, string? pin)
    {
        var key = svc.Name; // stable per-phone identifier
        if (_settings.KnownDeviceTokens.TryGetValue(key, out var encrypted))
        {
            var existing = DecryptToken(encrypted);
            if (!string.IsNullOrEmpty(existing))
            {
                // Quick probe to make sure the token still works — if it
                // doesn't (revoked from the phone), drop the cache and
                // re-pair below.
                if (await TokenIsValidAsync(svc.Url, existing))
                {
                    return existing;
                }
                _settings.KnownDeviceTokens.Remove(key);
                _settings.Save();
            }
        }

        if (string.IsNullOrEmpty(pin)) return null; // can't pair without PIN

        try
        {
            using var http = AuthHttp.Build(new Uri(svc.Url), pin, TimeSpan.FromSeconds(8));
            var body = new System.Net.Http.StringContent(
                $"{{\"name\":\"{System.Net.WebUtility.UrlEncode(_settings.DeviceName)}\"}}",
                System.Text.Encoding.UTF8, "application/json");
            using var resp = await http.PostAsync("/api/auth/pair", body);
            if (!resp.IsSuccessStatusCode) return null;
            var text = await resp.Content.ReadAsStringAsync();
            using var doc = System.Text.Json.JsonDocument.Parse(text);
            var token = doc.RootElement.TryGetProperty("token", out var t) ? t.GetString() : null;
            if (string.IsNullOrEmpty(token)) return null;
            _settings.KnownDeviceTokens[key] = EncryptToken(token);
            _settings.Save();
            return token;
        }
        catch
        {
            return null;
        }
    }

    private static async System.Threading.Tasks.Task<bool> TokenIsValidAsync(
        string baseUrl, string token)
    {
        try
        {
            using var http = AuthHttp.Build(new Uri(baseUrl), null,
                TimeSpan.FromSeconds(4), token);
            using var resp = await http.GetAsync("/api/info");
            // /api/info is public, so we use /api/files to actually
            // exercise the auth path.
            using var resp2 = await http.GetAsync("/api/files");
            return resp2.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private static string EncryptToken(string token)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(token);
        var enc = System.Security.Cryptography.ProtectedData.Protect(
            bytes, null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(enc);
    }

    private static string DecryptToken(string encrypted)
    {
        try
        {
            var bytes = Convert.FromBase64String(encrypted);
            var dec = System.Security.Cryptography.ProtectedData.Unprotect(
                bytes, null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
            return System.Text.Encoding.UTF8.GetString(dec);
        }
        catch { return ""; }
    }

    /// <summary>
    /// Win32 told us the Windows clipboard changed. Decide whether to
    /// push it to the phone — only when AutoMirror is on, a phone is
    /// paired, the new text isn't what we just pasted from the phone
    /// (avoids feedback loop), and the change is text (not files /
    /// images, which the phone-side string clipboard wouldn't accept).
    /// </summary>
    private void OnPcClipboardChanged()
    {
        if (!_settings.ClipboardAutoMirror) return;
        var baseUrl = _connectedTo?.Url;
        if (string.IsNullOrEmpty(baseUrl)) return;

        string text;
        try
        {
            if (!Clipboard.ContainsText()) return;
            text = Clipboard.GetText() ?? "";
        }
        catch { return; /* clipboard busy — try again next change */ }
        if (string.IsNullOrEmpty(text)) return;
        // Loop guard — _lastMirroredClipboard is set both when WE paste
        // (mirroring from phone) and when WE push (mirroring to phone).
        if (text == _lastMirroredClipboard) return;
        _lastMirroredClipboard = text;
        _ = PushClipboardAsync(baseUrl, text, silent: true);
    }

    private async System.Threading.Tasks.Task MirrorPhoneClipboardAsync()
    {
        var baseUrl = _connectedTo?.Url;
        if (string.IsNullOrEmpty(baseUrl)) return;
        try
        {
            using var http = AuthHttp.Build(new Uri(baseUrl), _connectedPassword, TimeSpan.FromSeconds(10), _connectedToken);
            using var resp = await http.GetAsync("/api/clipboard");
            if (!resp.IsSuccessStatusCode) return;
            var body = await resp.Content.ReadAsStringAsync();
            using var doc = System.Text.Json.JsonDocument.Parse(body);
            var text = doc.RootElement.TryGetProperty("text", out var t) ? t.GetString() : null;
            if (string.IsNullOrEmpty(text)) return;
            if (text == _lastMirroredClipboard) return; // already pasted this one
            _lastPhoneClipboard = text;
            _lastMirroredClipboard = text;
            Post(() =>
            {
                try
                {
                    Clipboard.SetText(text);
                    ShowBalloon("Clipboard synced from phone",
                        text.Length > 80 ? text.Substring(0, 80) + "…" : text);
                }
                catch { /* clipboard busy — ignore */ }
            });
        }
        catch { /* phone went away — silent */ }
    }

    private async System.Threading.Tasks.Task PullClipboardFromPhoneAsync()
    {
        await MirrorPhoneClipboardAsync();
    }

    private void PushClipboardToPhone()
    {
        var baseUrl = _connectedTo?.Url;
        if (string.IsNullOrEmpty(baseUrl))
        {
            ShowBalloon("Push clipboard", "Not connected to a phone right now.");
            return;
        }
        string text;
        try { text = Clipboard.GetText() ?? ""; }
        catch { text = ""; }
        if (string.IsNullOrEmpty(text))
        {
            ShowBalloon("Push clipboard", "PC clipboard is empty (or contains non-text data).");
            return;
        }
        _ = PushClipboardAsync(baseUrl, text);
    }

    private async System.Threading.Tasks.Task PushClipboardAsync(
        string baseUrl, string text, bool silent = false)
    {
        try
        {
            using var http = AuthHttp.Build(new Uri(baseUrl), _connectedPassword, TimeSpan.FromSeconds(10), _connectedToken);
            var content = new System.Net.Http.StringContent(
                text, System.Text.Encoding.UTF8, "text/plain");
            using var resp = await http.PutAsync("/api/clipboard", content);
            if (resp.IsSuccessStatusCode)
            {
                _lastMirroredClipboard = text; // suppress echo-back from event
                if (!silent)
                {
                    Post(() => ShowBalloon("Sent to phone",
                        text.Length > 80 ? text.Substring(0, 80) + "…" : text));
                }
            }
            else if (!silent)
            {
                Post(() => ShowBalloon("Push clipboard failed", $"HTTP {(int)resp.StatusCode}"));
            }
        }
        catch (Exception ex)
        {
            if (!silent) Post(() => ShowBalloon("Push clipboard failed", ex.Message));
        }
    }

    // ── DLNA "Cast to TV" ──────────────────────────────────────────

    private void OnDlnaRendererFound(DlnaRenderer renderer)
    {
        // Skip duplicates — TVs answer M-SEARCH bursts multiple times.
        foreach (ToolStripItem existing in _castToTvMenu.DropDownItems)
        {
            if (existing.Tag is DlnaRenderer r && r.Udn == renderer.Udn) return;
        }

        var sub = new ToolStripMenuItem(renderer.FriendlyName) { Tag = renderer };
        var castFile = new ToolStripMenuItem("Cast a video / photo from PC…",
            null, (_, _) => CastFileFromPc(renderer));
        var stop = new ToolStripMenuItem("Stop playback",
            null, async (_, _) => await DlnaClient.StopAsync(renderer));
        sub.DropDownItems.Add(castFile);
        sub.DropDownItems.Add(new ToolStripSeparator());
        sub.DropDownItems.Add(stop);
        _castToTvMenu.DropDownItems.Add(sub);
        _castToTvMenu.Visible = true;
    }

    private void CastFileFromPc(DlnaRenderer renderer)
    {
        using var dlg = new OpenFileDialog
        {
            Title = $"Cast to {renderer.FriendlyName}",
            Filter = "Media files|*.mp4;*.mkv;*.webm;*.mov;*.avi;*.jpg;*.jpeg;*.png;*.gif;*.webp;*.mp3;*.m4a;*.aac;*.flac;*.ogg|All files|*.*",
            CheckFileExists = true,
        };
        if (dlg.ShowDialog() != DialogResult.OK) return;
        _ = CastFileAsync(renderer, dlg.FileName);
    }

    private async System.Threading.Tasks.Task CastFileAsync(DlnaRenderer renderer, string filePath)
    {
        var url = _castServer.RegisterFile(filePath);
        if (url == null)
        {
            ShowBalloon("Cast failed", "Local media server isn't running — try restarting the tray.");
            return;
        }
        var mime = MimeFromExt(System.IO.Path.GetExtension(filePath));
        var setOk = await DlnaClient.SetUriAsync(renderer, url, mime,
            System.IO.Path.GetFileName(filePath));
        if (!setOk)
        {
            ShowBalloon("Cast failed", $"{renderer.FriendlyName} rejected SetAVTransportURI.");
            return;
        }
        var playOk = await DlnaClient.PlayAsync(renderer);
        if (!playOk)
        {
            ShowBalloon("Cast failed", $"{renderer.FriendlyName} accepted the URL but won't play it.");
            return;
        }
        ShowBalloon($"Casting to {renderer.FriendlyName}",
            System.IO.Path.GetFileName(filePath));
    }

    private static string MimeFromExt(string ext) => ext.ToLowerInvariant() switch
    {
        ".mp4" => "video/mp4",
        ".mkv" => "video/x-matroska",
        ".webm" => "video/webm",
        ".mov" => "video/quicktime",
        ".avi" => "video/x-msvideo",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".mp3" => "audio/mpeg",
        ".m4a" or ".aac" => "audio/aac",
        ".flac" => "audio/flac",
        ".ogg" => "audio/ogg",
        _ => "application/octet-stream",
    };

    private void OpenLocalApiDocs()
    {
        var url = _localApi.Url;
        if (string.IsNullOrEmpty(url))
        {
            ShowBalloon("Local API not running",
                "The tray's local API failed to bind a port. Check Windows firewall settings or restart the tray.");
            return;
        }
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = $"{url}/docs",
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            ShowBalloon("Failed to open docs", ex.Message);
        }
    }

    private void ToggleShareTarget()
    {
        // Probe synchronously here since the user just clicked
        var isReg = WindowsShareTarget.IsRegistered;

        if (isReg)
        {
            var ok = MessageBox.Show(
                "Remove WiFi Share from the Windows Share dialog?",
                "WiFi Share",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Question);
            if (ok != DialogResult.OK) return;
            var (success, msg) = WindowsShareTarget.Unregister();
            if (success) ShowBalloon("Removed", "Windows Share entry cleared.");
            else MessageBox.Show("Unregister failed:\n" + msg, "WiFi Share",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        else
        {
            // Pre-flight: Developer Mode must be on, otherwise sparse
            // packages can't register and the script will throw.
            if (!WindowsShareTarget.IsDeveloperModeEnabled())
            {
                var open = MessageBox.Show(
                    "Developer Mode must be ON for this to work.\n\n" +
                    "Open Windows Settings now? Toggle 'Developer Mode' to On, " +
                    "then come back and click this menu item again.",
                    "WiFi Share — Developer Mode required",
                    MessageBoxButtons.OKCancel,
                    MessageBoxIcon.Warning);
                if (open == DialogResult.OK) WindowsShareTarget.OpenDeveloperSettings();
                return;
            }

            var go = MessageBox.Show(
                "Register WiFi Share in the Windows Share dialog (Win+H)?\n\n" +
                "This will run a PowerShell script. The .exe stays where it is.",
                "WiFi Share — Windows Share integration",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Information);
            if (go != DialogResult.OK) return;

            var (success, msg) = WindowsShareTarget.Register();
            if (success)
                ShowBalloon("Registered", "WiFi Share now appears in Windows Share dialog.");
            else
                MessageBox.Show("Register failed:\n" + msg, "WiFi Share",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        // Refresh the menu label
        var nowRegistered = WindowsShareTarget.IsRegistered;
        _shareTargetItem.Text = nowRegistered
            ? "Remove from Windows Share"
            : "Show in Windows Share (Win+H)…";
    }

    private void OpenWebPage()
    {
        var url = _connectedTo?.Url ?? _mounter.CurrentUrl;
        if (string.IsNullOrEmpty(url)) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true,
            });
        }
        catch { /* ignore */ }
    }

    private void DoInstall()
    {
        try
        {
            Installer.Install();
            _installItem.Visible = false;
            _uninstallItem.Visible = true;
            _autoStartItem.Checked = Installer.IsAutoStartEnabled;
            var msg = Installer.IsRunningFromInstallDir
                ? "Auto-start enabled. The app will launch with Windows."
                : $"Installed to {Installer.InstallDir}.\n\nAuto-start is on. " +
                  "On next reboot the installed copy will run automatically.";
            MessageBox.Show(msg, "WiFi Share", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Install failed: " + ex.Message, "WiFi Share",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void DoUninstall()
    {
        var ok = MessageBox.Show(
            "Remove the installed copy and disable auto-start?\n" +
            "(The currently running app stays open until you Quit.)",
            "WiFi Share — Uninstall",
            MessageBoxButtons.OKCancel,
            MessageBoxIcon.Question);
        if (ok != DialogResult.OK) return;

        try
        {
            Installer.Uninstall();
            _installItem.Visible = !Installer.IsInstalled;
            _uninstallItem.Visible = Installer.IsInstalled;
            _autoStartItem.Checked = Installer.IsAutoStartEnabled;
            ShowBalloon("Uninstalled", "Auto-start removed.");
        }
        catch (Exception ex)
        {
            MessageBox.Show("Uninstall failed: " + ex.Message, "WiFi Share",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void OnFileReceived(ReceivedFile file)
    {
        LocalEvents.Push("file.received", new
        {
            name = file.Name,
            path = file.Path,
            size = file.Size,
        });
        Post(() =>
        {
            ShowBalloon(
                $"Received {file.Name}",
                $"Saved to {Path.GetDirectoryName(file.Path)}. Click to open.",
                onClick: () => OpenReceivedFile(file.Path));
        });
    }

    private void OpenWinFspDownload()
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = WinFspManager.DownloadUrl,
                UseShellExecute = true,
            });
        }
        catch { /* ignore */ }
    }

    private void RebuildDeviceMenu()
    {
        // Remove old per-device entries between header and separator
        var headerIndex = _menu.Items.IndexOf(_devicesHeader);
        var sepIndex = _menu.Items.IndexOf(_devicesSeparator);
        for (var i = sepIndex - 1; i > headerIndex; i--)
        {
            _menu.Items.RemoveAt(i);
        }

        var found = _browser.Snapshot().ToList();
        if (found.Count == 0)
        {
            var noneItem = new ToolStripMenuItem("(no devices found)") { Enabled = false };
            _menu.Items.Insert(headerIndex + 1, noneItem);
            return;
        }

        var insertAt = headerIndex + 1;
        foreach (var svc in found)
        {
            var item = new ToolStripMenuItem(
                $"{TrimName(svc.Name)} — {svc.Address}",
                null,
                async (_, _) => await ConnectAsync(svc));
            item.Checked = _connectedTo?.Name == svc.Name;
            _menu.Items.Insert(insertAt++, item);
        }
    }

    private static string TrimName(string fullName)
    {
        // mDNS instance names appear like
        //   "WiFi\032Share\032-\032Pixel\0329\032Pro._wifishare._tcp.local"
        // Strip the trailing service-type labels and decode the space escapes.
        var first = fullName.Split('.')[0];
        return UnescapeMdnsLabel(first);
    }

    private static string UnescapeMdnsLabel(string label)
    {
        // DNS labels escape special characters as \DDD (octal). The only ones
        // we expect from Android NSD are spaces (\032) and dots (\\).
        var sb = new System.Text.StringBuilder(label.Length);
        for (int i = 0; i < label.Length; i++)
        {
            if (label[i] == '\\' && i + 3 < label.Length &&
                char.IsDigit(label[i + 1]) && char.IsDigit(label[i + 2]) && char.IsDigit(label[i + 3]))
            {
                var oct = label.Substring(i + 1, 3);
                if (int.TryParse(oct, out var code)) { sb.Append((char)code); i += 3; continue; }
            }
            if (label[i] == '\\' && i + 1 < label.Length)
            {
                sb.Append(label[i + 1]); i++; continue;
            }
            sb.Append(label[i]);
        }
        return sb.ToString();
    }

    // Last value pushed to LocalEvents — we only emit state.changed when
    // the tuple actually changes, otherwise the firehose floods clients
    // with no-op renders (UpdateUi runs on every menu redraw too).
    private (AppState State, string? Drive)? _lastEmittedState;

    private void UpdateUi()
    {
        var backend = _mounter.CurrentKind switch
        {
            MountKind.WinFsp => "WinFSP",
            MountKind.WebDav => "WebDAV",
            _ => "",
        };

        var snapshot = (_state, _mounter.CurrentDrive);
        if (_lastEmittedState != snapshot)
        {
            _lastEmittedState = snapshot;
            LocalEvents.Push("state.changed", new
            {
                state = _state.ToString(),
                connectedTo = _connectedTo?.Name,
                connectedUrl = _connectedTo?.Url,
                mountedDrive = _mounter.CurrentDrive,
            });
        }
        var (label, color, tooltip) = _state switch
        {
            AppState.Connected => (
                $"Connected · {_mounter.CurrentDrive}: ({backend})",
                Color.MediumSeaGreen,
                $"WiFi Share — {_mounter.CurrentDrive}: via {backend}"),
            AppState.Connecting => (
                "Connecting…",
                Color.Goldenrod,
                "WiFi Share — connecting"),
            AppState.Disconnected => (
                "Disconnected",
                Color.Gray,
                "WiFi Share — disconnected"),
            _ => (
                "Scanning…",
                Color.SteelBlue,
                "WiFi Share — scanning"),
        };

        _statusItem.Text = label;
        _tray.Icon?.Dispose();
        _tray.Icon = BuildIcon(color);
        _tray.Text = tooltip.Length > 63 ? tooltip[..63] : tooltip;

        _openItem.Enabled = _state == AppState.Connected;
        _openWebItem.Enabled = _state == AppState.Connected;
        _disconnectItem.Enabled = _state == AppState.Connected;
    }

    private Action? _balloonClickAction;

    private void ShowBalloon(string title, string body, Action? onClick = null)
    {
        try
        {
            _balloonClickAction = onClick;
            _tray.BalloonTipTitle = title;
            _tray.BalloonTipText = body;
            _tray.BalloonTipIcon = ToolTipIcon.Info;
            _tray.ShowBalloonTip(3000);
        }
        catch { /* ignore */ }
    }

    private static void OpenReceivedFile(string path)
    {
        if (string.IsNullOrEmpty(path)) return;
        // 1st attempt: open with default app
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true,
            });
            return;
        }
        catch { /* fall through */ }
        // Fallback: open containing folder with the file pre-selected
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = "/select,\"" + path + "\"",
                UseShellExecute = true,
            });
        }
        catch { /* give up */ }
    }

    private void Post(Action action)
    {
        _ui.Post(_ => action(), null);
    }

    private void Cleanup()
    {
        try { _health.Stop(); } catch { }
        try { _queue.Stop(); } catch { }
        try { _phoneEvents.Dispose(); } catch { }
        try { _ipc.Stop(); } catch { }
        try { _localApi.Dispose(); } catch { }
        try { _ssdp.Dispose(); } catch { }
        try { _castServer.Dispose(); } catch { }
        try { _clipboardWatcher.Dispose(); } catch { }
        try { _ = _mounter.UnmountAsync(); } catch { }
        try { _tray.Visible = false; } catch { }
    }

    public void Dispose()
    {
        Cleanup();
        _browser.Dispose();
        _health.Dispose();
        _ipc.Dispose();
        _menu.Dispose();
        _tray.Dispose();
    }

    /// <summary>Build a small colored circle as the tray icon.</summary>
    private static Icon BuildIcon(Color fill)
    {
        const int size = 32;
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var brush = new SolidBrush(fill);
            g.FillEllipse(brush, 4, 4, size - 8, size - 8);
            using var pen = new Pen(Color.White, 2);
            g.DrawEllipse(pen, 4, 4, size - 8, size - 8);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }
}
