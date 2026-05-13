using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WiFiShareTray;

public sealed class PendingUpload
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>Stable mDNS instance name of the target phone.</summary>
    [JsonPropertyName("targetDeviceName")]
    public string TargetDeviceName { get; set; } = "";

    /// <summary>Friendly name shown in the menu, e.g. "Pixel 9 Pro".</summary>
    [JsonPropertyName("targetDisplayName")]
    public string TargetDisplayName { get; set; } = "";

    [JsonPropertyName("filePath")]
    public string FilePath { get; set; } = "";

    [JsonPropertyName("createdAt")]
    public long CreatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    [JsonPropertyName("attempts")]
    public int Attempts { get; set; } = 0;

    [JsonPropertyName("lastError")]
    public string? LastError { get; set; }
}

/// <summary>
/// Persistent queue of file-uploads waiting for a specific phone to come
/// online. Backed by JSON in %APPDATA%\WiFiShare so entries survive
/// tray-app restarts. Files that have since been deleted from the local
/// disk are auto-pruned on load.
/// </summary>
public sealed class PendingUploads
{
    public event Action? Changed;

    private readonly object _lock = new();
    private readonly string _path;
    private List<PendingUpload> _items = new();

    public PendingUploads()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "WiFiShare");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "pending-uploads.json");
        Load();
    }

    public int Count { get { lock (_lock) return _items.Count; } }

    public List<PendingUpload> Snapshot()
    {
        lock (_lock) return _items.ToList();
    }

    public List<PendingUpload> SnapshotForDevice(string deviceName)
    {
        lock (_lock)
        {
            return _items
                .Where(p => string.Equals(p.TargetDeviceName, deviceName, StringComparison.OrdinalIgnoreCase))
                .ToList();
        }
    }

    public PendingUpload Add(
        string targetDeviceName,
        string targetDisplayName,
        string filePath,
        string? lastError = null)
    {
        var p = new PendingUpload
        {
            TargetDeviceName = targetDeviceName,
            TargetDisplayName = targetDisplayName,
            FilePath = filePath,
            LastError = lastError,
        };
        lock (_lock) _items.Add(p);
        Save();
        Changed?.Invoke();
        return p;
    }

    public bool Remove(string id)
    {
        bool removed;
        lock (_lock) removed = _items.RemoveAll(p => p.Id == id) > 0;
        if (removed) { Save(); Changed?.Invoke(); }
        return removed;
    }

    public void RemoveMany(IEnumerable<string> ids)
    {
        var set = new HashSet<string>(ids);
        bool any;
        lock (_lock) any = _items.RemoveAll(p => set.Contains(p.Id)) > 0;
        if (any) { Save(); Changed?.Invoke(); }
    }

    public void Clear()
    {
        bool any;
        lock (_lock) { any = _items.Count > 0; _items.Clear(); }
        if (any) { Save(); Changed?.Invoke(); }
    }

    public void RecordFailure(string id, string? error)
    {
        lock (_lock)
        {
            var p = _items.FirstOrDefault(x => x.Id == id);
            if (p == null) return;
            p.Attempts++;
            p.LastError = error;
        }
        Save();
        Changed?.Invoke();
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_path)) return;
            var json = File.ReadAllText(_path);
            var loaded = JsonSerializer.Deserialize<List<PendingUpload>>(json) ?? new();
            lock (_lock)
            {
                // Drop entries whose source file vanished while we were offline.
                _items = loaded.Where(p => File.Exists(p.FilePath)).ToList();
            }
        }
        catch
        {
            // Corrupt state — start fresh, don't crash the tray.
            lock (_lock) _items = new List<PendingUpload>();
        }
    }

    private void Save()
    {
        try
        {
            List<PendingUpload> snap;
            lock (_lock) snap = _items.ToList();
            File.WriteAllText(
                _path,
                JsonSerializer.Serialize(snap, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* best-effort */ }
    }
}
