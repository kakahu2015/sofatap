using System.ComponentModel;
using System.Drawing.Drawing2D;

namespace SofaTap.Desktop;

internal static class MintCalm
{
    internal static readonly Color Canvas = Color.FromArgb(0x11, 0x19, 0x1B);
    internal static readonly Color Surface = Color.FromArgb(0x1E, 0x2A, 0x2C);
    internal static readonly Color Border = Color.FromArgb(0x33, 0x43, 0x46);
    internal static readonly Color Mint = Color.FromArgb(0x98, 0xE9, 0xC4);
    internal static readonly Color Text = Color.FromArgb(0xF0, 0xF5, 0xF3);
    internal static readonly Color Muted = Color.FromArgb(0xA8, 0xBA, 0xB7);
    internal static readonly Color Attention = Color.FromArgb(0xF2, 0xC7, 0x83);

    internal static GraphicsPath Rounded(RectangleF rectangle, float radius)
    {
        var path = new GraphicsPath();
        var diameter = Math.Min(radius * 2, Math.Min(rectangle.Width, rectangle.Height));
        if (diameter <= 0) { path.AddRectangle(rectangle); return path; }
        path.AddArc(rectangle.Left, rectangle.Top, diameter, diameter, 180, 90);
        path.AddArc(rectangle.Right - diameter, rectangle.Top, diameter, diameter, 270, 90);
        path.AddArc(rectangle.Right - diameter, rectangle.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(rectangle.Left, rectangle.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }
}

internal sealed class RoundedSurface : Panel
{
    internal RoundedSurface()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        BackColor = MintCalm.Surface;
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent?.BackColor ?? MintCalm.Canvas);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var path = MintCalm.Rounded(new RectangleF(.5f, .5f, Width - 1, Height - 1), 18 * DeviceDpi / 96f);
        using var fill = new SolidBrush(MintCalm.Surface);
        using var border = new Pen(MintCalm.Border);
        e.Graphics.FillPath(fill, path);
        e.Graphics.DrawPath(border, path);
    }
}

internal sealed class StatusBadge : Control
{
    private Color _indicatorColor = MintCalm.Muted;
    [Browsable(false), DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    internal Color IndicatorColor { get => _indicatorColor; set { _indicatorColor = value; Invalidate(); } }

    internal StatusBadge()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        ForeColor = MintCalm.Text;
        BackColor = MintCalm.Canvas;
        AccessibleRole = AccessibleRole.StaticText;
        TabStop = false;
    }

    public override Size GetPreferredSize(Size proposedSize)
    {
        var padding = (int)Math.Round(42 * DeviceDpi / 96d);
        var available = proposedSize.Width > 0 ? Math.Max(1, proposedSize.Width - padding) : int.MaxValue;
        var text = TextRenderer.MeasureText(Text, Font, new Size(available, int.MaxValue), TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix);
        return new Size(text.Width + padding, Math.Max((int)Math.Round(28 * DeviceDpi / 96d), text.Height + (int)Math.Round(10 * DeviceDpi / 96d)));
    }

    protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); AccessibleName = Text; Invalidate(); }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var path = MintCalm.Rounded(new RectangleF(.5f, .5f, Width - 1, Height - 1), Height / 2f);
        using var surface = new SolidBrush(MintCalm.Surface);
        using var border = new Pen(MintCalm.Border);
        using var indicator = new SolidBrush(IndicatorColor);
        e.Graphics.FillPath(surface, path);
        e.Graphics.DrawPath(border, path);
        var dot = (int)Math.Round(8 * DeviceDpi / 96d);
        var left = (int)Math.Round(12 * DeviceDpi / 96d);
        e.Graphics.FillEllipse(indicator, left, (Height - dot) / 2f, dot, dot);
        var textLeft = left + dot + (int)Math.Round(7 * DeviceDpi / 96d);
        TextRenderer.DrawText(e.Graphics, Text, Font, new Rectangle(textLeft, 0, Width - textLeft - left, Height), ForeColor,
            TextFormatFlags.VerticalCenter | TextFormatFlags.HorizontalCenter | TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix);
    }
}

