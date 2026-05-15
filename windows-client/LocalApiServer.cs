using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// Snapshot of the tray's high-level state, fed into the local HTTP API
/// so external tools (AI agents, scripts) can read the current status
/// without having to scrape menu items or settings files.
/// </summary>
public sealed record StatusSnapshot(
    string State,
    string? ConnectedToName,
    string? ConnectedToUrl,
    int ConnectedToPort,
    bool ConnectedToAuthRequired,
    bool AutoConnect,
    string? MountedDrive,
    bool WinFspInstalled,
    string DeviceName,
    string Version);

/// <summary>
/// Loopback-only HTTP API on 127.0.0.1 so AI agents / shell scripts on
/// the same PC can introspect the tray's state and the LAN's discovered
/// devices without parsing the JSON settings files directly.
///
/// Discovery: the chosen URL (port may shift if 9099 is taken) is written
/// to %LOCALAPPDATA%\WiFiShare\local-api.json so agents have a stable
/// path to look up where to connect.
///
/// Security: HttpListener binds the 127.0.0.1 prefix only, so the API is
/// unreachable from other hosts. No auth is required since only local
/// processes can reach it — adding auth here would just be noise.
/// </summary>
public sealed class LocalApiServer : IDisposable
{
    /// <summary>
    /// Canonical port. AI agents can hardcode this — the tray is
    /// single-instance (enforced by the IPC named-pipe), so this port
    /// won't be in use by another tray. If a different app on the box
    /// happens to occupy it, the local API stays down and the tray
    /// shows a balloon explaining why.
    /// </summary>
    public const int Port = 9099;
    public const string CanonicalUrl = "http://127.0.0.1:9099";

    private readonly Func<StatusSnapshot> _getStatus;
    private readonly MdnsBrowser _browser;
    private readonly PendingUploads _pending;
    private readonly Settings _settings;

    private HttpListener? _listener;
    private CancellationTokenSource? _cts;
    private string? _discoveryPath;

    public string? Url { get; private set; }
    public string? StartupError { get; private set; }

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public LocalApiServer(
        Func<StatusSnapshot> getStatus,
        MdnsBrowser browser,
        PendingUploads pending,
        Settings settings)
    {
        _getStatus = getStatus;
        _browser = browser;
        _pending = pending;
        _settings = settings;
    }

    public void Start()
    {
        // Single fixed port — agents can hardcode http://127.0.0.1:9099/.
        // The tray is single-instance (IPC named pipe enforces that), so
        // the only thing that can occupy this port is an unrelated app,
        // in which case we surface a balloon instead of silently moving.
        try
        {
            var listener = new HttpListener();
            listener.Prefixes.Add($"http://127.0.0.1:{Port}/");
            listener.Start();
            _listener = listener;
            Url = CanonicalUrl;
        }
        catch (HttpListenerException ex)
        {
            StartupError = $"Port {Port} is already in use (HRESULT {ex.ErrorCode}). " +
                "Close whatever owns it, or set HKCU\\Software\\WiFiShare\\LocalApiDisabled=1.";
            return;
        }
        catch (Exception ex)
        {
            StartupError = ex.Message;
            return;
        }

        _cts = new CancellationTokenSource();
        _ = Task.Run(() => AcceptLoop(_cts.Token));

        WriteDiscoveryFile();
    }

