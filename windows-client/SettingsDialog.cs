using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace WiFiShareTray;

internal sealed class SettingsDialog : Form
{
    private readonly Settings _settings;
    private readonly TextBox _deviceName;
    private readonly TextBox _downloadFolder;

    public SettingsDialog(Settings settings)
    {
        _settings = settings;

        Text = "WiFi Share — Settings";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(560, 320);
        BackColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        // ---- Device name ---------------------------------------------------

        var nameLabel = new Label
        {
            Text = "Device name (shown on phone)",
            Top = 20, Left = 20, AutoSize = true,
            ForeColor = Color.FromArgb(80, 80, 80),
        };
        _deviceName = new TextBox
        {
            Top = 42, Left = 20, Width = 520, Height = 28,
            Text = settings.DeviceName,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 11f),
        };

        // ---- Download folder ----------------------------------------------

        var folderLabel = new Label
        {
            Text = "Download folder for received files",
            Top = 92, Left = 20, AutoSize = true,
            ForeColor = Color.FromArgb(80, 80, 80),
        };
        _downloadFolder = new TextBox
        {
            Top = 114, Left = 20, Width = 412, Height = 28,
            Text = settings.DownloadFolder,
            ReadOnly = true,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 11f),
        };
        var browse = new Button
        {
            Top = 113, Left = 440, Width = 100, Height = 30,
            Text = "Browse…",
            FlatStyle = FlatStyle.System,
        };
        browse.Click += (_, _) => PickFolder();

        var openFolder = new LinkLabel
        {
            Top = 152, Left = 20, AutoSize = true,
            Text = "Open in Explorer",
            LinkColor = Color.FromArgb(25, 118, 210),
        };
        openFolder.LinkClicked += (_, _) => OpenInExplorer();

        // ---- Buttons (bottom-right, plenty of room) -----------------------

        var buttonRow = new FlowLayoutPanel
        {
            Top = ClientSize.Height - 60,
            Left = 20,
            Width = ClientSize.Width - 40,
            Height = 40,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = Color.Transparent,
        };

        var cancel = new Button
        {
            Text = "Cancel",
            Width = 100, Height = 32,
            DialogResult = DialogResult.Cancel,
            FlatStyle = FlatStyle.System,
            Margin = new Padding(8, 0, 0, 0),
        };

        var ok = new Button
        {
            Text = "Save",
            Width = 100, Height = 32,
            DialogResult = DialogResult.OK,
            FlatStyle = FlatStyle.System,
            Margin = new Padding(0, 0, 0, 0),
            BackColor = Color.FromArgb(25, 118, 210),
            ForeColor = Color.White,
        };
        ok.Click += (_, _) => SaveAndClose();

        buttonRow.Controls.Add(cancel);
        buttonRow.Controls.Add(ok);

        // ---- Compose ------------------------------------------------------

        Controls.AddRange(new Control[]
        {
            nameLabel, _deviceName,
            folderLabel, _downloadFolder, browse,
            openFolder,
            buttonRow,
        });

        AcceptButton = ok;
        CancelButton = cancel;
    }

    private void OpenInExplorer()
    {
        if (!Directory.Exists(_downloadFolder.Text)) return;
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"\"{_downloadFolder.Text}\"",
                UseShellExecute = true,
            });
        }
        catch { /* ignore */ }
    }

    private void PickFolder()
    {
        using var dlg = new FolderBrowserDialog
        {
            Description = "Pick a folder for files received from the phone",
            ShowNewFolderButton = true,
            InitialDirectory = Directory.Exists(_downloadFolder.Text)
                ? _downloadFolder.Text
                : Settings.DefaultDownloadFolder(),
        };
        if (dlg.ShowDialog(this) == DialogResult.OK && !string.IsNullOrWhiteSpace(dlg.SelectedPath))
        {
            _downloadFolder.Text = dlg.SelectedPath;
        }
    }

    private void SaveAndClose()
    {
        var trimmedName = _deviceName.Text.Trim();
        if (!string.IsNullOrEmpty(trimmedName)) _settings.DeviceName = trimmedName;
        if (Directory.Exists(_downloadFolder.Text))
            _settings.DownloadFolder = _downloadFolder.Text;
        _settings.Save();
        DialogResult = DialogResult.OK;
        Close();
    }
}
