using System;
using System.Threading;
using System.Windows.Forms;

namespace WiFiShareTray;

internal static class Program
{
    private static Mutex? _singleInstance;

    [STAThread]
    private static void Main()
    {
        // Single-instance guard
        _singleInstance = new Mutex(true, "WiFiShareTray-{4f3d6e8a-9c2b-4f1d-8e1a-2b1c5e6d7f9a}", out var firstInstance);
        if (!firstInstance) return;

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) =>
        {
            MessageBox.Show(e.Exception.ToString(), "WiFi Share — error",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        };

        using var app = new TrayApp();
        Application.Run();
    }
}
