using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

namespace WiFiShareTray;

internal static class Program
{
    private static Mutex? _singleInstance;

    [STAThread]
    private static void Main(string[] args)
    {
        // Send-To passes file paths as arguments. Filter to ones that
        // actually point at existing files; ignore everything else
        // (the user might launch the .exe manually for the tray).
        var paths = args.Where(a => !string.IsNullOrWhiteSpace(a) && File.Exists(a))
                        .ToArray();

        // Single-instance gate. If another tray is already running we
        // forward our paths to it via named pipe and exit.
        _singleInstance = new Mutex(true,
            "WiFiShareTray-{4f3d6e8a-9c2b-4f1d-8e1a-2b1c5e6d7f9a}",
            out var firstInstance);

        if (!firstInstance)
        {
            if (paths.Length > 0) IpcClient.Send(paths);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) =>
        {
            MessageBox.Show(e.Exception.ToString(), "WiFi Share — error",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        };

        using var app = new TrayApp();
        if (paths.Length > 0) app.QueueUploads(paths);
        Application.Run();
    }
}
