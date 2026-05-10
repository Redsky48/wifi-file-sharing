using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// Polls the phone over HTTP to detect when it disappears
/// (out of WiFi range, server stopped, screen-off battery saver, etc).
/// Fires <see cref="Lost"/> after <see cref="MissesUntilLost"/> failed pings.
/// </summary>
public sealed class HealthChecker : IDisposable
{
    private const int IntervalMs = 5000;
    private const int MissesUntilLost = 5;

    private HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(8) };

    private CancellationTokenSource? _cts;

    public event Action? Lost;

    public void Start(string baseUrl, string? password)
    {
        Stop();
        _http.Dispose();
        _http = AuthHttp.Build(new Uri(baseUrl), password, TimeSpan.FromSeconds(8));
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(baseUrl, _cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
    }

    private async Task Loop(string baseUrl, CancellationToken ct)
    {
        var probeUrl = baseUrl.TrimEnd('/') + "/api/files";
        var misses = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var resp = await _http.GetAsync(probeUrl, ct);
                // Server proactively told us it's shutting down — bail now.
                if ((int)resp.StatusCode == 503 ||
                    resp.Headers.TryGetValues("X-WiFiShare-Shutdown", out _))
                {
                    try { Lost?.Invoke(); } catch { /* ignore */ }
                    return;
                }
                if (resp.IsSuccessStatusCode)
                {
                    misses = 0;
                }
                else
                {
                    misses++;
                }
            }
            catch
            {
                misses++;
            }

            if (misses >= MissesUntilLost)
            {
                try { Lost?.Invoke(); } catch { /* ignore */ }
                return;
            }

            try { await Task.Delay(IntervalMs, ct); } catch { return; }
        }
    }

    public void Dispose()
    {
        Stop();
        _http.Dispose();
    }
}
