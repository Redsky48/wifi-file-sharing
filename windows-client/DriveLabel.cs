using System;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace WiFiShareTray;

/// <summary>
/// Sets a friendly display label on a mounted network drive. Windows stores
/// a per-mount override at
///   HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\
///        &lt;UNC-path-with-#-instead-of-\&gt;\_LabelFromReg
/// so writing it there makes Explorer show "WiFi Share — Pixel 9 Pro"
/// instead of the raw "dav (\\192.168.1.179@8080\DavWWWRoot)" form.
/// </summary>
internal static class DriveLabel
{
    public static void Apply(string driveLetter, string label)
    {
        try
        {
            var unc = GetUncForDrive(driveLetter);
            if (unc == null) return;
            // \\server@8080\DavWWWRoot\dav  →  ##server@8080#DavWWWRoot#dav
            var keyName = unc.Replace('\\', '#');
            using var key = Registry.CurrentUser.CreateSubKey(
                $@"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\{keyName}");
            key.SetValue("_LabelFromReg", label, RegistryValueKind.String);
            NotifyExplorer();
        }
        catch
        {
            // Cosmetic — never let label-setting break the mount.
        }
    }

    public static void Clear(string driveLetter)
    {
        try
        {
            var unc = GetUncForDrive(driveLetter);
            if (unc == null) return;
            var keyName = unc.Replace('\\', '#');
            Registry.CurrentUser.DeleteSubKeyTree(
                $@"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\{keyName}",
                throwOnMissingSubKey: false);
            NotifyExplorer();
        }
        catch { /* ignore */ }
    }

    private static string? GetUncForDrive(string driveLetter)
    {
        var local = driveLetter.TrimEnd(':') + ":";
        var sb = new StringBuilder(1024);
        int len = sb.Capacity;
        int err = WNetGetConnection(local, sb, ref len);
        if (err == 0) return sb.ToString().TrimEnd('\0', '\r', '\n');
        if (err == ERROR_MORE_DATA && len > sb.Capacity)
        {
            sb = new StringBuilder(len);
            err = WNetGetConnection(local, sb, ref len);
            if (err == 0) return sb.ToString().TrimEnd('\0', '\r', '\n');
        }
        return null;
    }

    private static void NotifyExplorer()
    {
        SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_FLUSH, IntPtr.Zero, IntPtr.Zero);
    }

    private const int ERROR_MORE_DATA = 234;
    private const int SHCNE_ASSOCCHANGED = 0x08000000;
    private const int SHCNF_FLUSH = 0x1000;

    [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
    private static extern int WNetGetConnection(
        string lpLocalName,
        StringBuilder lpRemoteName,
        ref int lpnLength);

    [DllImport("shell32.dll")]
    private static extern void SHChangeNotify(
        int wEventId, int uFlags, IntPtr dwItem1, IntPtr dwItem2);
}
