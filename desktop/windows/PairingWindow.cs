using System.Runtime.InteropServices;

namespace SofaTap.Desktop;

internal sealed class PairingWindow : Form
{
    private readonly BackendProcess _backend = new();
    private readonly System.Windows.Forms.Timer _copyFeedback = new() { Interval = 2000 };
    private readonly ReceiverLaunchOptions _options;
    private StartupRegistration? _startup;
    private readonly NotifyIcon? _tray;
    private readonly ToolTip _startupHint = new();
    private readonly ContextMenuStrip? _trayMenu;
    private bool _updatingStartup;
    private bool _quitRequested;
    private bool _installUpdateRequested;
    private readonly string? _healthTicket;
    private WindowsUpdates? _updates;
    private ToolStripMenuItem? _checkUpdates;
    private ToolStripMenuItem? _automaticUpdates;
    private ToolStripMenuItem? _installUpdate;
    private readonly System.Windows.Forms.Timer _updateTimer = new() { Interval = 60 * 60 * 1000 };
    private readonly CheckBox _startAtLogin = new()
    {
        Text = Strings.Get("Launch at login"), AutoSize = false, TabIndex = 4,
        ForeColor = MintCalm.Muted, BackColor = Color.Transparent,
        AccessibleName = Strings.Get("Launch at login"),
        AccessibleDescription = Strings.Get("Enabled by default on first launch. Windows Settings → Apps → Startup can still disable HostTap Remote.")
    };
    private readonly bool _preview;
    private readonly Panel _scroll = new() { Dock = DockStyle.Fill, AutoScroll = true, BackColor = MintCalm.Canvas };
    private readonly Panel _content = new() { Dock = DockStyle.Top, BackColor = MintCalm.Canvas };
    private readonly Panel _column = new() { BackColor = MintCalm.Canvas };
    private readonly StatusBadge _status = new() { Text = Strings.Get("Preparing receiver") };
    private readonly Label _helper = TextLabel(Strings.Get("Open the companion app on your iPhone and scan the QR code"), MintCalm.Muted);
    private readonly RoundedSurface _card = new();
    private readonly Label _machine = TextLabel(Strings.Get("Starting computer receiver…"), MintCalm.Text);
    private readonly ComboBox _addresses = new()
    {
        DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat,
        BackColor = MintCalm.Surface, ForeColor = MintCalm.Text,
        AccessibleName = Strings.Get("Computer network address"),
        AccessibleDescription = Strings.Get("Choose the computer network address your iPhone can reach."), TabIndex = 0,
        Enabled = false, IntegralHeight = true
    };
    private readonly QrView _qr = new()
    {
        AccessibleName = Strings.Get("iPhone private pairing QR code"),
        Text = Strings.Get("Creating QR code…")
    };
    private readonly CalmButton _copy = new(true)
    {
        Text = Strings.Get("Copy Pairing Link"), Enabled = false, TabIndex = 1,
        AccessibleName = Strings.Get("Copy Pairing Link"),
        AccessibleDescription = Strings.Get("Copy the current pairing link to the clipboard. The link can grant control of this computer, so do not share it.")
    };
    private readonly Label _networkHint = TextLabel(Strings.Get("Connect iPhone and computer to the same network"), MintCalm.Muted);
    private readonly Label _privacyHint = TextLabel(Strings.Get("Keep your pairing code private"), MintCalm.Muted);
    private readonly CalmButton _refresh = new(false) { Text = Strings.Get("Refresh"), Enabled = false, TabIndex = 2, AccessibleName = Strings.Get("Refresh pairing QR code") };
    private readonly CalmButton _quit = new(false)
    {
        Text = Strings.Get("Quit"), TabIndex = 3,
        AccessibleName = Strings.Get("Quit receiver"),
        AccessibleDescription = Strings.Get("Stop the receiver process this window started and quit.")
    };
    private PairingImage[] _pairings = [];
    private byte[]? _renderedPng;
    private string? _copyUri;
    private bool _updating;
    private bool _layingOut;
    private bool _layoutReady;
    private bool _receivedInitialState;
    private bool _stopping;
    private bool _allowClose;
    private readonly Icon _brandIcon = LoadBrandIcon();

