using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Fsp;

namespace WiFiShareTray;

public enum MountKind { None, WebDav, WinFsp }

/// <summary>
/// Mounts the phone's shared folder as a Windows drive using whichever
/// backend is available:
///   1. WinFSP — looks like a real local disk; no 50 MB cap; works with
///      MPC-HC, Photoshop, etc. Requires WinFSP installed on the PC.
///   2. WebDAV via "net use" — built into every Windows install but has
///      compatibility quirks with Win32-classic apps.
///
/// The strategy is chosen automatically; <see cref="CurrentKind"/> reports
/// which one ended up running.
/// </summary>
public sealed class Mounter
{
    public string? CurrentDrive { get; private set; }
    public string? CurrentUrl { get; private set; }
    public string? CurrentLabel { get; private set; }
    public MountKind CurrentKind { get; private set; } = MountKind.None;

    private FileSystemHost? _winFspHost;

    public async Task<MountResult> MountAsync(string url, string deviceLabel, string? password)
    {
        if (CurrentDrive != null) await UnmountAsync();

        // Prefer WinFSP — no MS WebDAV quirks, no 50 MB cap, faster.
        // If the user has WinFSP installed, we trust it: don't silently
        // fall back to the kludgier WebDAV path on failure (that just
        // hides the real error).
        if (WinFspManager.IsInstalled() && WinFspManager.EnsureDllPath())
        {
            return TryMountWinFsp(url, deviceLabel, password);
        }

        // No WinFSP — fall back to Windows' built-in WebDAV mini-redirector.
        var result = await MountWebDavAsync(url, deviceLabel, password);
        if (!result.Success && IsWebClientStoppedError(result.Error))
        {
            // Error 67 / "network name cannot be found" → WebClient
            // service usually disabled. Offer to start it (UAC prompt)
            // and retry once.
            if (await TryStartWebClientAsync())
            {
                await Task.Delay(750);
                result = await MountWebDavAsync(url, deviceLabel, password);
            }
        }
        return result;
    }

