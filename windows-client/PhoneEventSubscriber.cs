using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// Long-running Server-Sent Events client for the phone's /api/events
/// endpoint. Parses the `data: <json>\n\n` framing and fires
/// [EventReceived] for every envelope.
///
/// Auto-reconnects on stream end / network error after a back-off, so
/// the tray keeps catching events across short phone reboots.
/// </summary>
public sealed class PhoneEventSubscriber : IDisposable
{
    private readonly Settings _settings;
    private HttpClient _http = new();
    private CancellationTokenSource? _cts;
    private string? _baseUrl;
    private string? _password;

    public event Action<PhoneEvent>? EventReceived;

    public PhoneEventSubscriber(Settings settings)
    {
        _settings = settings;
    }

    public void Start(string baseUrl, string? password, string? bearerToken = null)
    {
        Stop();
        _baseUrl = baseUrl.TrimEnd('/');
        _password = password;
        _http.Dispose();
        // Infinite read timeout — SSE connections stay open indefinitely.
        // We rely on the phone's 20-second keepalive ping to detect dead
        // sockets at the TCP level.
        _http = AuthHttp.Build(new Uri(_baseUrl), password, Timeout.InfiniteTimeSpan, bearerToken);
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        _cts = null;
    }

    private async Task Loop(CancellationToken ct)
    {
        var backoffMs = 1000;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var req = new HttpRequestMessage(HttpMethod.Get, "/api/events");
                req.Headers.Accept.ParseAdd("text/event-stream");
                using var resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
                resp.EnsureSuccessStatusCode();

                await using var stream = await resp.Content.ReadAsStreamAsync(ct);
                using var reader = new StreamReader(stream);

                backoffMs = 1000; // reset on first successful read
                var data = new System.Text.StringBuilder();
                while (!ct.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync().WaitAsync(ct);
                    if (line == null) break; // server closed the stream

                    // Comments (heartbeat lines like ": ping 1234") start with ':'
                    if (line.StartsWith(':')) continue;

                    if (line.Length == 0)
                    {
                        if (data.Length > 0)
                        {
                            DispatchFrame(data.ToString());
                            data.Clear();
                        }
                        continue;
                    }

                    if (line.StartsWith("data:", StringComparison.Ordinal))
                    {
                        var payload = line.Substring(5).TrimStart(' ');
                        if (data.Length > 0) data.Append('\n');
                        data.Append(payload);
                    }
                    // We ignore `event:` and `id:` lines — phone never emits them.
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch
            {
                // Treat any failure as a disconnect — back off and retry.
            }

            try { await Task.Delay(backoffMs, ct); } catch { return; }
            // Exponential up to 30s; phone may be down for a while during install.
            backoffMs = Math.Min(backoffMs * 2, 30_000);
        }
    }

    private void DispatchFrame(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            // The phone wraps events as { type, ts, seq, data }
            var type = root.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
            long ts = root.TryGetProperty("ts", out var tsEl) && tsEl.TryGetInt64(out var tsv) ? tsv : 0L;
            long seq = root.TryGetProperty("seq", out var seqEl) && seqEl.TryGetInt64(out var sv) ? sv : 0L;
            string? rawData = root.TryGetProperty("data", out var d) ? d.GetRawText() : null;
            if (string.IsNullOrEmpty(type)) return;
            EventReceived?.Invoke(new PhoneEvent(type, rawData, ts, seq));
        }
        catch
        {
            // Skip malformed frames; the next one will arrive shortly.
        }
    }

    public void Dispose()
    {
        Stop();
        try { _http.Dispose(); } catch { }
    }
}

public sealed record PhoneEvent(string Type, string? DataJson, long Ts, long Seq)
{
    /// <summary>
    /// Attempts to read a string field out of the event's data payload.
    /// Returns null if the field is missing or the data was malformed.
    /// </summary>
    public string? GetString(string field)
    {
        if (string.IsNullOrEmpty(DataJson)) return null;
        try
        {
            using var doc = JsonDocument.Parse(DataJson);
            if (doc.RootElement.TryGetProperty(field, out var v) && v.ValueKind == JsonValueKind.String)
                return v.GetString();
            return null;
        }
        catch { return null; }
    }

    public int? GetInt(string field)
    {
        if (string.IsNullOrEmpty(DataJson)) return null;
        try
        {
            using var doc = JsonDocument.Parse(DataJson);
            if (doc.RootElement.TryGetProperty(field, out var v) && v.TryGetInt32(out var i))
                return i;
            return null;
        }
        catch { return null; }
    }
}