    private static Icon LoadBrandIcon()
    {
        using var stream = typeof(PairingWindow).Assembly.GetManifestResourceStream("Sofatap.AppIcon")
            ?? throw new InvalidOperationException("The HostTap Remote app icon is missing from this build.");
        using var icon = new Icon(stream);
        return (Icon)icon.Clone();
    }

    internal PairingWindow(ReceiverLaunchOptions options, string? healthTicket = null) : this(options, preview: false, healthTicket) { }

    // Offline rendering only: this constructor never attaches the backend-start event.
    internal PairingWindow(ReceiverState fixture) : this(new(false, false), preview: true) { ApplyState(fixture); }
    internal static PairingWindow CreatePendingPreview() => new(new(false, false), preview: true);

    private PairingWindow(ReceiverLaunchOptions options, bool preview, string? healthTicket = null)
    {
        _options = options;
        _preview = preview;
        _healthTicket = healthTicket;
        SuspendLayout();
        // These are design dimensions at 96 DPI. WinForms scales the form once;
        // LayoutContents then places children in the current device coordinates.
        AutoScaleDimensions = new SizeF(96, 96);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = Strings.ProductName;
        Icon = _brandIcon;
        ShowIcon = true;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(472, 730);
        MinimumSize = new Size(350, 440);
        Font = new Font("Segoe UI", 10.5f);
        BackColor = MintCalm.Canvas;
        ForeColor = MintCalm.Text;
        DoubleBuffered = true;
        _helper.Font = Font;
        _machine.Font = new Font(Font.FontFamily, 10.5f, FontStyle.Bold);
        _machine.TextAlign = ContentAlignment.MiddleLeft;
        _machine.AccessibleName = Strings.Get("Computer name");
        _copy.Font = new Font(Font.FontFamily, 11, FontStyle.Bold);
        _status.Font = new Font(Font.FontFamily, 9.5f);
        _networkHint.Font = _privacyHint.Font = new Font(Font.FontFamily, 9.5f);
        _card.Controls.AddRange([_qr, _machine, _addresses, _copy]);
        _column.Controls.AddRange([_status, _helper, _card, _networkHint, _privacyHint, _refresh, _quit, _startAtLogin]);
        _content.Controls.Add(_column);
        _scroll.Controls.Add(_content);
        Controls.Add(_scroll);
        _scroll.ClientSizeChanged += (_, _) => LayoutContents();
        _copyFeedback.Tick += (_, _) => { _copyFeedback.Stop(); _copy.Text = Strings.Get("Copy Pairing Link"); };
        _addresses.SelectedIndexChanged += (_, _) => { if (!_updating) RenderQr(); };
        _copy.Click += (_, _) => CopyPairingLink();
        _refresh.Click += async (_, _) => await RefreshAsync();
        _quit.Click += (_, _) => QuitReceiver();
        _startAtLogin.Checked = preview; // Synthetic first-launch appearance; no startup store is touched.
        // Store/MSIX builds cannot launch through a shell:startup shortcut.
        _startAtLogin.Visible = !StoreBuild.Packaged;
        _startAtLogin.Enabled = !preview && options.ManagesStartup && !StoreBuild.Packaged;
        _startupHint.SetToolTip(_startAtLogin, Strings.Get("Run automatically when you log in. HostTap Remote keeps receiving phone input when the window is closed. Choose Quit to stop it."));
        _startAtLogin.CheckedChanged += (_, _) => ChangeStartup();
        if (!preview)
        {
            if (options.ManagesStartup)
            {
                _trayMenu = new ContextMenuStrip();
                _trayMenu.Items.Add(Strings.Get("Open HostTap Remote"), null, (_, _) => ShowReceiver());
                if (!StoreBuild.Packaged)
                {
                    _checkUpdates = new ToolStripMenuItem(Strings.Get("Check for Updates…"), null, async (_, _) => await CheckUpdatesAsync(manual: true));
                    _automaticUpdates = new ToolStripMenuItem(Strings.Get("Automatically Check for and Download Updates")) { CheckOnClick = true, Checked = true };
                    _automaticUpdates.Click += (_, _) => ChangeAutomaticUpdates();
                    _installUpdate = new ToolStripMenuItem(Strings.Get("Install Update and Restart…"), null, (_, _) => InstallUpdate()) { Enabled = false };
                    _trayMenu.Items.AddRange([_checkUpdates, _automaticUpdates, _installUpdate]);
                }
                _trayMenu.Items.Add(new ToolStripSeparator());
                _trayMenu.Items.Add(Strings.Get("Quit HostTap Remote"), null, (_, _) => QuitReceiver());
                _tray = new NotifyIcon { Icon = _brandIcon, Text = Strings.Get("HostTap Remote · Double-click to open"), ContextMenuStrip = _trayMenu, Visible = true };
                _tray.DoubleClick += (_, _) => ShowReceiver();
            }
            if (options.Startup) { WindowState = FormWindowState.Minimized; ShowInTaskbar = false; }
            _backend.StateReceived += state => OnUi(() => ApplyState(state));
            _backend.Failed += () => OnUi(ShowBackendFailure);
            Shown += (_, _) => { InitializeStartup(); StartBackend(); InitializeUpdates(); if (options.Startup) Hide(); };
            _updateTimer.Tick += async (_, _) => await CheckUpdatesAsync(manual: false);
        }
        FormClosing += ClosingAsync;
        _layoutReady = true;
        ResumeLayout(performLayout: true);
        LayoutContents();
    }

