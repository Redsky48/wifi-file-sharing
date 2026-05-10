using System;
using System.IO;
using Microsoft.Win32;

namespace WiFiShareTray;

/// <summary>
/// Self-installer for the tray app: copies the running .exe into a
/// stable per-user location and toggles the auto-start registry entry.
/// All operations are user-scope (no admin needed).
/// </summary>
internal static class Installer
{
    private const string RunKeyName = "WiFiShareTray";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static string InstallDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "WiFiShare");

    public static string InstallExePath => Path.Combine(InstallDir, "WiFiShareTray.exe");

    public static bool IsInstalled => File.Exists(InstallExePath);

    public static string? CurrentExePath =>
        Environment.ProcessPath?.Length > 0 ? Path.GetFullPath(Environment.ProcessPath) : null;

    public static bool IsRunningFromInstallDir =>
        CurrentExePath != null &&
        string.Equals(CurrentExePath, InstallExePath, StringComparison.OrdinalIgnoreCase);

    public static bool IsAutoStartEnabled
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
                return key?.GetValue(RunKeyName) != null;
            }
            catch { return false; }
        }
    }

    /// <summary>Copy the running exe into <see cref="InstallDir"/> and turn on auto-start.</summary>
    public static void Install()
    {
        Directory.CreateDirectory(InstallDir);
        var src = CurrentExePath ?? throw new InvalidOperationException("Cannot determine running exe path.");
        if (!string.Equals(src, InstallExePath, StringComparison.OrdinalIgnoreCase))
        {
            File.Copy(src, InstallExePath, overwrite: true);
        }
        SetAutoStart(true);
    }

    /// <summary>Remove the auto-start key and try to delete the install dir.</summary>
    public static void Uninstall()
    {
        SetAutoStart(false);
        try { File.Delete(InstallExePath); } catch { /* may be in use */ }
        try { Directory.Delete(InstallDir, recursive: true); } catch { /* may not be empty */ }
    }

    public static void SetAutoStart(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
            if (key == null) return;
            if (enabled)
            {
                var target = IsInstalled ? InstallExePath : CurrentExePath;
                if (!string.IsNullOrEmpty(target))
                    key.SetValue(RunKeyName, $"\"{target}\"", RegistryValueKind.String);
            }
            else
            {
                key.DeleteValue(RunKeyName, throwOnMissingValue: false);
            }
        }
        catch { /* best-effort */ }
    }
}
