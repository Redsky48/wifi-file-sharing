using System;
using System.IO;

namespace WiFiShareTray;

/// <summary>
/// Adds / removes a "WiFi Share" shortcut in the user's Send-To folder.
/// When present, Windows Explorer's right-click → Send To menu lists
/// this app, and selecting it launches WiFiShareTray.exe with the file
/// path(s) as arguments. The launched .exe forwards them via IPC to the
/// running tray, which uploads to the connected phone.
/// </summary>
internal static class SendToShortcut
{
    private const string ShortcutFileName = "WiFi Share.lnk";

    public static string ShortcutPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.SendTo),
        ShortcutFileName);

    public static bool IsRegistered => File.Exists(ShortcutPath);

    public static void Register()
    {
        var target = Installer.IsInstalled
            ? Installer.InstallExePath
            : (Installer.CurrentExePath
                ?? throw new InvalidOperationException("Cannot determine .exe path."));

        CreateShortcut(ShortcutPath, target,
            description: "Send file to phone via WiFi Share",
            iconLocation: target + ",0");
    }

    public static void Unregister()
    {
        if (File.Exists(ShortcutPath)) File.Delete(ShortcutPath);
    }

    /// <summary>
    /// Build a .lnk via the WScript.Shell COM object — no extra packages,
    /// works on every Windows that has windows-script-host installed
    /// (i.e. every Windows since the early 2000s).
    /// </summary>
    private static void CreateShortcut(
        string lnkPath,
        string targetPath,
        string description,
        string iconLocation)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell")
            ?? throw new InvalidOperationException("WScript.Shell not available");
        dynamic shell = Activator.CreateInstance(shellType)!;
        try
        {
            dynamic lnk = shell.CreateShortcut(lnkPath);
            try
            {
                lnk.TargetPath = targetPath;
                lnk.WorkingDirectory = Path.GetDirectoryName(targetPath) ?? "";
                lnk.Description = description;
                lnk.IconLocation = iconLocation;
                lnk.Save();
            }
            finally
            {
                System.Runtime.InteropServices.Marshal.FinalReleaseComObject(lnk);
            }
        }
        finally
        {
            System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell);
        }
    }
}
