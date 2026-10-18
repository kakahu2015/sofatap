using System.Drawing.Imaging;
using System.Globalization;
using System.Text.Json;
using QRCoder;
using SofaTap.Desktop;

internal static class Program
{
    private static readonly List<LayoutReceipt> Layouts = [];

    [STAThread]
    private static void Main(string[] args)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("WinForms rendering requires Windows.");
        var stable96 = args.Contains("--stable-96");
        // Localization QA: the default follows the Windows UI language. An explicit
        // language renders the other catalog without changing the machine setting.
        var languageOption = args.FirstOrDefault(argument => argument.StartsWith("--ui-language=", StringComparison.Ordinal));
        if (languageOption is not null)
        {
            var language = languageOption["--ui-language=".Length..];
            if (language is not ("en" or "zh")) throw new ArgumentException("--ui-language must be en or zh.");
            var culture = CultureInfo.GetCultureInfo(language == "zh" ? "zh-CN" : "en-US");
            CultureInfo.DefaultThreadCurrentUICulture = culture;
            CultureInfo.CurrentUICulture = culture;
        }
        var paths = args.Where(argument => argument != "--stable-96" && !argument.StartsWith("--ui-language=", StringComparison.Ordinal)).ToArray();
        if (paths.Length > 1 || paths.Any(path => path.StartsWith("--", StringComparison.Ordinal)))
            throw new ArgumentException("Pass the screenshot output directory and optional --stable-96 or --ui-language=en|zh.");
        // Match production by default: an unaware fixture conceals an unscaled startup form.
        var dpiMode = stable96 ? HighDpiMode.DpiUnaware : HighDpiMode.PerMonitorV2;
        Require(Application.SetHighDpiMode(dpiMode), "The fixture must establish its DPI mode before creating a native handle.");
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        var output = Path.GetFullPath(paths.FirstOrDefault() ?? "artifacts/mint-calm");
        Directory.CreateDirectory(output);

        // An ordinary QR with a clearly non-pairing payload. No real token, identity or server exists.
        using var data = QRCodeGenerator.GenerateQrCode("SOFATAP UI PREVIEW - DEMO ONLY - NOT A PAIRING CODE. " + new string('.', 140), QRCodeGenerator.ECCLevel.Q);
        using var qr = new PngByteQRCode(data);
        var png = qr.GetGraphic(5);
        const string host = "192.0.2.17"; // RFC 5737 documentation range.
        var demoLink = $"sofatap://pair?host={host}&port=53319&token={new string('D', 43)}&fingerprint={new string('0', 64)}";
        var state = new ReceiverState(Strings.UsesChinese ? "客厅电脑" : "Living Room Computer", "windows", "ready", false, false, [new PairingImage(host, png, demoLink)]);

