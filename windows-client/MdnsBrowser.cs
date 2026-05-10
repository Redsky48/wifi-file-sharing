using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading.Tasks;
using Makaretu.Dns;

namespace WiFiShareTray;

public sealed record DiscoveredService(
    string Name,
    IPAddress Address,
    ushort Port,
    bool AuthRequired)
{
    public string Url => $"http://{Address}:{Port}";
}

public sealed class MdnsBrowser : IDisposable
{
    private const string ServiceType = "_wifishare._tcp";

    private readonly ServiceDiscovery _sd;
    private readonly object _lock = new();
    private readonly Dictionary<string, DiscoveredService> _seen = new(StringComparer.OrdinalIgnoreCase);

    public event Action<DiscoveredService>? Found;
    public event Action<string>? Lost;

    public MdnsBrowser()
    {
        _sd = new ServiceDiscovery();
        _sd.ServiceInstanceDiscovered += OnInstanceDiscovered;
        _sd.ServiceInstanceShutdown += OnInstanceShutdown;
    }

    public void Start()
    {
        _sd.QueryServiceInstances(ServiceType);
    }

    public IReadOnlyCollection<DiscoveredService> Snapshot()
    {
        lock (_lock) return _seen.Values.ToList();
    }

    private void OnInstanceDiscovered(object? sender, ServiceInstanceDiscoveryEventArgs e)
    {
        try
        {
            // Resolve SRV + A records
            var name = e.ServiceInstanceName.ToString();
            var msg = e.Message;
            var srv = msg.AdditionalRecords.OfType<SRVRecord>().FirstOrDefault()
                      ?? msg.Answers.OfType<SRVRecord>().FirstOrDefault();
            if (srv == null) return;

            var aRecords = msg.AdditionalRecords.OfType<ARecord>()
                .Concat(msg.Answers.OfType<ARecord>())
                .ToList();
            var address = aRecords.FirstOrDefault()?.Address;
            if (address == null) return;

            // Defensive TXT parsing — null Strings or unexpected types must
            // not abort the whole discovery; otherwise the device never
            // shows up and the tray stays stuck on "Scanning…".
            bool authRequired = false;
            try
            {
                var txtRecords = msg.AdditionalRecords.OfType<TXTRecord>()
                    .Concat(msg.Answers.OfType<TXTRecord>());
                foreach (var rec in txtRecords)
                {
                    var strings = rec?.Strings;
                    if (strings == null) continue;
                    foreach (var s in strings)
                    {
                        if (string.IsNullOrEmpty(s)) continue;
                        if (s.StartsWith("auth=", StringComparison.OrdinalIgnoreCase) &&
                            s.EndsWith("required", StringComparison.OrdinalIgnoreCase))
                        {
                            authRequired = true;
                            break;
                        }
                    }
                    if (authRequired) break;
                }
            }
            catch { /* TXT parsing failures must not block discovery */ }

            // Dedupe primarily by network endpoint — the same phone may
            // announce under multiple service names but we only want to
            // surface one entry per IP:port.
            var endpointKey = $"{address}:{srv.Port}";
            var svc = new DiscoveredService(name, address, srv.Port, authRequired);
            DiscoveredService? newOne = null;
            lock (_lock)
            {
                if (!_seen.ContainsKey(endpointKey))
                {
                    _seen[endpointKey] = svc;
                    newOne = svc;
                }
            }
            if (newOne != null) Found?.Invoke(newOne);
        }
        catch
        {
            // Discovery is best-effort
        }
    }

    private void OnInstanceShutdown(object? sender, ServiceInstanceShutdownEventArgs e)
    {
        var name = e.ServiceInstanceName.ToString();
        // Find the (single) endpoint key matching this name — best effort
        string? toRemove = null;
        lock (_lock)
        {
            foreach (var (key, svc) in _seen)
            {
                if (string.Equals(svc.Name, name, StringComparison.OrdinalIgnoreCase))
                {
                    toRemove = key;
                    break;
                }
            }
            if (toRemove != null) _seen.Remove(toRemove);
        }
        if (toRemove != null) Lost?.Invoke(name);
    }

    public void Dispose()
    {
        _sd.Dispose();
    }
}