    private void WriteDiscoveryFile()
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "WiFiShare");
            Directory.CreateDirectory(dir);
            _discoveryPath = Path.Combine(dir, "local-api.json");
            var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "0";
            var doc = new
            {
                url = Url,
                pid = Environment.ProcessId,
                version,
                started = DateTimeOffset.UtcNow.ToString("O"),
            };
            File.WriteAllText(
                _discoveryPath,
                JsonSerializer.Serialize(doc, JsonOpts));
        }
        catch
        {
            // best-effort — the tray still works without the discovery
            // file, agents just have to scan ports 9099..9120 themselves.
        }
    }

    private async Task AcceptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener?.IsListening == true)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await _listener.GetContextAsync();
            }
            catch
            {
                return;
            }
            _ = Task.Run(() => HandleRequest(ctx));
        }
    }

    private void HandleRequest(HttpListenerContext ctx)
    {
        try
        {
            var path = (ctx.Request.Url?.AbsolutePath ?? "/").TrimEnd('/').ToLowerInvariant();
            if (string.IsNullOrEmpty(path)) path = "/";

            ctx.Response.Headers["Access-Control-Allow-Origin"] = "*";
            ctx.Response.Headers["Cache-Control"] = "no-store";

            if (ctx.Request.HttpMethod == "OPTIONS")
            {
                ctx.Response.Headers["Access-Control-Allow-Methods"] = "GET, OPTIONS";
                ctx.Response.Headers["Access-Control-Allow-Headers"] = "Content-Type";
                ctx.Response.StatusCode = 204;
                return;
            }

            switch (path)
            {
                case "/":
                    WriteHtml(ctx, BuildIndexHtml());
                    return;
                case "/docs":
                    WriteHtml(ctx, BuildDocsHtml());
                    return;
                case "/api/manifest":
                    WriteJson(ctx, BuildManifest());
                    return;
                case "/api/status":
                    WriteJson(ctx, _getStatus());
                    return;
                case "/api/devices":
                    WriteJson(ctx, _browser.Snapshot().Select(d => new
                    {
                        name = d.Name,
                        address = d.Address.ToString(),
                        port = (int)d.Port,
                        url = d.Url,
                        authRequired = d.AuthRequired,
                    }).ToList());
                    return;
                case "/api/pending":
                    WriteJson(ctx, _pending.Snapshot());
                    return;
                case "/api/settings":
                    WriteJson(ctx, new
                    {
                        deviceName = _settings.DeviceName,
                        downloadFolder = _settings.DownloadFolder,
                        autoConnect = _settings.AutoConnect,
                        knownDevices = _settings.KnownDevices?.Keys.ToList() ?? new List<string>(),
                    });
                    return;
                case "/api/health":
                    WriteJson(ctx, new { ok = true });
                    return;
                default:
                    ctx.Response.StatusCode = 404;
                    WriteJson(ctx, new { error = "not found", path });
                    return;
            }
        }
        catch (Exception ex)
        {
            try
            {
                ctx.Response.StatusCode = 500;
                WriteJson(ctx, new { error = ex.Message });
            }
            catch { /* socket may already be gone */ }
        }
        finally
        {
            try { ctx.Response.Close(); } catch { }
        }
    }

    private static void WriteJson(HttpListenerContext ctx, object payload)
    {
        ctx.Response.ContentType = "application/json; charset=utf-8";
        var body = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload, JsonOpts));
        ctx.Response.ContentLength64 = body.Length;
        ctx.Response.OutputStream.Write(body, 0, body.Length);
    }

    private static void WriteHtml(HttpListenerContext ctx, string html)
    {
        ctx.Response.ContentType = "text/html; charset=utf-8";
        var body = Encoding.UTF8.GetBytes(html);
        ctx.Response.ContentLength64 = body.Length;
        ctx.Response.OutputStream.Write(body, 0, body.Length);
    }

    private string BuildIndexHtml()
    {
        var s = _getStatus();
        return $@"<!doctype html><html><head><meta charset=""utf-8""><title>WiFiShareTray local API</title>
<style>body{{font-family:system-ui;max-width:680px;margin:2em auto;padding:0 1em;color:#222}}
code{{background:#eef;padding:2px 5px;border-radius:3px;font-size:0.9em}}
.kv{{display:grid;grid-template-columns:160px 1fr;gap:6px 14px;margin:1em 0}}
a{{color:#06c}}</style></head>
<body><h1>WiFi Share Tray — local API</h1>
<p>Loopback-only diagnostic API. AI agents and scripts on this PC can query the JSON endpoints below to read state.</p>
<div class=""kv"">
<div>State</div><div><b>{HtmlEncode(s.State)}</b></div>
<div>Connected to</div><div>{HtmlEncode(s.ConnectedToName ?? "(none)")}</div>
<div>URL</div><div>{HtmlEncode(s.ConnectedToUrl ?? "—")}</div>
<div>Auth required</div><div>{s.ConnectedToAuthRequired}</div>
<div>Mounted drive</div><div>{HtmlEncode(s.MountedDrive ?? "—")}</div>
<div>Auto-connect</div><div>{s.AutoConnect}</div>
<div>WinFSP installed</div><div>{s.WinFspInstalled}</div>
<div>Device name</div><div>{HtmlEncode(s.DeviceName)}</div>
<div>Version</div><div>{HtmlEncode(s.Version)}</div>
</div>
<p><a href=""/docs""><b>→ Full API documentation</b></a> · machine-readable manifest at <a href=""/api/manifest""><code>/api/manifest</code></a></p>
<h2>Endpoints</h2>
<ul>
<li><a href=""/api/status""><code>GET /api/status</code></a> — current tray state, connected phone, mount drive</li>
<li><a href=""/api/devices""><code>GET /api/devices</code></a> — mDNS-discovered phones on the LAN</li>
<li><a href=""/api/pending""><code>GET /api/pending</code></a> — uploads queued for later (phone offline)</li>
<li><a href=""/api/settings""><code>GET /api/settings</code></a> — non-sensitive settings (no passwords)</li>
<li><a href=""/api/health""><code>GET /api/health</code></a> — liveness probe</li>
<li><a href=""/api/manifest""><code>GET /api/manifest</code></a> — structured endpoint spec for agents</li>
</ul>
<p>Discovery file: <code>%LOCALAPPDATA%\WiFiShare\local-api.json</code></p>
</body></html>";
    }

    /// <summary>
    /// Structured JSON manifest of the API surface. Designed so AI agents
    /// can fetch ONE file to learn every endpoint, its purpose, and a
    /// sample of the response shape — instead of scraping the HTML docs.
    /// </summary>
    private object BuildManifest()
    {
        var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "0";
        return new
        {
            name = "WiFi Share Tray local API",
            description = "Loopback-only HTTP API exposing the tray's state for AI agents and scripts. Companion to the phone-side REST API on the Android app.",
            version,
            host = CanonicalUrl,
            hostNote = $"Fixed port {Port}. The tray is single-instance, so this URL is stable across restarts. Agents can hardcode it.",
            discoveryFile = "%LOCALAPPDATA%\\WiFiShare\\local-api.json",
            phoneApiNote = "When /api/status reports State == \"Connected\", the phone's full REST API is reachable at ConnectedToUrl (e.g. http://10.131.141.87:8080). See the phone's /docs page for the file-management surface.",
            endpoints = new object[]
            {
                new
                {
                    method = "GET",
                    path = "/api/status",
                    description = "Snapshot of the tray's current state: scanning / connecting / connected / disconnected, the connected phone (name, URL, port, auth flag), mounted drive letter, WinFSP install state, auto-connect flag, this PC's device name, tray version.",
                    response = new
                    {
                        State = "Connected",
                        ConnectedToName = "WiFi Share - Pixel 9 Pro._wifishare._tcp.local",
                        ConnectedToUrl = "http://10.131.141.87:8080",
                        ConnectedToPort = 8080,
                        ConnectedToAuthRequired = true,
                        AutoConnect = true,
                        MountedDrive = "Z",
                        WinFspInstalled = true,
                        DeviceName = "DESKTOP-XYZ",
                        Version = "0.7.0.0",
                    },
                },
                new
                {
                    method = "GET",
                    path = "/api/devices",
                    description = "All phones currently visible on the LAN via mDNS (_wifishare._tcp). The browser keeps polling, so this snapshot updates without needing to restart the tray.",
                    response = new[] { new
                    {
                        name = "WiFi Share - Pixel 9 Pro._wifishare._tcp.local",
                        address = "10.131.141.87",
                        port = 8080,
                        url = "http://10.131.141.87:8080",
                        authRequired = true,
                    }},
                },
                new
                {
                    method = "GET",
                    path = "/api/pending",
                    description = "Uploads queued because the target phone was offline when the user requested the send. Persisted to %APPDATA%\\WiFiShare\\pending-uploads.json — survives tray restarts. The tray auto-flushes them when the phone reappears.",
                    response = new[] { new
                    {
                        id = "abc123",
                        targetDeviceName = "WiFi Share - Pixel 9 Pro._wifishare._tcp.local",
                        targetDisplayName = "Pixel 9 Pro",
                        filePath = "C:\\Users\\edza\\Desktop\\foo.pdf",
                        createdAt = 1762345678901L,
                        attempts = 0,
                        lastError = (string?)null,
                    }},
                },
                new
                {
                    method = "GET",
                    path = "/api/settings",
                    description = "Non-sensitive tray settings. Passwords are NEVER returned here — they live DPAPI-encrypted inside settings.json on disk. KnownDevices is the list of phone mDNS names this PC has previously connected to.",
                    response = new
                    {
                        deviceName = "DESKTOP-XYZ",
                        downloadFolder = "C:\\Users\\edza\\Downloads",
                        autoConnect = true,
                        knownDevices = new[] { "WiFi Share - Pixel 9 Pro._wifishare._tcp.local" },
                    },
                },
                new
                {
                    method = "GET",
                    path = "/api/health",
                    description = "Liveness probe. Returns { ok: true } if the tray's HTTP server is up. Use this to confirm the tray is running before issuing other queries.",
                    response = new { ok = true },
                },
                new
                {
                    method = "GET",
                    path = "/api/manifest",
                    description = "This document — describes every endpoint with a sample response.",
                },
                new
                {
                    method = "GET",
                    path = "/docs",
                    description = "Human-readable HTML documentation.",
                },
            },
            agentRecipes = new object[]
            {
                new
                {
                    intent = "Is the phone reachable right now?",
                    steps = new[]
                    {
                        "GET /api/status",
                        "Check that State == \"Connected\" AND ConnectedToUrl is set.",
                    },
                },
                new
                {
                    intent = "Push a file to the phone.",
                    steps = new[]
                    {
                        "GET /api/status to get ConnectedToUrl + ConnectedToAuthRequired.",
                        "If auth required, the PIN lives DPAPI-encrypted on disk; ask the user instead of trying to read it.",
                        "PUT <ConnectedToUrl>/api/files?path=<name> with raw file body (Basic auth user:PIN if required). See the phone's /docs for full surface.",
                    },
                },
                new
                {
                    intent = "List files on the phone's shared folder.",
                    steps = new[]
                    {
                        "GET /api/status → grab ConnectedToUrl.",
                        "GET <ConnectedToUrl>/api/files (Basic auth user:PIN). Returns JSON with name/size/mime/modified per item.",
                    },
                },
                new
                {
                    intent = "Check what's queued for upload.",
                    steps = new[]
                    {
                        "GET /api/pending. Each entry has filePath + targetDisplayName + lastError if a prior attempt failed.",
                    },
                },
                new
                {
                    intent = "Find all phones on the LAN, even ones we're not connected to.",
                    steps = new[]
                    {
                        "GET /api/devices. Each entry has a URL the agent can hit directly.",
                    },
                },
            },
        };
    }

    private string BuildDocsHtml()
    {
        var s = _getStatus();
        var connectedNote = s.ConnectedToUrl != null
            ? $@"<p class=""tip""><b>Phone is connected.</b> Its full file-management API is at <code>{HtmlEncode(s.ConnectedToUrl)}</code> — fetch <code>{HtmlEncode(s.ConnectedToUrl)}/docs</code> for the phone-side endpoint reference.</p>"
            : @"<p class=""warn"">Phone is not currently connected. Endpoints below still work; <code>/api/status</code> will show <code>State != ""Connected""</code>.</p>";

        return $@"<!doctype html><html><head><meta charset=""utf-8""><title>WiFiShareTray API docs</title>
<style>
body{{font-family:system-ui;max-width:880px;margin:2em auto;padding:0 1.5em;color:#1d1d1f;line-height:1.55}}
h1{{margin-top:0}}
h2{{border-bottom:1px solid #ddd;padding-bottom:6px;margin-top:2em}}
h3{{margin-top:1.6em;color:#0a4d7a}}
code{{background:#eef1f5;padding:2px 6px;border-radius:4px;font-size:0.9em}}
pre{{background:#1e1e1e;color:#e8e8e8;padding:14px 16px;border-radius:6px;overflow:auto}}
pre code{{background:transparent;color:inherit;padding:0}}
.method{{display:inline-block;font-weight:600;color:#fff;background:#0a72c2;padding:2px 8px;border-radius:3px;font-size:0.8em;margin-right:8px}}
.tip{{background:#e6f5ec;border-left:4px solid #1e9a55;padding:10px 14px;border-radius:0 4px 4px 0}}
.warn{{background:#fff4e1;border-left:4px solid #d28e00;padding:10px 14px;border-radius:0 4px 4px 0}}
a{{color:#0a6fb5}}
.toc{{background:#f7f8fa;padding:14px 20px;border-radius:6px;border:1px solid #e1e4e8}}
.toc ul{{margin:6px 0;padding-left:20px}}
table{{border-collapse:collapse;margin:0.5em 0;font-size:0.95em}}
th,td{{border:1px solid #d6d8db;padding:6px 10px;text-align:left}}
th{{background:#f2f4f6}}
</style></head>
<body>
<h1>WiFi Share Tray — local API</h1>
<p>Loopback-only HTTP API the Windows tray exposes for AI agents and local scripts to introspect state. Companion to the phone-side REST API.</p>

{connectedNote}

<div class=""toc""><b>Contents</b>
<ul>
<li><a href=""#discovery"">Discovery</a> — finding the URL</li>
<li><a href=""#authentication"">Authentication</a> — there isn't any (and why)</li>
<li><a href=""#endpoints"">Endpoints</a></li>
<li><a href=""#recipes"">Agent recipes</a></li>
<li><a href=""#phone-bridge"">Phone API bridge</a></li>
</ul></div>

<h2 id=""discovery"">Discovery</h2>
<p>The URL is <b>fixed</b>:</p>
<pre><code>{HtmlEncode(CanonicalUrl)}</code></pre>
<p>Agents can hardcode this — the tray is single-instance (enforced via a named pipe), so port {Port} won't bounce around between tray restarts. If port {Port} is already taken by an unrelated app, the local API stays down; the tray surfaces a balloon and <code>/api/health</code> simply won't respond.</p>
<p>On startup, the tray also writes a discovery file as a sanity check / metadata sidecar:</p>
<pre><code>%LOCALAPPDATA%\WiFiShare\local-api.json</code></pre>
<pre><code>{{
  ""url"": ""{HtmlEncode(CanonicalUrl)}"",
  ""pid"": 12345,
  ""version"": ""0.7.0.0"",
  ""started"": ""2026-05-15T14:33:57Z""
}}</code></pre>

<h2 id=""authentication"">Authentication</h2>
<p>None. The HttpListener is bound to <code>127.0.0.1</code> only, so the API is unreachable from other hosts. Other local processes share this trust boundary already (they can read the user's files anyway), so adding a token here would just be noise.</p>

<h2 id=""endpoints"">Endpoints</h2>

<h3><span class=""method"">GET</span><code>/api/health</code></h3>
<p>Liveness probe. Hit this first to confirm the tray is running and the API is reachable.</p>
<pre><code>{{ ""ok"": true }}</code></pre>

<h3><span class=""method"">GET</span><code>/api/status</code></h3>
<p>The single most useful endpoint — full snapshot of the tray's current state.</p>
<table>
<tr><th>Field</th><th>Type</th><th>Notes</th></tr>
<tr><td><code>State</code></td><td>string</td><td>One of <code>Scanning</code>, <code>Connecting</code>, <code>Connected</code>, <code>Disconnected</code></td></tr>
<tr><td><code>ConnectedToName</code></td><td>string?</td><td>mDNS instance name of the phone, or <code>null</code> if not connected</td></tr>
<tr><td><code>ConnectedToUrl</code></td><td>string?</td><td>e.g. <code>http://192.168.1.179:8080</code> — pass this to phone-side endpoints</td></tr>
<tr><td><code>ConnectedToPort</code></td><td>int</td><td>0 if not connected</td></tr>
<tr><td><code>ConnectedToAuthRequired</code></td><td>bool</td><td>Whether the phone is PIN-protected</td></tr>
<tr><td><code>AutoConnect</code></td><td>bool</td><td>Whether the tray auto-connects to known phones</td></tr>
<tr><td><code>MountedDrive</code></td><td>string?</td><td>Drive letter, e.g. <code>""Z""</code>, or <code>null</code> if not mounted</td></tr>
<tr><td><code>WinFspInstalled</code></td><td>bool</td><td>True if WinFSP is installed (fast WebDAV mount)</td></tr>
<tr><td><code>DeviceName</code></td><td>string</td><td>This PC's machine name as advertised to the phone</td></tr>
<tr><td><code>Version</code></td><td>string</td><td>Tray version, e.g. <code>""0.7.0.0""</code></td></tr>
</table>

<h3><span class=""method"">GET</span><code>/api/devices</code></h3>
<p>All phones currently visible on the LAN via mDNS. Returns an array even if there's only one device.</p>
<pre><code>[
  {{
    ""name"": ""WiFi Share - Pixel 9 Pro._wifishare._tcp.local"",
    ""address"": ""10.131.141.87"",
    ""port"": 8080,
    ""url"": ""http://10.131.141.87:8080"",
    ""authRequired"": true
  }}
]</code></pre>

<h3><span class=""method"">GET</span><code>/api/pending</code></h3>
<p>Uploads queued because the phone was offline. The tray auto-flushes them when the phone reappears; this endpoint exposes the current queue.</p>
<pre><code>[
  {{
    ""id"": ""abc123…"",
    ""targetDeviceName"": ""WiFi Share - Pixel 9 Pro._wifishare._tcp.local"",
    ""targetDisplayName"": ""Pixel 9 Pro"",
    ""filePath"": ""C:\\Users\\edza\\Desktop\\foo.pdf"",
    ""createdAt"": 1762345678901,
    ""attempts"": 0,
    ""lastError"": null
  }}
]</code></pre>

<h3><span class=""method"">GET</span><code>/api/settings</code></h3>
<p>Non-sensitive tray settings. <b>Passwords are not returned</b> — they live DPAPI-encrypted on disk and the tray never exposes them over HTTP.</p>
<pre><code>{{
  ""deviceName"": ""DESKTOP-XYZ"",
  ""downloadFolder"": ""C:\\Users\\edza\\Downloads"",
  ""autoConnect"": true,
  ""knownDevices"": [""WiFi Share - Pixel 9 Pro._wifishare._tcp.local""]
}}</code></pre>

<h3><span class=""method"">GET</span><code>/api/manifest</code></h3>
<p>This same surface in structured JSON form — designed for agents that prefer to fetch one machine-readable file instead of parsing HTML. Includes endpoint list, sample responses, and a few <code>agentRecipes</code> for common tasks.</p>

<h2 id=""recipes"">Agent recipes</h2>

<h3>Is the phone reachable right now?</h3>
<pre><code>curl -s http://127.0.0.1:9099/api/status | jq '.State, .ConnectedToUrl'</code></pre>
<p>If <code>State == ""Connected""</code>, you can talk to the phone directly at <code>ConnectedToUrl</code>.</p>

<h3>List files on the phone's shared folder</h3>
<pre><code>STATUS=$(curl -s http://127.0.0.1:9099/api/status)
PHONE=$(echo $STATUS | jq -r .ConnectedToUrl)
# If auth required, ask the user for the PIN — never persist it.
curl -s -u user:$PIN ""$PHONE/api/files""</code></pre>

<h3>Watch for new phones appearing</h3>
<pre><code># Poll every 2s; print whenever a new device shows up.
seen=""""
while :; do
  curr=$(curl -s http://127.0.0.1:9099/api/devices | jq -r '.[].url' | sort)
  if [ ""$curr"" != ""$seen"" ]; then echo ""$curr""; seen=""$curr""; fi
  sleep 2
done</code></pre>

<h3>What's blocking my upload?</h3>
<pre><code>curl -s http://127.0.0.1:9099/api/pending | jq '.[] | {{file: .filePath, error: .lastError}}'</code></pre>

<h2 id=""phone-bridge"">Phone API bridge</h2>
<p>Once <code>/api/status</code> tells you <code>ConnectedToUrl</code>, you have the phone's full REST surface available — file upload/download/delete, folder browse, queue management, search, etc. See the phone's own <code>/docs</code> page (served from the same URL) for the complete reference.</p>

<p style=""margin-top:3em;color:#888;font-size:0.85em"">Generated by WiFi Share Tray {HtmlEncode(s.Version)}</p>
</body></html>";
    }

    private static string HtmlEncode(string s) => System.Net.WebUtility.HtmlEncode(s);

    public void Dispose()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        try { _listener?.Close(); } catch { }
        try
        {
            if (_discoveryPath != null && File.Exists(_discoveryPath))
                File.Delete(_discoveryPath);
        }
        catch { }
    }
}
