using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

namespace WiFiShareTray;

/// <summary>
/// Registers / unregisters WiFi Share as a Windows 11 share target via a
/// sparse-package manifest. The manifest + helper scripts are embedded
/// resources in the .exe (single-file friendly) — they get extracted to
/// %TEMP% on demand and PowerShell is invoked from there.
/// </summary>
internal static class WindowsShareTarget
{
    private const string PackageName = "WiFiShare.Tray";

    public static bool IsRegistered
    {
        get
        {
            try
            {
                var (code, output) = RunPwsh(
                    $"if (Get-AppxPackage -Name {PackageName} -ErrorAction SilentlyContinue) {{ 'yes' }} else {{ 'no' }}");
                return code == 0 && output.Trim() == "yes";
            }
            catch { return false; }
        }
    }

    /// <summary>
    /// True if the user has flipped on Developer Mode (Settings → System →
    /// For developers). Required before sparse-package registration.
    /// </summary>
    public static bool IsDeveloperModeEnabled()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock");
            var value = key?.GetValue("AllowDevelopmentWithoutDevLicense");
            return value is int i && i == 1;
        }
        catch { return false; }
    }

    public static void OpenDeveloperSettings()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "ms-settings:developers",
                UseShellExecute = true,
            });
        }
        catch { /* ignore */ }
    }

    public static (bool Success, string Message) Register()
    {
        try
        {
            var stagingDir = ExtractScripts();
            var script = Path.Combine(stagingDir, "Register-ShareTarget.ps1");
            var (code, output) = RunPwshFile(script);
            return (code == 0, output);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    public static (bool Success, string Message) Unregister()
    {
        try
        {
            var (code, output) = RunPwsh(
                $"Get-AppxPackage {PackageName} -ErrorAction SilentlyContinue | Remove-AppxPackage");
            return (code == 0, string.IsNullOrWhiteSpace(output) ? "Removed" : output);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>
    /// Extract embedded manifest + scripts to a fresh temp folder. Returns
    /// the folder so the registration script can find AppxManifest.xml in
    /// its own ..\Package directory.
    /// </summary>
    private static string ExtractScripts()
    {
        var dir = Path.Combine(Path.GetTempPath(), "WiFiShare-SparsePkg-" + Guid.NewGuid().ToString("N").Substring(0, 8));
        Directory.CreateDirectory(dir);
        Directory.CreateDirectory(Path.Combine(dir, "Package"));
        Directory.CreateDirectory(Path.Combine(dir, "scripts"));

        WriteEmbedded("AppxManifest.xml",            Path.Combine(dir, "Package", "AppxManifest.xml"));
        WriteEmbedded("Register-ShareTarget.ps1",    Path.Combine(dir, "scripts", "Register-ShareTarget.ps1"));
        WriteEmbedded("Unregister-ShareTarget.ps1",  Path.Combine(dir, "scripts", "Unregister-ShareTarget.ps1"));

        return Path.Combine(dir, "scripts");
    }

    private static void WriteEmbedded(string resourceName, string destPath)
    {
        using var src = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new FileNotFoundException("Embedded resource missing: " + resourceName);

        // Buffer the resource so we can prepend a UTF-8 BOM if missing.
        // Windows PowerShell 5.1 falls back to ANSI when there's no BOM,
        // mangling any non-ASCII bytes into garbage that breaks parsing.
        using var ms = new MemoryStream();
        src.CopyTo(ms);
        var bytes = ms.ToArray();

        var hasBom = bytes.Length >= 3 &&
            bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;

        using var dst = File.Create(destPath);
        if (!hasBom && IsTextResource(resourceName))
        {
            dst.Write(new byte[] { 0xEF, 0xBB, 0xBF }, 0, 3);
        }
        dst.Write(bytes, 0, bytes.Length);
    }

    private static bool IsTextResource(string name) =>
        name.EndsWith(".ps1", StringComparison.OrdinalIgnoreCase) ||
        name.EndsWith(".xml", StringComparison.OrdinalIgnoreCase);

    private static (int code, string output) RunPwshFile(string scriptPath)
    {
        // We pass -ExePath of the running .exe so the script registers
        // against the right binary regardless of where it lives.
        var exe = Environment.ProcessPath ?? "";
        return RunPwsh($"& '{scriptPath.Replace("'", "''")}' -ExePath '{exe.Replace("'", "''")}'");
    }

    private static (int code, string output) RunPwsh(string script)
    {
        var psi = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = "-NoProfile -ExecutionPolicy Bypass -Command " + Quote(script),
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        using var p = Process.Start(psi);
        if (p == null) return (-1, "Failed to launch PowerShell.");
        var stdout = p.StandardOutput.ReadToEnd();
        var stderr = p.StandardError.ReadToEnd();
        p.WaitForExit();
        var combined = string.IsNullOrWhiteSpace(stderr) ? stdout : stdout + "\n" + stderr;
        return (p.ExitCode, combined);
    }

    private static string Quote(string s) => "\"" + s.Replace("\"", "`\"") + "\"";
}
