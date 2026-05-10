using System;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

public sealed class ReceivedFile
{
    public string Name { get; init; } = "";
    public string Path { get; init; } = "";
    public long Size { get; init; }
}

/// <summary>
/// Registers this PC with the phone (so it shows up in the phone's
/// "Connected" list with a friendly name) and polls
/// /api/queue/{clientId} for files the phone wants to push. Saves
/// pending files to the configured download folder.
/// </summary>
public sealed class QueuePoller : IDisposable
{
    private const int PollIntervalMs = 4000;

    private readonly Settings _settings;
    private HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(30) };

    private CancellationTokenSource? _cts;
    private string? _baseUrl;

    public event Action<ReceivedFile>? FileReceived;

    public QueuePoller(Settings settings)
    {
        _settings = settings;
    }

    public void Start(string baseUrl, string? password)
    {
        Stop();
        _baseUrl = baseUrl.TrimEnd('/');
        _http.Dispose();
        _http = AuthHttp.Build(new Uri(_baseUrl), password, TimeSpan.FromSeconds(30));
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
    }

    private async Task Loop(CancellationToken ct)
    {
        if (_baseUrl == null) return;
        var registered = false;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!registered)
                {
                    registered = await TryRegister(ct);
                }
                else
                {
                    await PollOnce(ct);
                }
            }
            catch (OperationCanceledException) { return; }
            catch
            {
                // Network blip — keep trying. Re-registration will happen
                // again the next time PollOnce can't reach the server.
            }

            try { await Task.Delay(PollIntervalMs, ct); } catch { return; }
        }
    }

    private async Task<bool> TryRegister(CancellationToken ct)
    {
        var body = JsonSerializer.Serialize(new RegisterReq
        {
            ClientId = _settings.ClientId,
            Name = _settings.DeviceName,
        });
        using var content = new StringContent(body, System.Text.Encoding.UTF8, "application/json");
        using var resp = await _http.PostAsync($"{_baseUrl}/api/clients/register", content, ct);
        return resp.IsSuccessStatusCode;
    }

    private async Task PollOnce(CancellationToken ct)
    {
        var queueUrl = $"{_baseUrl}/api/queue/{Uri.EscapeDataString(_settings.ClientId)}";
        using var resp = await _http.GetAsync(queueUrl, ct);
        if (resp.StatusCode == System.Net.HttpStatusCode.NoContent) return;
        if (!resp.IsSuccessStatusCode) return;

        var meta = await resp.Content.ReadFromJsonAsync<QueueItemMeta>(cancellationToken: ct);
        if (meta?.Id == null) return;

        await DownloadAndSave(meta, queueUrl, ct);
    }

    private async Task DownloadAndSave(QueueItemMeta meta, string queueUrl, CancellationToken ct)
    {
        Directory.CreateDirectory(_settings.DownloadFolder);
        var safeName = SanitizeFileName(meta.Name ?? $"file-{meta.Id}");
        var targetPath = UniquePath(Path.Combine(_settings.DownloadFolder, safeName));

        using (var resp = await _http.GetAsync($"{queueUrl}/{meta.Id}",
            HttpCompletionOption.ResponseHeadersRead, ct))
        {
            if (!resp.IsSuccessStatusCode) return;
            await using var src = await resp.Content.ReadAsStreamAsync(ct);
            await using var dst = File.Create(targetPath);
            await src.CopyToAsync(dst, ct);
        }

        // Acknowledge to remove from server queue
        try
        {
            using var del = new HttpRequestMessage(HttpMethod.Delete, $"{queueUrl}/{meta.Id}");
            await _http.SendAsync(del, ct);
        }
        catch { /* best-effort */ }

        FileReceived?.Invoke(new ReceivedFile
        {
            Name = safeName,
            Path = targetPath,
            Size = new FileInfo(targetPath).Length,
        });
    }

    private static string SanitizeFileName(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars())
            name = name.Replace(c, '_');
        return string.IsNullOrWhiteSpace(name) ? "file" : name;
    }

    private static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var stem = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (int i = 1; ; i++)
        {
            var candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
    }

    public void Dispose()
    {
        Stop();
        _http.Dispose();
    }

    private sealed class RegisterReq
    {
        [JsonPropertyName("clientId")] public string ClientId { get; set; } = "";
        [JsonPropertyName("name")] public string Name { get; set; } = "";
    }

    private sealed class QueueItemMeta
    {
        [JsonPropertyName("id")] public string Id { get; set; } = "";
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("size")] public long Size { get; set; }
        [JsonPropertyName("mime")] public string? Mime { get; set; }
        [JsonPropertyName("pending")] public int Pending { get; set; }
    }
}
