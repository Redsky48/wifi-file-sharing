using System;
using System.IO;

namespace WiFiShareTray;

/// <summary>
/// Locates the FFmpeg shared DLLs on this machine and points
/// FFmpeg.AutoGen at them. Checked in priority order:
///   1. &lt;exe-dir&gt;\ffmpeg\     — DLLs we shipped with the tray
///   2. PATH                       — system-wide ffmpeg install
///   3. C:\ffmpeg\bin              — common manual-install location
///
/// If none works, [IsAvailable] returns false and the caller can show
/// a clear "install FFmpeg" message instead of crashing on first
/// avcodec_find_decoder call.
/// </summary>
internal static class FFmpegSetup
{
    private static readonly object Lock = new();
    private static bool _attempted;
    public static bool IsAvailable { get; private set; }
    public static string? FailureReason { get; private set; }
    public static string? RootPath { get; private set; }

    public static bool Configure()
    {
        lock (Lock)
        {
            if (_attempted) return IsAvailable;
            _attempted = true;
            var candidates = BuildCandidates();
            foreach (var path in candidates)
            {
                if (string.IsNullOrEmpty(path)) continue;
                if (!Directory.Exists(path)) continue;
                if (!HasRequiredDlls(path)) continue;
                try
                {
                    FFmpeg.AutoGen.ffmpeg.RootPath = path;
                    // Trigger a no-op call to force the dynamic-load.
                    // av_version_info will throw DllNotFoundException
                    // if the path is wrong before we set up a stream.
                    var _ = FFmpeg.AutoGen.ffmpeg.av_version_info();
                    RootPath = path;
                    IsAvailable = true;
                    return true;
                }
                catch (Exception ex)
                {
                    FailureReason = $"{path}: {ex.Message}";
                }
            }
            if (FailureReason == null)
            {
                FailureReason = "FFmpeg DLLs not found. See windows-client/lib/ffmpeg/README.txt.";
            }
            return false;
        }
    }

    private static IEnumerable<string> BuildCandidates()
    {
        // User-supplied DLLs in lib/ffmpeg/ win (copied to <exe>/ffmpeg/).
        yield return Path.Combine(AppContext.BaseDirectory, "ffmpeg");
        // Sdcb.FFmpeg.runtime.windows-x64 drops DLLs here at publish.
        yield return Path.Combine(AppContext.BaseDirectory, "runtimes", "win-x64", "native");
        // .NET sometimes leaves single-file extracted dependencies in a
        // sibling extraction dir; covers `dotnet publish -p:PublishSingleFile=true`.
        var extract = Environment.GetEnvironmentVariable("DOTNET_BUNDLE_EXTRACT_BASE_DIR");
        if (!string.IsNullOrEmpty(extract)) yield return extract;
        // Plain alongside the exe (some FFmpeg installers do this).
        yield return AppContext.BaseDirectory;
        // System install / user PATH.
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var p in path.Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(p)) continue;
            yield return p;
        }
        yield return @"C:\ffmpeg\bin";
        yield return @"C:\Program Files\ffmpeg\bin";
    }

    private static bool HasRequiredDlls(string path)
    {
        // Just check for *any* avcodec-NN.dll; the exact major number
        // depends on the FFmpeg release. ffmpeg.RootPath will fail
        // explicitly if other DLLs are missing — that's fine, we'd
        // catch it in Configure() above.
        try
        {
            foreach (var f in Directory.EnumerateFiles(path, "avcodec-*.dll"))
            {
                if (!string.IsNullOrEmpty(f)) return true;
            }
        }
        catch { }
        return false;
    }
}