    private static bool IsWebClientStoppedError(string? err)
    {
        if (string.IsNullOrEmpty(err)) return false;
        return err.Contains("error 67", StringComparison.OrdinalIgnoreCase)
            || err.Contains("network name cannot be found", StringComparison.OrdinalIgnoreCase)
            || err.Contains("1920", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Enable + start the Windows WebClient service. Requires elevation —
    /// shows a UAC prompt. Returns true if the service ended up running.
    /// </summary>
    private static async Task<bool> TryStartWebClientAsync()
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -WindowStyle Hidden -Command " +
                    "\"try { Set-Service WebClient -StartupType Manual -ErrorAction Stop; " +
                    "Start-Service WebClient -ErrorAction Stop; exit 0 } catch { exit 1 }\"",
                UseShellExecute = true,
                Verb = "runas",
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            await p.WaitForExitAsync();
            return p.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }

    public async Task UnmountAsync()
    {
        var kind = CurrentKind;
        var letter = CurrentDrive;
        CurrentDrive = null;
        CurrentUrl = null;
        CurrentLabel = null;
        CurrentKind = MountKind.None;

        switch (kind)
        {
            case MountKind.WinFsp:
                try { _winFspHost?.Unmount(); } catch { /* ignore */ }
                try { _winFspHost?.Dispose(); } catch { /* ignore */ }
                _winFspHost = null;
                break;
            case MountKind.WebDav:
                if (letter != null) await RunNetUseAsync($"{letter}: /delete /yes");
                break;
        }
    }

    public void OpenInExplorer()
    {
        if (CurrentDrive == null) return;
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"{CurrentDrive}:\\",
                UseShellExecute = true,
            });
        }
        catch { /* best-effort */ }
    }

    // -------- WinFSP -----------------------------------------------------

    private MountResult TryMountWinFsp(string url, string deviceLabel, string? password)
    {
        var letter = PickFreeDriveLetter();
        if (letter == null) return MountResult.Fail("No free drive letter");

        FileSystemHost? host = null;
        try
        {
            var fs = new WiFiShareFs(url, deviceLabel, password);
            host = new FileSystemHost(fs);
            int err = host.Mount(letter + ":");
            if (err < 0)
            {
                host.Dispose();
                return MountResult.Fail($"WinFSP mount failed (NTSTATUS 0x{err:X8})");
            }

            _winFspHost = host;
            CurrentDrive = letter;
            CurrentUrl = url;
            CurrentLabel = deviceLabel;
            CurrentKind = MountKind.WinFsp;
            return MountResult.Ok(letter, MountKind.WinFsp);
        }
        catch (DllNotFoundException ex)
        {
            host?.Dispose();
            return MountResult.Fail($"WinFSP DLL load failed: {ex.Message}\n" +
                $"WinFspManager: {WinFspManager.LastError ?? "OK"}");
        }
        catch (Exception ex)
        {
            host?.Dispose();
            // Surface the inner exception too — TypeInitializationException
            // hides the actual cause (e.g. DllNotFoundException) under .Message.
            var inner = ex.InnerException;
            var msg = inner != null ? $"{ex.Message} → {inner.Message}" : ex.Message;
            return MountResult.Fail($"WinFSP error: {msg}\n" +
                $"WinFspManager: {WinFspManager.LastError ?? "OK"}");
        }
    }

    // -------- WebDAV -----------------------------------------------------

    private async Task<MountResult> MountWebDavAsync(string url, string deviceLabel, string? password)
    {
        var davUrl = url.TrimEnd('/') + "/dav";
        var letter = PickFreeDriveLetter();
        if (letter == null) return MountResult.Fail("No free drive letter");

        // net use Z: <url> [<password>] [/user:<user>] /persistent:no
        var args = string.IsNullOrEmpty(password)
            ? $"{letter}: \"{davUrl}\" /persistent:no"
            : $"{letter}: \"{davUrl}\" \"{Escape(password)}\" /user:user /persistent:no";

        var (code, output) = await RunNetUseAsync(args);
        if (code != 0)
        {
            var hint = output.Contains("1920", StringComparison.Ordinal) ||
                       output.Contains("WebClient", StringComparison.OrdinalIgnoreCase)
                ? " (try: services.msc → start the WebClient service)"
                : output.Contains("1244", StringComparison.Ordinal) ||
                  output.Contains("login failure", StringComparison.OrdinalIgnoreCase) ||
                  output.Contains("password", StringComparison.OrdinalIgnoreCase)
                ? " (wrong password — re-enter it in Settings)"
                : "";
            return MountResult.Fail($"net use failed (code {code}){hint}\n{output.Trim()}");
        }

        CurrentDrive = letter;
        CurrentUrl = url;
        CurrentLabel = deviceLabel;
        CurrentKind = MountKind.WebDav;
        return MountResult.Ok(letter, MountKind.WebDav);
    }

    // -------- shared -----------------------------------------------------

    private static string? PickFreeDriveLetter()
    {
        var taken = DriveInfo.GetDrives()
            .Select(d => char.ToUpperInvariant(d.Name[0]))
            .ToHashSet();
        for (var c = 'Z'; c >= 'D'; c--)
        {
            if (!taken.Contains(c)) return c.ToString();
        }
        return null;
    }

    private static string Escape(string s) => s.Replace("\"", "\\\"");

    private static async Task<(int code, string output)> RunNetUseAsync(string args)
    {
        var psi = new ProcessStartInfo
        {
            FileName = "net.exe",
            Arguments = "use " + args,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var p = Process.Start(psi);
        if (p == null) return (-1, "Failed to launch net.exe");
        var stdoutTask = p.StandardOutput.ReadToEndAsync();
        var stderrTask = p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        return (p.ExitCode, (await stdoutTask) + (await stderrTask));
    }
}

public sealed record MountResult(bool Success, string? DriveLetter, string? Error, MountKind Kind)
{
    public static MountResult Ok(string letter, MountKind kind) => new(true, letter, null, kind);
    public static MountResult Fail(string error) => new(false, null, error, MountKind.None);
}
