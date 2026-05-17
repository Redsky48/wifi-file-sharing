using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace WiFiShareTray;

/// <summary>
/// Notifies on every change to the Windows clipboard. Uses the modern
/// <c>AddClipboardFormatListener</c> Win32 API — every owner that puts
/// data in the clipboard triggers <see cref="ClipboardChanged"/>.
///
/// Implementation note: the API requires an HWND to deliver
/// WM_CLIPBOARDUPDATE messages to. We back ourselves with a hidden
/// NativeWindow so we don't force a visible form onto the TrayApp
/// owner.
/// </summary>
public sealed class ClipboardWatcher : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    private readonly HiddenWindow _window;

    public event Action? ClipboardChanged;

    public ClipboardWatcher()
    {
        _window = new HiddenWindow(() => ClipboardChanged?.Invoke());
        AddClipboardFormatListener(_window.Handle);
    }

    public void Dispose()
    {
        try { RemoveClipboardFormatListener(_window.Handle); } catch { }
        try { _window.DestroyHandle(); } catch { }
    }

    private sealed class HiddenWindow : NativeWindow
    {
        private readonly Action _onUpdate;
        public HiddenWindow(Action onUpdate)
        {
            _onUpdate = onUpdate;
            // HWND_MESSAGE parent gives us a message-only window —
            // invisible, doesn't appear in alt-tab, no taskbar entry.
            CreateHandle(new CreateParams { Parent = new IntPtr(-3) }); // HWND_MESSAGE
        }
        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_CLIPBOARDUPDATE) _onUpdate();
            base.WndProc(ref m);
        }
    }
}