    private static Label TextLabel(string text, Color color) => new()
    {
        Text = text, ForeColor = color, BackColor = Color.Transparent,
        AutoSize = false, TextAlign = ContentAlignment.MiddleCenter, UseMnemonic = false,
        TabStop = false
    };

    private int S(int logicalPixels) => (int)Math.Round(logicalPixels * DeviceDpi / 96d);

    private int LabelHeight(Label label, int width, int minimum)
    {
        var measured = TextRenderer.MeasureText(label.Text, label.Font, new Size(width, int.MaxValue),
            TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix | TextFormatFlags.TextBoxControl);
        return Math.Max(S(minimum), measured.Height + S(4));
    }

    private void LayoutContents()
    {
        if (!_layoutReady || _layingOut || IsDisposed || _scroll.ClientSize.Width == 0) return;
        _layingOut = true;
        try
        {
            var outer = Math.Min(S(24), _scroll.ClientSize.Width / 12);
            // Never impose a content width larger than the viewport, including
            // when Windows clamps a high-DPI window to a small work area.
            var width = Math.Max(1, Math.Min(S(424), _scroll.ClientSize.Width - outer * 2));
            var y = S(30);
            var statusWidth = Math.Min(width, _status.GetPreferredSize(new Size(width, 0)).Width);
            var statusHeight = _status.GetPreferredSize(new Size(statusWidth, 0)).Height;
            _status.SetBounds((width - statusWidth) / 2, y, statusWidth, statusHeight);
            y += statusHeight + S(16);
            // The helper line is now the sole instruction heading; it replaces
            // the removed headline so the status badge leads the QR card.
            var helperHeight = LabelHeight(_helper, width, 24);
            _helper.SetBounds(0, y, width, helperHeight);
            y += helperHeight + S(24);
            var inset = Math.Min(S(20), width / 12);
            var innerWidth = width - inset * 2;
            var qrSize = Math.Max(1, Math.Min(Math.Min(S(224), innerWidth), _scroll.ClientSize.Height - y - S(40)));
            var cardY = S(20);
            _qr.SetBounds((width - qrSize) / 2, cardY, qrSize, qrSize);
            cardY += qrSize + S(16);
            if (width >= S(390))
            {
                var machineWidth = (int)(innerWidth * .45);
                var addressWidth = innerWidth - machineWidth - S(14);
                var rowHeight = LabelHeight(_machine, machineWidth, 32);
                _machine.SetBounds(inset, cardY, machineWidth, rowHeight);
                _addresses.SetBounds(inset + machineWidth + S(14), cardY + (rowHeight - _addresses.PreferredHeight) / 2,
                    addressWidth, _addresses.PreferredHeight);
                cardY += rowHeight;
            }
            else
            {
                var machineHeight = LabelHeight(_machine, innerWidth, 24);
                _machine.SetBounds(inset, cardY, innerWidth, machineHeight);
                cardY += machineHeight + S(8);
                _addresses.SetBounds(inset, cardY, innerWidth, _addresses.PreferredHeight);
                cardY += _addresses.PreferredHeight;
            }
            cardY += S(16);
            _copy.SetBounds(inset, cardY, innerWidth, S(44));
            cardY += S(44) + S(20);
            _card.SetBounds(0, y, width, cardY);
            y += cardY + S(17);
            var networkHeight = LabelHeight(_networkHint, width, 20);
            _networkHint.SetBounds(0, y, width, networkHeight);
            y += networkHeight + S(2);
            var privacyHeight = LabelHeight(_privacyHint, width, 20);
            _privacyHint.SetBounds(0, y, width, privacyHeight);
            y += privacyHeight + S(18);
            var buttonGap = Math.Min(S(20), width / 12);
            var buttonWidth = Math.Min(S(100), (width - buttonGap) / 2);
            _refresh.SetBounds((width - buttonWidth * 2 - buttonGap) / 2, y, buttonWidth, S(36));
            _quit.SetBounds(_refresh.Right + buttonGap, y, buttonWidth, S(36));
            y += S(36) + S(12);
            if (_startAtLogin.Visible)
            {
                _startAtLogin.SetBounds(Math.Max(0, (width - S(160)) / 2), y, Math.Min(width, S(160)), S(30));
                y += S(30);
            }
            y += S(24);
            // A top-docked scroll child follows the viewport width. Keeping the
            // centered column inside it prevents WinForms from using an old
            // child X offset as horizontal overflow during a resize.
            _column.SetBounds(Math.Max(outer, (_scroll.ClientSize.Width - width) / 2), 0, width, y);
            _content.Height = y;
            _scroll.AutoScrollMinSize = new Size(0, y);
        }
        finally { _layingOut = false; }
        var availableWidth = Math.Max(1, Math.Min(S(424), _scroll.ClientSize.Width - Math.Min(S(24), _scroll.ClientSize.Width / 12) * 2));
        if (_column.Width != availableWidth) LayoutContents();
    }

