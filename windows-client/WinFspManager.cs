using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace WiFiShareTray;

/// <summary>
/// Detects whether WinFSP is installed and prepares the process so that
/// the managed wrapper (winfsp-msil.dll) can locate the native winfsp-x64.dll
/// at runtime. WinFSP's installer adds its bin folder to PATH, but we
/// can't rely on that being inherited by long-running processes — so we
/// proactively call SetDllDirectory before any P/Invoke happens.
/// </summary>
internal static class WinFspManager
{
    public static string DownloadUrl => "https://winfsp.dev/rel/";

    private static bool _initialized;

    public static bool IsInstalled() => DetectInstallPath() != null;

    public static string? DetectInstallPath()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WOW6432Node\WinFsp")
                          ?? Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WinFsp");
            if (key?.GetValue("InstallDir") is string p && Directory.Exists(p))
                return p.TrimEnd('\\');
        }
        catch { /* ignore */ }

        var defaultX86 = @"C:\Program Files (x86)\WinFsp";
        if (Directory.Exists(Path.Combine(defaultX86, "bin"))) return defaultX86;

        var defaultX64 = @"C:\Program Files\WinFsp";
        if (Directory.Exists(Path.Combine(defaultX64, "bin"))) return defaultX64;

        return null;
    }

    /// <summary>
    /// Make every reasonable attempt to ensure that .NET's P/Invoke can
    /// later resolve "winfsp-x64.dll" via DllImport. Single-file publish
    /// makes this fiddly: the .exe is unpacked to a temp folder, .NET
    /// probes from there first, and the type initializer of
    /// Fsp.Interop.Api fails before SetDllDirectory has any effect.
    ///
    /// We belt-and-suspenders this:
    ///   1. Prepend WinFSP's bin to PATH (covers fallback search).
    ///   2. SetDefaultDllDirectories + AddDllDirectory (modern API, .NET 6+
    ///      uses these for P/Invoke).
    ///   3. SetDllDirectory (legacy).
    ///   4. NativeLibrary.Load with the full path — registers the module
    ///      under the short name in the loader cache, so subsequent
    ///      LoadLibrary("winfsp-x64.dll") resolves to it instantly.
    /// </summary>
    public static bool EnsureDllPath()
    {
        if (_initialized) return true;
        LastError = null;

        var root = DetectInstallPath();
        if (root == null) { LastError = "WinFSP install dir not found"; return false; }
        var bin = Path.Combine(root, "bin");
        if (!Directory.Exists(bin)) { LastError = $"Missing folder: {bin}"; return false; }

        var dllName = Environment.Is64BitProcess ? "winfsp-x64.dll" : "winfsp-x86.dll";
        var nativeDll = Path.Combine(bin, dllName);
        if (!File.Exists(nativeDll)) { LastError = $"Missing DLL: {nativeDll}"; return false; }

        // 1. Register a custom DLL resolver for the winfsp-msil assembly.
        //    This is the *only* mechanism that reliably intercepts P/Invoke
        //    on .NET 8 single-file publishes — SetDllDirectory and PATH
        //    tricks can be ignored by the runtime's native loader. We use
        //    Assembly.Load (NOT typeof) so we don't trigger type
        //    initialization of Fsp.Interop.Api before the resolver is set.
        try
        {
            var asm = Assembly.Load("winfsp-msil");
            NativeLibrary.SetDllImportResolver(asm, (name, _, _) =>
            {
                if (!name.StartsWith("winfsp-", StringComparison.OrdinalIgnoreCase))
                    return IntPtr.Zero;
                var file = name.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) ? name : name + ".dll";
                var full = Path.Combine(bin, file);
                return File.Exists(full) && NativeLibrary.TryLoad(full, out var h)
                    ? h
                    : IntPtr.Zero;
            });
        }
        catch (Exception ex)
        {
            // SetDllImportResolver throws if a resolver is already registered
            // for this assembly — that's fine, treat as success.
            if (ex is not InvalidOperationException)
            {
                LastError = $"Resolver: {ex.Message}";
            }
        }

        // 2. Belt-and-suspenders: PATH / SetDllDirectory / preload — these
        //    cover paths the resolver doesn't handle (e.g. side-loaded deps).
        try
        {
            var path = Environment.GetEnvironmentVariable("PATH") ?? "";
            if (path.IndexOf(bin, StringComparison.OrdinalIgnoreCase) < 0)
            {
                Environment.SetEnvironmentVariable("PATH", bin + ";" + path);
            }

            try
            {
                SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
                AddDllDirectory(bin);
            }
            catch { /* not all Windows versions */ }

            SetDllDirectory(bin);

            try { NativeLibrary.Load(nativeDll); }
            catch { LoadLibraryEx(nativeDll, IntPtr.Zero, LOAD_WITH_ALTERED_SEARCH_PATH); }
        }
        catch { /* non-fatal: resolver above is what matters */ }

        _initialized = true;
        return true;
    }

    /// <summary>Diagnostic — last reason why EnsureDllPath failed.</summary>
    public static string? LastError { get; private set; }

    private const uint LOAD_WITH_ALTERED_SEARCH_PATH = 0x00000008;
    private const uint LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetDllDirectory(string? lpPathName);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadLibraryEx(string lpFileName, IntPtr hReservedNull, uint dwFlags);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetDefaultDllDirectories(uint DirectoryFlags);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr AddDllDirectory(string NewDirectory);
}
