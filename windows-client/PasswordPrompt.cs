using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace WiFiShareTray;

/// <summary>
/// Mobile-style PIN pad: six filled-dot slots and a 3x4 numeric keypad
/// (digits + backspace). Tapping a digit fills the next dot; six digits
/// in a row auto-submits. No text editing — just tap.
/// </summary>
internal sealed class PasswordPrompt : Form
{
    private readonly PinDot[] _slots = new PinDot[6];
    private string _pin = "";
    private bool _submitting;

    private static readonly Color Filled = Color.FromArgb(25, 118, 210);
    private static readonly Color Unfilled = Color.FromArgb(220, 224, 232);
    private static readonly Color KeyBg = Color.FromArgb(245, 246, 250);
    private static readonly Color KeyHover = Color.FromArgb(225, 235, 250);
    private static readonly Color KeyActive = Color.FromArgb(200, 220, 250);
    private static readonly Color KeyBorder = Color.FromArgb(220, 222, 230);

    public string Password => _pin;

    public PasswordPrompt(string deviceName, bool wrongPassword)
    {
        Text = wrongPassword ? "Wrong PIN" : "PIN required";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
        MinimizeBox = false;
        ClientSize = new Size(360, 560);
        ShowInTaskbar = false;
        TopMost = true;
        BackColor = Color.White;
        KeyPreview = true;

        var title = new Label
        {
            Text = wrongPassword
                ? "Wrong PIN — try again"
                : "Enter PIN",
            Top = 24, Left = 0, Width = ClientSize.Width,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
            Height = 28,
            ForeColor = wrongPassword ? Color.Firebrick : Color.Black,
        };
        Controls.Add(title);

        var subtitle = new Label
        {
            Text = $"for \"{deviceName}\"",
            Top = 56, Left = 0, Width = ClientSize.Width,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 9.5f),
            ForeColor = Color.DimGray,
            Height = 20,
        };
        Controls.Add(subtitle);

        // 6 dot slots
        const int dotSize = 18;
        const int dotGap = 16;
        var totalW = dotSize * 6 + dotGap * 5;
        var startX = (ClientSize.Width - totalW) / 2;
        for (int i = 0; i < 6; i++)
        {
            var dot = new PinDot { Top = 100, Left = startX + i * (dotSize + dotGap) };
            _slots[i] = dot;
            Controls.Add(dot);
        }

        // Keypad: 1 2 3 / 4 5 6 / 7 8 9 / · 0 ⌫
        const int btnSize = 78;
        const int btnGap = 14;
        var gridW = btnSize * 3 + btnGap * 2;
        var gridX = (ClientSize.Width - gridW) / 2;
        const int gridY = 150;

        for (int row = 0; row < 4; row++)
        {
            for (int col = 0; col < 3; col++)
            {
                string label;
                Action? action;
                if (row < 3)
                {
                    var n = row * 3 + col + 1;
                    label = n.ToString();
                    action = () => OnDigit(label);
                }
                else if (col == 0)
                {
                    continue; // empty cell
                }
                else if (col == 1)
                {
                    label = "0";
                    action = () => OnDigit("0");
                }
                else
                {
                    label = "⌫";
                    action = OnBackspace;
                }

                var btn = MakeKey(label);
                btn.Top = gridY + row * (btnSize + btnGap);
                btn.Left = gridX + col * (btnSize + btnGap);
                btn.Width = btnSize;
                btn.Height = btnSize;
                btn.Click += (_, _) => action!();
                Controls.Add(btn);
            }
        }

        var cancel = new Button
        {
            Top = ClientSize.Height - 48,
            Left = ClientSize.Width - 100,
            Width = 86,
            Height = 30,
            Text = "Cancel",
            DialogResult = DialogResult.Cancel,
            FlatStyle = FlatStyle.System,
            TabStop = false,
        };
        Controls.Add(cancel);
        CancelButton = cancel;

        // Hardware keyboard support — same digits + backspace
        KeyDown += (_, e) =>
        {
            if (e.KeyCode >= Keys.D0 && e.KeyCode <= Keys.D9)
            {
                OnDigit(((int)(e.KeyCode - Keys.D0)).ToString());
                e.Handled = e.SuppressKeyPress = true;
            }
            else if (e.KeyCode >= Keys.NumPad0 && e.KeyCode <= Keys.NumPad9)
            {
                OnDigit(((int)(e.KeyCode - Keys.NumPad0)).ToString());
                e.Handled = e.SuppressKeyPress = true;
            }
            else if (e.KeyCode == Keys.Back)
            {
                OnBackspace();
                e.Handled = e.SuppressKeyPress = true;
            }
        };

        UpdateDots();
    }

    private static Button MakeKey(string text)
    {
        var btn = new Button
        {
            Text = text,
            Font = new Font("Segoe UI", 20f, FontStyle.Regular),
            FlatStyle = FlatStyle.Flat,
            BackColor = KeyBg,
            ForeColor = Color.Black,
            TabStop = false,
            UseCompatibleTextRendering = false,
        };
        btn.FlatAppearance.BorderColor = KeyBorder;
        btn.FlatAppearance.BorderSize = 1;
        btn.FlatAppearance.MouseOverBackColor = KeyHover;
        btn.FlatAppearance.MouseDownBackColor = KeyActive;
        return btn;
    }

    private void OnDigit(string digit)
    {
        if (_submitting) return;
        if (_pin.Length >= 6) return;
        _pin += digit;
        UpdateDots();
        if (_pin.Length == 6)
        {
            _submitting = true;
            // Brief delay so the user sees the last dot fill before the dialog closes.
            var t = new System.Windows.Forms.Timer { Interval = 150 };
            t.Tick += (_, _) =>
            {
                t.Stop();
                t.Dispose();
                DialogResult = DialogResult.OK;
                Close();
            };
            t.Start();
        }
    }

    private void OnBackspace()
    {
        if (_submitting) return;
        if (_pin.Length == 0) return;
        _pin = _pin.Substring(0, _pin.Length - 1);
        UpdateDots();
    }

    private void UpdateDots()
    {
        for (int i = 0; i < 6; i++)
            _slots[i].FillState = i < _pin.Length;
    }

    /// <summary>Round dot — drawn manually to be circular and clean.</summary>
    private sealed class PinDot : Control
    {
        private bool _filled;
        public bool FillState
        {
            get => _filled;
            set { if (_filled != value) { _filled = value; Invalidate(); } }
        }

        public PinDot()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint
                | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.UserPaint
                | ControlStyles.SupportsTransparentBackColor, true);
            Width = Height = 18;
            BackColor = Color.Transparent;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(_filled ? Filled : Unfilled);
            g.FillEllipse(brush, 0, 0, Width - 1, Height - 1);
            if (!_filled)
            {
                using var pen = new Pen(Color.FromArgb(190, 195, 205), 1);
                g.DrawEllipse(pen, 0, 0, Width - 1, Height - 1);
            }
        }
    }
}