    protected override void OnLayout(LayoutEventArgs e)
    {
        // ContainerControl performs automatic scaling in its layout pass. A
        // final manual pass must follow it, not run only during handle creation.
        base.OnLayout(e);
        LayoutContents();
    }

    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        if (!_preview)
        {
            var workArea = Screen.FromControl(this).WorkingArea;
            FitWindowToWorkArea(workArea.Size);
            Location = new Point(workArea.Left + (workArea.Width - Width) / 2,
                workArea.Top + (workArea.Height - Height) / 2);
        }
        LayoutContents();
    }

    private void FitWindowToWorkArea(Size workArea)
    {
        MinimumSize = new Size(Math.Min(MinimumSize.Width, workArea.Width), Math.Min(MinimumSize.Height, workArea.Height));
        Size = new Size(Math.Min(Width, workArea.Width), Math.Min(Height, workArea.Height));
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        // Quit is initially the only enabled tab stop. Native initial focus can
        // scroll it into view; pairing must instead open at the complete QR.
        _scroll.AutoScrollPosition = Point.Empty;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Keep the real Windows frame and its standard controls. This only requests its dark appearance.
        try { var enabled = 1; _ = DwmSetWindowAttribute(Handle, 20, ref enabled, sizeof(int)); }
        catch (Exception error) when (error is DllNotFoundException or EntryPointNotFoundException) { }
        LayoutContents();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e) { base.OnDpiChanged(e); LayoutContents(); }
    protected override bool ShowWithoutActivation => _preview || _options.Startup || base.ShowWithoutActivation;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);

    private void StartBackend()
    {
        try
        {
            var executable = Path.Combine(AppContext.BaseDirectory, "SofaTapHost.exe");
            if (!File.Exists(executable)) throw new FileNotFoundException();
            _backend.Start(executable, _options.BackendArguments);
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or System.ComponentModel.Win32Exception)
        { ShowBackendFailure(); }
    }

    private void OnUi(Action action)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        try { BeginInvoke(action); }
        catch (InvalidOperationException) { }
    }

    // English source wording mirrors the macOS receiver's status title.
    private static string StatusText(ReceiverState state)
    {
        if (state.DryRun || state.InputStatus == "dry_run")
            return Strings.Format("Dry run · {0}", state.Connected ? Strings.Get("Phone connected") : Strings.Get("Waiting for iPhone"));
        if (state.InputStatus != "ready")
            return state.Connected ? Strings.Get("Phone connected · Desktop unavailable") : Strings.Get("Computer desktop unavailable");
        if (!state.Connected && state.Pairings.Length == 0) return Strings.Get("No pairing address available");
        return state.Connected ? Strings.Get("Phone connected · Desktop ready") : Strings.Get("Ready · Waiting for iPhone");
    }

    private void ApplyState(ReceiverState state)
    {
        if (_stopping) return;
        _machine.Text = state.Name;
        _machine.AccessibleDescription = state.Name;
        _status.Text = StatusText(state);
        _status.IndicatorColor = !state.DryRun && state.InputStatus == "ready" && (state.Connected || state.Pairings.Length > 0)
            ? MintCalm.Mint : MintCalm.Attention;
        var selected = _addresses.SelectedItem as string;
        _updating = true;
        _pairings = state.Pairings;
        _addresses.Items.Clear();
        _addresses.Items.AddRange(_pairings.Select(pairing => (object)pairing.Host).ToArray());
        var previous = Array.FindIndex(_pairings, pairing => pairing.Host == selected);
        _addresses.SelectedIndex = _pairings.Length == 0 ? -1 : Math.Max(0, previous);
        _addresses.Enabled = _pairings.Length > 0;
        _addresses.DropDownWidth = Math.Max(_addresses.Width,
            Math.Min(S(520), _pairings.Select(pairing => TextRenderer.MeasureText(pairing.Host, _addresses.Font).Width + S(36)).DefaultIfEmpty(0).Max()));
        _updating = false;
        _refresh.Enabled = true;
        RenderQr();
        LayoutContents();
        if (!_receivedInitialState)
        {
            _receivedInitialState = true;
            if (_healthTicket is not null)
            {
                try { UpdateApplication.MarkHealthy(_healthTicket); }
                catch (Exception error) when (WindowsUpdates.IsRecoverable(error)) { /* Helper will preserve/restore the previous version. */ }
            }
            _scroll.AutoScrollPosition = Point.Empty;
        }
    }

    private void RenderQr()
    {
        var index = _addresses.SelectedIndex;
        if (index < 0 || index >= _pairings.Length) { ClearQr(Strings.Get("No pairing address available\nConnect to a home network, then refresh")); return; }
        var pairing = _pairings[index];
        if (_renderedPng is null || !pairing.Png.AsSpan().SequenceEqual(_renderedPng))
        {
            try
            {
                using var stream = new MemoryStream(pairing.Png, writable: false);
                using var decoded = Image.FromStream(stream);
                _qr.SetImage(new Bitmap(decoded));
                _renderedPng = pairing.Png;
            }
            catch (Exception error) when (error is ArgumentException or OutOfMemoryException)
            { ClearQr(Strings.Get("QR code temporarily unavailable\nRefresh and try again")); return; }
        }
        var uri = NativeProtocol.ValidPairingUri(pairing.Uri, pairing.Host);
        if (_copyUri != uri) { _copyFeedback.Stop(); _copy.Text = Strings.Get("Copy Pairing Link"); }
        _copyUri = uri;
        _copy.Enabled = uri is not null && _qr.HasImage;
    }

    private void CopyPairingLink()
    {
        if (_copyUri is null || !_copy.Enabled || _stopping || _preview) return;
        try
        {
            // The only clipboard write in this frontend; never invoked by status refresh or input delivery.
            Clipboard.SetText(_copyUri);
            _copy.Text = Strings.Get("Pairing link copied");
        }
        catch (Exception error) when (error is ExternalException or System.Threading.ThreadStateException)
        { _copy.Text = Strings.Get("Unable to copy right now. Try again."); }
        _copyFeedback.Stop();
        _copyFeedback.Start();
    }

    private void ClearQr(string text)
    {
        _qr.SetImage(null);
        _renderedPng = null;
        _qr.Text = text;
        _copyUri = null;
        _copyFeedback.Stop();
        _copy.Enabled = false;
        _copy.Text = Strings.Get("Copy Pairing Link");
    }

    private void ShowBackendFailure()
    {
        if (_stopping) return;
        _refresh.Enabled = false;
        _pairings = [];
        _updating = true;
        _addresses.Items.Clear();
        _addresses.Enabled = false;
        _updating = false;
        _status.Text = Strings.Get("Receiver unavailable");
        _status.IndicatorColor = MintCalm.Attention;
        ClearQr(Strings.Get("Quit and reopen the receiver.\nMake sure the HostTap Remote package was extracted completely."));
        LayoutContents();
    }

    private async Task RefreshAsync()
    {
        if (_preview) return;
        try { await _backend.SendAsync("refresh"); }
        catch (Exception error) when (error is IOException or InvalidOperationException or ObjectDisposedException) { ShowBackendFailure(); }
    }

    private async void ClosingAsync(object? sender, FormClosingEventArgs args)
    {
        if (_preview || _allowClose) return;
        if (!_quitRequested && _tray is not null && args.CloseReason == CloseReason.UserClosing)
        {
            args.Cancel = true;
            Hide();
            return;
        }
        // Logoff/shutdown must not be cancelled for asynchronous UI teardown.
        if (args.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing)
        {
            _allowClose = true;
            return;
        }
        args.Cancel = true;
        if (_stopping) return;
        _stopping = true;
        _updateTimer.Stop();
        _refresh.Enabled = false;
        _quit.Enabled = false;
        _addresses.Enabled = false;
        _status.Text = Strings.Get("Quitting");
        ClearQr(Strings.Get("Stopping receiver…"));
        LayoutContents();
        try { await _backend.StopAsync(); }
        catch (Exception error) when (error is IOException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            _status.Text = Strings.Get("Receiver process has not exited");
            _qr.Text = Strings.Get("Try quitting again, or end SofaTapHost in Task Manager.");
            _status.IndicatorColor = MintCalm.Attention;
            _stopping = false;
            _quit.Enabled = true;
            LayoutContents();
            return;
        }
        if (_installUpdateRequested)
        {
            try { _ = UpdateApplication.StartPrepared(background: false); }
            catch (Exception error) when (WindowsUpdates.IsRecoverable(error))
            {
                MessageBox.Show(this, Strings.Get("Unable to install the update right now. The current version is unchanged; reopen the app and try again."),
                    Strings.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        }
        _allowClose = true;
        Close();
    }

    private void InitializeUpdates()
    {
        if (_preview || !_options.ManagesStartup || StoreBuild.Packaged) return;
        try
        {
            _updates = new WindowsUpdates();
            _updates.Changed += () => OnUi(UpdateMenus);
            UpdateMenus();
            _updateTimer.Start();
            _ = CheckUpdatesAsync(manual: false);
        }
        catch (Exception error) when (WindowsUpdates.IsRecoverable(error))
        {
            if (_automaticUpdates is not null) { _automaticUpdates.Checked = false; _automaticUpdates.Enabled = false; }
        }
    }

    private void UpdateMenus()
    {
        if (_updates is null) return;
        if (_automaticUpdates is not null) _automaticUpdates.Checked = _updates.Settings.Automatic;
        if (_installUpdate is not null) _installUpdate.Enabled = _updates.HasPreparedUpdate && !_stopping;
        if (_checkUpdates is not null)
        {
            _checkUpdates.Text = _updates.HasPreparedUpdate ? Strings.Get("Update downloaded · Installs next launch")
                : _updates.Settings.LastError is not null ? Strings.Get("Last update incomplete · Check for Updates…")
                : Strings.Get("Check for Updates…");
            _checkUpdates.ToolTipText = _updates.Settings.LastError ?? "";
        }
    }

    private async Task CheckUpdatesAsync(bool manual)
    {
        if (_preview || !_options.ManagesStartup || _stopping) return;
        try
        {
            _updates ??= new WindowsUpdates();
            var message = await _updates.CheckAsync(manual);
            UpdateMenus();
            if (manual && !_stopping && !IsDisposed)
                MessageBox.Show(this, message, Strings.Get("HostTap Remote Update"), MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception error) when (WindowsUpdates.IsRecoverable(error))
        {
            if (manual && !_stopping && !IsDisposed)
                MessageBox.Show(this, Strings.Get("Unable to complete the update check. Check the network and installation folder write permissions, then try again. The current version is unchanged."),
                    Strings.Get("HostTap Remote Update"), MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void ChangeAutomaticUpdates()
    {
        if (_updates is null || _automaticUpdates is null || _stopping) return;
        try { _updates.SetAutomatic(_automaticUpdates.Checked); UpdateMenus(); }
        catch (Exception error) when (WindowsUpdates.IsRecoverable(error))
        {
            _automaticUpdates.Checked = !_automaticUpdates.Checked;
            MessageBox.Show(this, Strings.Get("Unable to save update settings. Check the current user's file permissions."),
                Strings.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void InstallUpdate()
    {
        if (_updates?.HasPreparedUpdate != true || _stopping) return;
        if (MessageBox.Show(this, Strings.Get("Install the verified update now and restart the receiver? The current phone connection will disconnect briefly and then reconnect automatically."),
            Strings.Get("HostTap Remote Update"), MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
        _installUpdateRequested = true;
        QuitReceiver();
    }

    private void InitializeStartup()
    {
        if (!_options.ManagesStartup || _preview || StoreBuild.Packaged) return;
        try
        {
            _startup ??= new StartupRegistration(new WindowsStartupStore());
            SetStartupCheck(_startup.Initialize());
        }
        catch (Exception error) when (StartupRegistration.IsRecoverableError(error)) { ShowStartupFailure(); }
    }


    private void SetStartupCheck(bool enabled)
    {
        _updatingStartup = true;
        _startAtLogin.Checked = enabled;
        _updatingStartup = false;
    }

    private void ChangeStartup()
    {
        if (_updatingStartup || !_options.ManagesStartup || _preview) return;
        try
        {
            _startup ??= new StartupRegistration(new WindowsStartupStore());
            SetStartupCheck(_startup.SetEnabled(_startAtLogin.Checked));
        }
        catch (Exception error) when (StartupRegistration.IsRecoverableError(error))
        {
            SetStartupCheck(!_startAtLogin.Checked);
            ShowStartupFailure();
        }
    }

    private void ShowStartupFailure()
    {
        _startAtLogin.AccessibleDescription = Strings.Get("Unable to update automatic startup. Check the current user's startup folder permissions.");
        if (Visible && !_options.Startup)
            MessageBox.Show(this, Strings.Get("Unable to update automatic startup. Check the startup folder permissions, or look for HostTap Remote in Windows Settings → Apps → Startup."),
                Strings.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    internal void RequestShow() => OnUi(ShowReceiver);

    private void ShowReceiver()
    {
        if (_stopping) return;
        // Reopening after an HDMI/display change brings an off-screen window
        // into a current work area without changing any display configuration.
        if (!Screen.AllScreens.Any(screen => screen.WorkingArea.IntersectsWith(Bounds)))
        {
            var area = Screen.PrimaryScreen!.WorkingArea;
            FitWindowToWorkArea(area.Size);
            Location = new Point(area.Left + (area.Width - Width) / 2, area.Top + (area.Height - Height) / 2);
        }
        ShowInTaskbar = true;
        WindowState = FormWindowState.Normal;
        Show();
        Activate();
    }

    private void QuitReceiver() { _quitRequested = true; Close(); }

    internal void ShowPreviewFailure()
    {
        if (!_preview) throw new InvalidOperationException("Fixtures are available only in preview mode.");
        ShowBackendFailure();
    }

    internal bool PreviewHasQr => _preview && _qr.HasImage;
    internal bool PreviewCopyEnabled => _preview && _copy.Enabled;
    internal bool PreviewCanScroll => _preview && _scroll.VerticalScroll.Visible;
    internal bool PreviewHasHorizontalScroll => _preview && _scroll.HorizontalScroll.Visible;
    internal Rectangle PreviewQrViewportBounds => new(_scroll.PointToClient(_qr.PointToScreen(Point.Empty)), _qr.Size);
    internal Size PreviewViewportSize => _scroll.ClientSize;
    internal Point PreviewScrollPosition => _scroll.AutoScrollPosition;
    internal void FitPreviewToWorkArea(Size workArea)
    {
        if (!_preview) throw new InvalidOperationException("Fixtures are available only in preview mode.");
        FitWindowToWorkArea(workArea);
    }
    internal void ApplyPreviewState(ReceiverState state)
    {
        if (!_preview) throw new InvalidOperationException("Fixtures are available only in preview mode.");
        ApplyState(state);
    }
    internal void ScrollPreviewToBottom()
    {
        if (!_preview) throw new InvalidOperationException("Fixtures are available only in preview mode.");
        _scroll.AutoScrollPosition = new Point(0, _scroll.AutoScrollMinSize.Height);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { _updateTimer.Dispose(); _updates?.Dispose(); _tray?.Dispose(); _trayMenu?.Dispose(); _startupHint.Dispose(); _copyFeedback.Dispose(); _backend.Dispose(); _brandIcon.Dispose(); }
        base.Dispose(disposing);
    }
}
