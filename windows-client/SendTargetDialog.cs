using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Windows.Forms;

namespace WiFiShareTray;

internal sealed class SendTargetDialog : Form
{
    private readonly ListView _list;

    public DiscoveredService? SelectedDevice { get; private set; }

    public SendTargetDialog(
        IReadOnlyList<DiscoveredService> devices,
        DiscoveredService? defaultDevice,
        string[] paths)
    {
        Text = "Send to which phone?";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ShowInTaskbar = true;
        ClientSize = new Size(460, 360);
        BackColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        var label = new Label
        {
            Text = paths.Length == 1
                ? $"Send \"{Path.GetFileName(paths[0])}\" to:"
                : $"Send {paths.Length} files to:",
            Top = 16, Left = 16, AutoSize = true,
            Font = new Font("Segoe UI", 11f, FontStyle.Bold),
        };

        _list = new ListView
        {
            Top = 50, Left = 16, Width = 428, Height = 240,
            View = View.Details,
            FullRowSelect = true,
            MultiSelect = false,
            HideSelection = false,
            BorderStyle = BorderStyle.FixedSingle,
        };
        _list.Columns.Add("Device", 220);
        _list.Columns.Add("Address", 130);
        _list.Columns.Add("Auth", 60);

        foreach (var d in devices)
        {
            var item = new ListViewItem(TrimName(d.Name));
            item.SubItems.Add(d.Address.ToString());
            item.SubItems.Add(d.AuthRequired ? "PIN" : "");
            item.Tag = d;
            if (defaultDevice != null &&
                string.Equals(d.Name, defaultDevice.Name, StringComparison.OrdinalIgnoreCase))
            {
                item.Selected = true;
                item.Focused = true;
            }
            _list.Items.Add(item);
        }

        if (_list.SelectedItems.Count == 0 && _list.Items.Count > 0)
            _list.Items[0].Selected = true;

        _list.DoubleClick += (_, _) => Confirm();

        var ok = new Button
        {
            Text = "Send",
            Top = 308, Left = 264, Width = 84, Height = 32,
            DialogResult = DialogResult.OK,
            BackColor = Color.FromArgb(25, 118, 210),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.System,
        };
        ok.Click += (_, _) => Confirm();

        var cancel = new Button
        {
            Text = "Cancel",
            Top = 308, Left = 360, Width = 84, Height = 32,
            DialogResult = DialogResult.Cancel,
            FlatStyle = FlatStyle.System,
        };

        Controls.AddRange(new Control[] { label, _list, ok, cancel });
        AcceptButton = ok;
        CancelButton = cancel;
    }

    private void Confirm()
    {
        if (_list.SelectedItems.Count > 0)
        {
            SelectedDevice = (DiscoveredService)_list.SelectedItems[0].Tag!;
            DialogResult = DialogResult.OK;
            Close();
        }
    }

    /// <summary>Same trim as TrayApp's — keep them in sync.</summary>
    private static string TrimName(string fullName)
    {
        var first = fullName.Split('.')[0];
        var sb = new System.Text.StringBuilder(first.Length);
        for (int i = 0; i < first.Length; i++)
        {
            if (first[i] == '\\' && i + 3 < first.Length &&
                char.IsDigit(first[i + 1]) && char.IsDigit(first[i + 2]) && char.IsDigit(first[i + 3]))
            {
                if (int.TryParse(first.Substring(i + 1, 3), out var code))
                {
                    sb.Append((char)code); i += 3; continue;
                }
            }
            sb.Append(first[i]);
        }
        return sb.ToString();
    }
}
