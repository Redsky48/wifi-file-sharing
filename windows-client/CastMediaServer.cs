using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// Tiny HTTP server bound to LAN so DLNA Media Renderers (smart TVs)
/// can pull the file we asked them to play. Required because the TV
/// pulls from the URL we hand it via SetAVTransportURI — we can't just
/// "send" the file. Files are registered with a random URL token; the
/// token is single-use (well, single-cast — the same TV may make
/// several Range requests for one playback, so we leave entries
/// registered until explicitly cleared or the form quits).
///
/// Supports HTTP Range so TVs can seek without re-downloading.
/// Distinct from the local-API server: that one is bound to 127.0.0.1
/// for AI agents; this one needs to be reachable by TVs on the LAN.
/// </summary>
public sealed class CastMediaServer : IDisposable
{
    private HttpListener? _listener;
    private CancellationTokenSource? _cts;
    private string? _hostIp;
    private int _port;

    private readonly ConcurrentDictionary<string, string> _files = new();

    /// <summary>Local URL that's reachable by other LAN devices.</summary>
    public string? BaseUrl =>
        _hostIp != null && _port > 0 ? $"http://{_hostIp}:{_port}" : null;

    public void Start()
    {
        if (_listener != null) return;
        _hostIp = PickLanAddress();
        // HttpListener bound to "+:port" matches all interfaces — bind
        // wide so TVs on any phone-hotspot or office-WiFi subnet can hit
        // us. The Windows firewall may need a one-time allow click.
        for (var port = 9100; port <= 9120; port++)
        {
            try
            {
                var l = new HttpListener();
                l.Prefixes.Add($"http://+:{port}/cast/");
                l.Start();
                _listener = l;
                _port = port;
                break;
            }
            catch (HttpListenerException)
            {
                // Port busy or insufficient permission for "+" — fall
                // back to a single-interface bind below.
            }
        }
        if (_listener == null && _hostIp != null)
        {
            // Fallback: bind to specific IP. Doesn't need URL ACL.
            for (var port = 9100; port <= 9120; port++)
            {
                try
                {
                    var l = new HttpListener();
                    l.Prefixes.Add($"http://{_hostIp}:{port}/cast/");
                    l.Start();
                    _listener = l;
                    _port = port;
                    break;
                }
                catch (HttpListenerException) { }
            }
        }
        if (_listener == null) return;

        _cts = new CancellationTokenSource();
        _ = Task.Run(() => AcceptLoop(_cts.Token));
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _listener?.Stop(); } catch { }
        try { _listener?.Close(); } catch { }
        _listener = null;
        _files.Clear();
    }

    public void Dispose() => Stop();

    /// <summary>
    /// Register a file for casting. Returns a URL the TV can fetch
    /// from, or null if the server isn't running. Token is random so
    /// LAN-snoopers can't enumerate /cast/ to access files we didn't
    /// intend to share.
    /// </summary>
    public string? RegisterFile(string filePath)
    {
        if (BaseUrl == null) return null;
        if (!File.Exists(filePath)) return null;
        var token = RandomToken();
        _files[token] = filePath;
        var ext = Path.GetExtension(filePath);
        return $"{BaseUrl}/cast/{token}{ext}";
    }

    public void Unregister(string token) => _files.TryRemove(token, out _);

    private static string RandomToken()
    {
        var bytes = new byte[16];
        RandomNumberGenerator.Fill(bytes);
        return BitConverter.ToString(bytes).Replace("-", "").ToLowerInvariant();
    }

    private async Task AcceptLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _listener?.IsListening == true)
        {
            HttpListenerContext ctx;
            try { ctx = await _listener.GetContextAsync(); }
            catch { return; }
            _ = Task.Run(() => HandleRequest(ctx));
        }
    }

    private void HandleRequest(HttpListenerContext ctx)
    {
        try
        {
            var path = ctx.Request.Url?.AbsolutePath ?? "";
            if (!path.StartsWith("/cast/")) { ctx.Response.StatusCode = 404; return; }
            // Strip extension we tacked on for the TV's benefit.
            var name = Path.GetFileNameWithoutExtension(path.Substring("/cast/".Length));
            if (!_files.TryGetValue(name, out var filePath) || !File.Exists(filePath))
            {
                ctx.Response.StatusCode = 404; return;
            }

            var info = new FileInfo(filePath);
            var total = info.Length;
            long start = 0, end = total - 1;
            // Honor Range so TVs can seek; UPnP servers must support this.
            var range = ctx.Request.Headers["Range"];
            if (!string.IsNullOrEmpty(range) && range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
            {
                var spec = range.Substring(6);
                var dash = spec.IndexOf('-');
                if (dash > 0)
                {
                    long.TryParse(spec.Substring(0, dash), out start);
                    var endStr = spec.Substring(dash + 1);
                    if (!string.IsNullOrEmpty(endStr)) long.TryParse(endStr, out end);
                }
                ctx.Response.StatusCode = 206;
                ctx.Response.Headers["Content-Range"] = $"bytes {start}-{end}/{total}";
            }
            else
            {
                ctx.Response.StatusCode = 200;
            }

            ctx.Response.ContentType = GuessMime(filePath);
            ctx.Response.ContentLength64 = end - start + 1;
            ctx.Response.Headers["Accept-Ranges"] = "bytes";
            // transferMode.dlna.org/Cache-Control hints — some TVs want
            // these to start playback instead of buffering forever.
            ctx.Response.Headers["transferMode.dlna.org"] = "Streaming";
            ctx.Response.Headers["contentFeatures.dlna.org"] =
                "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000";

            using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            fs.Seek(start, SeekOrigin.Begin);
            var buf = new byte[64 * 1024];
            long remaining = end - start + 1;
            while (remaining > 0)
            {
                var take = (int)Math.Min(buf.Length, remaining);
                var n = fs.Read(buf, 0, take);
                if (n <= 0) break;
                ctx.Response.OutputStream.Write(buf, 0, n);
                remaining -= n;
            }
        }
        catch (HttpListenerException) { /* client disconnected */ }
        catch (IOException) { /* same */ }
        catch
        {
            try { ctx.Response.StatusCode = 500; } catch { }
        }
        finally
        {
            try { ctx.Response.Close(); } catch { }
        }
    }

    private static string GuessMime(string path)
    {
        var ext = Path.GetExtension(path).ToLowerInvariant();
        return ext switch
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
    }

    /// <summary>
    /// Pick a LAN-reachable IPv4 address. Prefers wired/WiFi over
    /// virtual adapters (Hyper-V, WSL, Docker) so TVs on the home
    /// network actually find us.
    /// </summary>
    private static string? PickLanAddress()
    {
        try
        {
            foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (iface.OperationalStatus != OperationalStatus.Up) continue;
                if (iface.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                if (iface.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                var desc = iface.Description.ToLowerInvariant();
                if (desc.Contains("hyper-v") || desc.Contains("wsl") || desc.Contains("vmnet") ||
                    desc.Contains("vethernet") || desc.Contains("docker")) continue;
                foreach (var addr in iface.GetIPProperties().UnicastAddresses)
                {
                    if (addr.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(addr.Address)) continue;
                    return addr.Address.ToString();
                }
            }
        }
        catch { }
        return null;
    }
}
