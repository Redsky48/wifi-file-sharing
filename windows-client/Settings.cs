using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WiFiShareTray;

/// <summary>
/// Persistent user settings, stored as JSON at
/// %APPDATA%\WiFiShare\settings.json. Holds the stable client identity
/// (so the phone recognises this PC across runs) and the folder where
/// pushed files land.
/// </summary>
public sealed class Settings
{
    public string ClientId { get; set; } = Guid.NewGuid().ToString();
    public string DeviceName { get; set; } = Environment.MachineName;
    public string DownloadFolder { get; set; } = DefaultDownloadFolder();
    public bool AutoConnect { get; set; } = true;

    /// <summary>
    /// When true, clipboard changes flow both ways automatically:
    ///   - phone clipboard.changed event → Windows clipboard
    ///   - Windows clipboard change → phone /api/clipboard PUT
    /// Default on — that's almost universally what people want when
    /// they install a "share between devices" tool. User can toggle
    /// off via the tray's Clipboard submenu.
    /// </summary>
    public bool ClipboardAutoMirror { get; set; } = true;

    /// <summary>
    /// Per-phone Bearer tokens issued via /api/auth/pair. Key = phone's
    /// mDNS instance name. Value = DPAPI-encrypted token (Base64).
    /// Once we have a token for a phone we prefer Bearer auth and the
    /// PIN is only consulted to re-pair if the token gets revoked.
    /// </summary>
    public Dictionary<string, string> KnownDeviceTokens { get; set; } = new();

    /// <summary>
    /// Saved phone passwords. Key = phone's mDNS instance name (stable
    /// per-phone identity). Value = DPAPI-encrypted password (Base64).
    /// Encrypts under CurrentUser scope so other users on the same PC
    /// can't read it.
    /// </summary>
    public Dictionary<string, string> KnownDevices { get; set; } = new();

    [JsonIgnore]
    public string FilePath { get; private set; } = "";

    public static string DefaultDownloadFolder()
    {
        // Windows "Known Folder" Downloads — Environment doesn't expose it
        // directly; build from UserProfile + "Downloads" which is correct
        // on every English/Latvian Windows install.
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads");
    }

    private static string ConfigPath()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "WiFiShare");
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, "settings.json");
    }

    public static Settings Load()
    {
        var path = ConfigPath();
        Settings s;
        if (File.Exists(path))
        {
            try
            {
                var json = File.ReadAllText(path);
                s = JsonSerializer.Deserialize<Settings>(json) ?? new Settings();
            }
            catch
            {
                s = new Settings();
            }
        }
        else
        {
            s = new Settings();
        }
        s.FilePath = path;
        if (!Directory.Exists(s.DownloadFolder))
            s.DownloadFolder = DefaultDownloadFolder();
        return s;
    }

    public void Save()
    {
        try
        {
            File.WriteAllText(
                FilePath.Length > 0 ? FilePath : ConfigPath(),
                JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* best-effort */ }
    }

    public string? GetPassword(string deviceKey)
    {
        if (string.IsNullOrEmpty(deviceKey)) return null;
        if (!KnownDevices.TryGetValue(deviceKey, out var enc)) return null;
        try
        {
            var bytes = Convert.FromBase64String(enc);
            var clear = ProtectedData.Unprotect(bytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(clear);
        }
        catch { return null; }
    }

    public void SavePassword(string deviceKey, string password)
    {
        if (string.IsNullOrEmpty(deviceKey)) return;
        try
        {
            var enc = ProtectedData.Protect(
                Encoding.UTF8.GetBytes(password ?? ""),
                null,
                DataProtectionScope.CurrentUser);
            KnownDevices[deviceKey] = Convert.ToBase64String(enc);
            Save();
        }
        catch { /* best-effort */ }
    }

    public void ForgetDevice(string deviceKey)
    {
        if (KnownDevices.Remove(deviceKey)) Save();
    }
}
