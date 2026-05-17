using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;

namespace WiFiShareTray;

/// <summary>
/// One discovered DLNA Media Renderer (typically a smart TV).
/// </summary>
public sealed record DlnaRenderer(
    string Udn,                // unique device name, e.g. "uuid:1234"
    string FriendlyName,       // human label, e.g. "LG webOS TV NANO753QC"
    string ModelName,
    Uri AvTransportControlUrl, // SOAP endpoint we POST cast commands to
    IPAddress Address);

/// <summary>
/// SSDP browser that hunts for UPnP Media Renderers on the LAN. Sends
/// an M-SEARCH every 8 s and parses each device's description.xml to
/// extract the AVTransport service control URL — which is where we'll
/// POST SetAVTransportURI / Play / Stop / Pause.
///
/// Operates entirely independently of [MdnsBrowser]. mDNS is for our
/// own _wifishare._tcp service; SSDP is for the standard UPnP world.
/// </summary>
public sealed class SsdpBrowser : IDisposable
{
    private const int SsdpPort = 1900;
    private static readonly IPAddress SsdpMulticast = IPAddress.Parse("239.255.255.250");
    private const string AvTransportService = "urn:schemas-upnp-org:service:AVTransport:1";

    private readonly ConcurrentDictionary<string, DlnaRenderer> _renderers =
        new(StringComparer.OrdinalIgnoreCase);
    private CancellationTokenSource? _cts;

    public event Action<DlnaRenderer>? Found;
    public event Action<string>? Lost;

    public IReadOnlyCollection<DlnaRenderer> Snapshot() => _renderers.Values.ToArray();

    public void Start()
    {
        if (_cts != null) return;
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
        // One listener socket bound to any local port — receives unicast
        // M-SEARCH responses AND multicast NOTIFY announces from devices
        // that bring themselves up after we started.
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        udp.Client.SetSocketOption(SocketOptionLevel.Socket,
            SocketOptionName.ReuseAddress, true);
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));
        try
        {
            udp.JoinMulticastGroup(SsdpMulticast);
        }
        catch { /* some networks block multicast — M-SEARCH unicasts still work */ }

        _ = Task.Run(() => ReceiveLoop(udp, ct), ct);

        // Initial burst — three M-SEARCHes 500ms apart to deal with packet
        // loss on noisy WiFi.
        await SendMSearch(udp, ct);
        try { await Task.Delay(500, ct); } catch { return; }
        await SendMSearch(udp, ct);
        try { await Task.Delay(500, ct); } catch { return; }
        await SendMSearch(udp, ct);

        // Then re-probe every 8 s. UPnP devices age their advertisements
        // out of caches after ~30 min, so we want a steady drip to keep
        // the list fresh — and to spot devices that powered on late.
        while (!ct.IsCancellationRequested)
        {
            try { await Task.Delay(8_000, ct); } catch { return; }
            await SendMSearch(udp, ct);
        }
    }

    private static async Task SendMSearch(UdpClient udp, CancellationToken ct)
    {
        var msg =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: " + AvTransportService + "\r\n" +
            "USER-AGENT: WiFiShareTray/1.0 UPnP/1.1\r\n" +
            "\r\n";
        var bytes = Encoding.ASCII.GetBytes(msg);
        try
        {
            await udp.SendAsync(bytes, bytes.Length,
                new IPEndPoint(SsdpMulticast, SsdpPort));
        }
        catch { /* socket may have died — let outer Loop reset on next iteration */ }
    }

    private async Task ReceiveLoop(UdpClient udp, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try { result = await udp.ReceiveAsync(ct); }
            catch { return; }
            await HandlePacket(result, ct);
        }
    }

    private async Task HandlePacket(UdpReceiveResult result, CancellationToken ct)
    {
        var text = Encoding.ASCII.GetString(result.Buffer);
        // Both M-SEARCH responses (HTTP/1.1 200 OK) and NOTIFY announcements
        // (NOTIFY * HTTP/1.1) carry the same set of headers we care about.
        // Crude header parse — full HTTP parser is overkill for SSDP.
        var headers = ParseHeaders(text);
        if (!headers.TryGetValue("LOCATION", out var loc)) return;
        if (!headers.TryGetValue("ST", out var st) &&
            !headers.TryGetValue("NT", out st)) return;
        // We only care about Media Renderers and the AVTransport service.
        // Both come in via the same M-SEARCH because devices answer with
        // every matching service they host.
        if (!st.Contains("MediaRenderer", StringComparison.OrdinalIgnoreCase) &&
            !st.Contains(AvTransportService, StringComparison.OrdinalIgnoreCase)) return;

        await FetchDescription(loc, result.RemoteEndPoint.Address, ct);
    }

    private async Task FetchDescription(string locationUrl, IPAddress address, CancellationToken ct)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };
            using var resp = await http.GetAsync(locationUrl, ct);
            if (!resp.IsSuccessStatusCode) return;
            var xml = await resp.Content.ReadAsStringAsync(ct);
            var renderer = ParseDescription(xml, locationUrl, address);
            if (renderer == null) return;
            var wasNew = _renderers.TryAdd(renderer.Udn, renderer);
            if (wasNew) Found?.Invoke(renderer);
        }
        catch { /* device may have gone offline between announce and fetch */ }
    }

    private static DlnaRenderer? ParseDescription(string xml, string descUrl, IPAddress address)
    {
        try
        {
            var doc = XDocument.Parse(xml);
            var ns = doc.Root?.GetDefaultNamespace() ?? XNamespace.None;
            var device = doc.Root?.Element(ns + "device");
            if (device == null) return null;
            var udn = device.Element(ns + "UDN")?.Value ?? Guid.NewGuid().ToString();
            var friendly = device.Element(ns + "friendlyName")?.Value ?? "Unknown";
            var model = device.Element(ns + "modelName")?.Value ?? "";
            // Find the AVTransport service and its controlURL.
            var services = device.Element(ns + "serviceList")?.Elements(ns + "service");
            if (services == null) return null;
            string? controlPath = null;
            foreach (var s in services)
            {
                var type = s.Element(ns + "serviceType")?.Value ?? "";
                if (type.Contains("AVTransport", StringComparison.OrdinalIgnoreCase))
                {
                    controlPath = s.Element(ns + "controlURL")?.Value;
                    break;
                }
            }
            if (string.IsNullOrEmpty(controlPath)) return null;

            // controlURL can be absolute or relative — resolve against
            // the description URL.
            var baseUri = new Uri(descUrl);
            var control = new Uri(baseUri, controlPath);
            return new DlnaRenderer(udn, friendly, model, control, address);
        }
        catch { return null; }
    }

    private static Dictionary<string, string> ParseHeaders(string text)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        using var sr = new StringReader(text);
        string? line;
        while ((line = sr.ReadLine()) != null)
        {
            var idx = line.IndexOf(':');
            if (idx <= 0) continue;
            var name = line.Substring(0, idx).Trim();
            var value = line.Substring(idx + 1).Trim();
            // Keep the first occurrence — SSDP doesn't repeat headers.
            dict.TryAdd(name, value);
        }
        return dict;
    }
}