        // Do not replace the constructor's size here: that would hide startup-size regressions.
        using (var window = OpenPendingFixture())
        {
            CheckLayout(window, "pending-startup", requireQrVisible: true);
            window.ApplyPreviewState(state);
            Settle(window);
            Require(window.PreviewHasQr && window.PreviewCopyEnabled, "Ready fixture must have a QR and available Copy action.");
            CheckLayout(window, "ready-startup", requireQrVisible: true);
            Capture(window, Path.Combine(output, "windows-ready.png"));
            window.ShowPreviewFailure();
            Settle(window);
            Require(!window.PreviewHasQr && !window.PreviewCopyEnabled, "A failure must clear the stale QR and disable Copy.");
            CheckLayout(window, "failure", requireQrVisible: true);
            Capture(window, Path.Combine(output, "windows-failure.png"));
            window.ApplyPreviewState(state);
            Settle(window);
            CheckLayout(window, "ready-after-failure", requireQrVisible: true);
        }
        using (var window = OpenFixture(state with { InputStatus = "accessibility_required" }))
        {
            CheckLayout(window, "permission", requireQrVisible: true);
            Capture(window, Path.Combine(output, "windows-permission.png"));
        }
        using (var window = OpenFixture(state with { Name = Strings.UsesChinese ? "客厅电脑 · Living Room Computer with a long device name" : "Living Room Computer with a long device name" }))
        {
            ResizeLogical(window, new Size(334, 510));
            Require(window.PreviewCanScroll, "A short, narrow window must keep overflow reachable by scrolling.");
            CheckLayout(window, "narrow", requireQrVisible: true);
            Capture(window, Path.Combine(output, "windows-narrow.png"));
            window.ScrollPreviewToBottom();
            Settle(window);
            Require(window.PreviewScrollPosition.Y < 0, "The short fixture must actually scroll to its footer.");
            CheckLayout(window, "narrow-scrolled", requireQrVisible: false);
            Capture(window, Path.Combine(output, "windows-narrow-scrolled.png"));

            // Resize back and forth in the actual current DPI, including while initially scrolled.
            foreach (var size in new[] { new Size(680, 800), new Size(350, 560), new Size(472, 730), new Size(334, 510), new Size(472, 730) })
            {
                ResizeLogical(window, size);
                CheckLayout(window, $"resize-{size.Width}x{size.Height}", requireQrVisible: false);
            }
        }
        using (var window = OpenFixture(state with { Pairings = [new PairingImage(host, png)] }))
        {
            Require(window.PreviewHasQr && !window.PreviewCopyEnabled, "QR-only snapshots must render with Copy disabled.");
            CheckLayout(window, "qr-only", requireQrVisible: true);
        }
        foreach (var workArea in new[] { Screen.PrimaryScreen!.WorkingArea.Size, new Size(1920, 1040), new Size(1280, 680) })
        {
            using var window = OpenFixture(state);
            window.FitPreviewToWorkArea(workArea);
            Settle(window);
            Require(window.Width <= workArea.Width && window.Height <= workArea.Height, "The initial window must fit its work area.");
            CheckLayout(window, $"work-area-{workArea.Width}x{workArea.Height}", requireQrVisible: true);
            Capture(window, Path.Combine(output, $"windows-work-area-{workArea.Width}x{workArea.Height}.png"));
        }
        File.WriteAllText(Path.Combine(output, "windows-layout.json"), JsonSerializer.Serialize(new
        {
            dpiMode = dpiMode.ToString(),
            uiLanguage = Strings.UsesChinese ? "zh" : "en",
            scope = "Native off-screen fixtures at the current display DPI; no monitor switch or physical input acceptance.",
            layouts = Layouts
        }, new JsonSerializerOptions { WriteIndented = true }));
        Console.WriteLine($"Rendered synthetic WinForms fixtures in {dpiMode} with UI language {(Strings.UsesChinese ? "zh" : "en")}; verified {Layouts.Count} layouts at DPI {string.Join(", ", Layouts.Select(layout => layout.Dpi).Distinct())}. No backend, listener, clipboard write or desktop input was started.");
    }

    private static PairingWindow OpenPendingFixture() => OpenWindow(PairingWindow.CreatePendingPreview());

    private static PairingWindow OpenFixture(ReceiverState state) => OpenWindow(new PairingWindow(state));

    private static PairingWindow OpenWindow(PairingWindow window)
    {
        window.ShowInTaskbar = false;
        window.StartPosition = FormStartPosition.Manual;
        window.Location = new Point(-32000, -32000);
        // Create actual native handles off-screen, without activation; Shown has no backend handler in fixture mode.
        window.Show();
        Settle(window);
        return window;
    }

    private static void ResizeLogical(PairingWindow window, Size size)
    {
        window.ClientSize = new Size((int)Math.Round(size.Width * window.DeviceDpi / 96d), (int)Math.Round(size.Height * window.DeviceDpi / 96d));
        Settle(window);
    }

    private static void Settle(PairingWindow window)
    {
        Application.DoEvents();
        window.PerformLayout();
        Application.DoEvents();
    }

    private static void CheckLayout(PairingWindow window, string name, bool requireQrVisible)
    {
        var viewport = window.PreviewViewportSize;
        var qr = window.PreviewQrViewportBounds;
        var scroll = window.PreviewScrollPosition;
        Require(!window.PreviewHasHorizontalScroll && scroll.X == 0,
            $"{name}: no horizontal scrolling is needed at {window.DeviceDpi} DPI. Client={window.ClientSize}, viewport={viewport}, QR={qr}, scroll={scroll}.");
        Require(qr.Width > 0 && qr.Width == qr.Height, $"{name}: QR must remain square.");
        Require(qr.Left >= 0 && qr.Right <= viewport.Width, $"{name}: QR and its quiet zone must fit the viewport width.");
        if (requireQrVisible)
        {
            Require(scroll.Y == 0, $"{name}: startup/state changes must not unexpectedly jump down the page.");
            Require(qr.Top >= 0 && qr.Bottom <= viewport.Height, $"{name}: the complete QR must be visible at {window.DeviceDpi} DPI.");
        }
        Layouts.Add(new LayoutReceipt(name, window.DeviceDpi, window.ClientSize, viewport, qr, scroll,
            window.PreviewHasHorizontalScroll, window.PreviewCanScroll));
    }

    private sealed record LayoutReceipt(string Name, int Dpi, Size ClientSize, Size ViewportSize,
        Rectangle QrBounds, Point ScrollPosition, bool HorizontalScroll, bool VerticalScroll);

    private static void Capture(PairingWindow window, string path)
    {
        using var bitmap = new Bitmap(window.Width, window.Height);
        window.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
        bitmap.Save(path, ImageFormat.Png);
    }

    private static void Require(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
}