internal sealed class QrView : Control
{
    private Bitmap? _image;
    internal bool HasImage => _image is not null;

    internal QrView()
    {
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        BackColor = Color.White;
        ForeColor = MintCalm.Surface;
        AccessibleRole = AccessibleRole.Graphic;
        TabStop = false;
    }

    internal void SetImage(Bitmap? image)
    {
        var previous = _image;
        _image = image;
        previous?.Dispose();
        AccessibleDescription = image is null
            ? Text
            : Strings.Get("Scan this QR code with the iPhone companion app to pair this computer. The QR code grants control of this computer; keep it private.");
        Invalidate();
    }

    protected override void OnTextChanged(EventArgs e)
    {
        base.OnTextChanged(e);
        if (_image is null) AccessibleDescription = Text;
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Color.White);
        if (_image is null)
        {
            var inset = (int)Math.Round(12 * DeviceDpi / 96d);
            TextRenderer.DrawText(e.Graphics, Text, Font, Rectangle.Inflate(ClientRectangle, -inset, -inset), ForeColor,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix);
            return;
        }
        // Preserve every edge and the complete original quiet zone; no smoothing, crop, tint or overlay.
        var scale = Math.Min((float)Width / _image.Width, (float)Height / _image.Height);
        var size = new Size(Math.Max(1, (int)Math.Round(_image.Width * scale)), Math.Max(1, (int)Math.Round(_image.Height * scale)));
        var rectangle = new Rectangle((Width - size.Width) / 2, (Height - size.Height) / 2, size.Width, size.Height);
        e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
        e.Graphics.PixelOffsetMode = PixelOffsetMode.Half;
        e.Graphics.DrawImage(_image, rectangle, 0, 0, _image.Width, _image.Height, GraphicsUnit.Pixel);
    }

    protected override void Dispose(bool disposing) { if (disposing) _image?.Dispose(); base.Dispose(disposing); }
}

// Button still supplies native keyboard activation, focus, tab order and accessibility.
internal sealed class CalmButton : Button
{
    private readonly bool _primary;
    private bool _hovered;
    private bool _pressed;

    internal CalmButton(bool primary)
    {
        _primary = primary;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        UseVisualStyleBackColor = false;
        BackColor = primary ? MintCalm.Surface : MintCalm.Canvas;
        ForeColor = MintCalm.Text;
    }

    protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); _hovered = true; Invalidate(); }
    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); _hovered = _pressed = false; Invalidate(); }
    protected override void OnMouseDown(MouseEventArgs e) { base.OnMouseDown(e); _pressed = e.Button == MouseButtons.Left; Invalidate(); }
    protected override void OnMouseUp(MouseEventArgs e) { base.OnMouseUp(e); _pressed = false; Invalidate(); }
    protected override void OnGotFocus(EventArgs e) { base.OnGotFocus(e); Invalidate(); }
    protected override void OnLostFocus(EventArgs e) { base.OnLostFocus(e); Invalidate(); }
    protected override void OnEnabledChanged(EventArgs e) { base.OnEnabledChanged(e); Invalidate(); }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent?.BackColor ?? BackColor);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var fillColor = _primary ? (Enabled ? (_pressed ? Color.FromArgb(126, 209, 174) : _hovered ? Color.FromArgb(171, 239, 209) : MintCalm.Mint)
            : Color.FromArgb(48, 65, 61)) : (_hovered && Enabled ? MintCalm.Surface : BackColor);
        using var path = MintCalm.Rounded(new RectangleF(.5f, .5f, Width - 1, Height - 1), 10 * DeviceDpi / 96f);
        using var fill = new SolidBrush(fillColor);
        e.Graphics.FillPath(fill, path);
        var foreground = !Enabled ? MintCalm.Muted : _primary ? MintCalm.Canvas : MintCalm.Text;
        TextRenderer.DrawText(e.Graphics, Text, Font, Rectangle.Inflate(ClientRectangle, -6, -3), foreground,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        if (Focused && ShowFocusCues)
        {
            var inset = (int)Math.Round(4 * DeviceDpi / 96d);
            ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -inset, -inset), _primary ? MintCalm.Canvas : MintCalm.Mint, fillColor);
        }
    }
}
