package com.chekusu.sofatap.tv;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.chekusu.sofatap.tv.input.InputBridge;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import org.json.JSONObject;
import java.util.*;

/** TV-native pairing and capability status. No browser or WebView. */
public final class MainActivity extends Activity {
    // Keep the TV receiver visually aligned with the Windows/macOS MintCalm palette.
    private static final int CANVAS = Color.rgb(0x11, 0x19, 0x1B);
    private static final int SURFACE = Color.rgb(0x1E, 0x2A, 0x2C);
    private static final int BORDER = Color.rgb(0x33, 0x43, 0x46);
    private static final int MINT = Color.rgb(0x98, 0xE9, 0xC4);
    private static final int TEXT = Color.rgb(0xF0, 0xF5, 0xF3);
    private static final int MUTED = Color.rgb(0xA8, 0xBA, 0xB7);
    private static final int WARNING = Color.rgb(0xE8, 0xC0, 0x8A);

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView qr;
    private View statusDot;
    private TextView qrCaption, status, help, capabilities, nativeControls;
    private Button start, pair, permission, enableIme, selectIme;
    private Switch autoStart;
    private ReceiverStartup startup;
    private ReceiverUpdates updates;
    private TextView updateStatus;
    private Button checkUpdate, installUpdate, cancelUpdate;
    private Switch autoUpdate;
    private Spinner network;
    private String renderedURI;
    private List<String> addresses = new ArrayList<>();
    private boolean active;
    private final Runnable tick = new Runnable() { @Override public void run() { if (active) { refresh(); main.postDelayed(this, 700); } } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startup = new ReceiverStartup(this); startup.initializeFromVisibleLaunch();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(42), dp(32), dp(42), dp(32));
        root.setBackgroundColor(CANVAS);

        // Pairing side: brand, desktop-style status pill, instruction and QR card.
        LinearLayout pairingColumn = new LinearLayout(this);
        pairingColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pairingParams = new LinearLayout.LayoutParams(0, -1, 0.48f);
        pairingParams.setMargins(0, 0, dp(30), 0);
        root.addView(pairingColumn, pairingParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.brand_mark);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text(28, TEXT);
        title.setText("HostTap Remote");
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        brand.addView(title);
        pairingColumn.addView(brand);

        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(13), dp(8), dp(13), dp(8));
        statusPill.setBackground(rounded(SURFACE, BORDER, 999, 1));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2);
        pillParams.topMargin = dp(24);
        pairingColumn.addView(statusPill, pillParams);

        statusDot = new View(this);
        statusDot.setBackground(circle(MUTED));
        statusPill.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = text(14, TEXT);
        status.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams statusTextParams = new LinearLayout.LayoutParams(-2, -2);
        statusTextParams.leftMargin = dp(8);
        statusPill.addView(status, statusTextParams);

        TextView instruction = text(15, MUTED);
        instruction.setText(R.string.tagline);
        instruction.setGravity(Gravity.START);
        padded(pairingColumn, instruction, 14);

        LinearLayout pairingCard = section();
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        cardParams.topMargin = dp(20);
        pairingColumn.addView(pairingCard, cardParams);

        // QR bearer credentials must not leak through screenshots/cast previews.
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription(getString(R.string.qr_description));
        qr.setBackground(rounded(Color.WHITE, Color.WHITE, 12, 0));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        qrParams.setMargins(dp(10), dp(10), dp(10), 0);
        pairingCard.addView(qr, qrParams);

        qrCaption = text(15, MINT);
        qrCaption.setGravity(Gravity.CENTER);
        padded(pairingCard, qrCaption, 14);

        network = new Spinner(this);
        network.setContentDescription(getString(R.string.network_address));
        network.setPopupBackgroundDrawable(rounded(SURFACE, BORDER, 10, 1));
        LinearLayout.LayoutParams networkParams = new LinearLayout.LayoutParams(-1, -2);
        networkParams.topMargin = dp(8);
        pairingCard.addView(network, networkParams);

        // Controls side: three calm surfaces mirroring desktop notices/cards/actions.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.52f));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(controls, new ScrollView.LayoutParams(-1, -2));

        LinearLayout receiverCard = section();
        controls.addView(receiverCard);
        help = text(15, MUTED);
        help.setLineSpacing(0, 1.12f);
        receiverCard.addView(help);

        permission = button(R.string.enable_accessibility, () -> {
            AlertDialog disclosure = new AlertDialog.Builder(this).setTitle(R.string.permission_needed)
                .setMessage(R.string.accessibility_service_description).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.enable_accessibility, (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (ActivityNotFoundException missing) { Toast.makeText(this, R.string.open_settings_manually, Toast.LENGTH_LONG).show(); }
                }).create();
            disclosure.show(); disclosure.getButton(AlertDialog.BUTTON_POSITIVE).setId(R.id.accessibility_setup);
        }, true);
        permission.setId(R.id.accessibility_setup); padded(receiverCard, permission, 16);

        start = button(R.string.start, () -> {
            if (ReceiverService.instance != null) { ReceiverStartup.stop(this); }
            else {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
                ReceiverStartup.start(this);
            }
            refresh();
        }, true); padded(receiverCard, start, 8);

        pair = button(R.string.new_pairing, () -> {
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.new_pairing).setMessage(R.string.replace_pairing)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.new_pairing, (d, w) -> {
                    ReceiverService receiver = ReceiverService.instance; if (receiver != null) receiver.newPairing(); refresh();
                }).create();
            dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setId(R.id.pairing_approve);
        }, false); pair.setId(R.id.pairing_approve); padded(receiverCard, pair, 8);

        autoStart = calmSwitch(R.string.auto_start);
        autoStart.setId(R.id.auto_start);
        autoStart.setChecked(startup.enabled());
        autoStart.setOnCheckedChangeListener((button, checked) -> {
            startup.setEnabled(checked);
            if (checked && ReceiverService.instance == null) ReceiverStartup.start(this);
            refresh();
        }); padded(receiverCard, autoStart, 14);
        TextView startupHelp = text(13, MUTED); startupHelp.setText(R.string.auto_start_help);
        padded(receiverCard, startupHelp, 2);

        LinearLayout nativeCard = section();
        LinearLayout.LayoutParams nativeParams = new LinearLayout.LayoutParams(-1, -2);
        nativeParams.topMargin = dp(14);
        controls.addView(nativeCard, nativeParams);
        nativeControls = text(15, TEXT);
        nativeControls.setLineSpacing(0, 1.15f);
        nativeCard.addView(nativeControls);
        enableIme = button(R.string.ime_enable, () -> {
            new AlertDialog.Builder(this).setTitle(R.string.ime_enable).setMessage(R.string.ime_disclosure)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.ime_enable, (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); }
                    catch (ActivityNotFoundException missing) { Toast.makeText(this, R.string.open_settings_manually, Toast.LENGTH_LONG).show(); }
                }).show();
        }, false); padded(nativeCard, enableIme, 12);
        selectIme = button(R.string.ime_select, () -> getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker(), false);
        padded(nativeCard, selectIme, 8);
        capabilities = text(13, MUTED); capabilities.setLineSpacing(0, 1.12f); padded(nativeCard, capabilities, 14);

        updates = ReceiverUpdates.get(this);
        LinearLayout updateCard = section();
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, -2);
        updateParams.topMargin = dp(14);
        controls.addView(updateCard, updateParams);
        updateStatus = text(15, MINT); updateCard.addView(updateStatus);
        autoUpdate = calmSwitch(R.string.update_automatic);
        autoUpdate.setId(R.id.auto_update);
        autoUpdate.setChecked(updates.automatic());
        autoUpdate.setVisibility(updates.supported() ? View.VISIBLE : View.GONE);
        autoUpdate.setOnCheckedChangeListener((button, checked) -> updates.setAutomatic(checked)); padded(updateCard, autoUpdate, 10);
        checkUpdate = button(R.string.update_check, () -> {
            if (updates.supported()) updates.check(true);
            else {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + getPackageName())).setPackage("com.android.vending")); }
                catch (ActivityNotFoundException missing) { Toast.makeText(this, R.string.update_store, Toast.LENGTH_LONG).show(); }
            }
            refresh();
        }, false); checkUpdate.setId(R.id.update_check); padded(updateCard, checkUpdate, 8);
        installUpdate = button(R.string.update_install, () -> { updates.install(this); refresh(); }, true);
        installUpdate.setId(R.id.update_install); padded(updateCard, installUpdate, 8);
        cancelUpdate = button(android.R.string.cancel, () -> {
            if (updates.hasInstaller()) updates.cancelInstallation(); else updates.cancelTransfer(); refresh();
        }, false); cancelUpdate.setId(R.id.update_cancel); padded(updateCard, cancelUpdate, 8);
        TextView updateHelp = text(13, MUTED); updateHelp.setText(R.string.update_help); padded(updateCard, updateHelp, 8);

        setContentView(root);
        renderQR(null);
        start.requestFocus();
        if (startup.runRequested() && ReceiverService.instance == null) ReceiverStartup.start(this);
    }

    private void refresh() {
        refreshUpdates();
        ReceiverService receiver = ReceiverService.instance;
        ReceiverIdentity identity = receiver == null ? null : receiver.identity;
        ReceiverServer server = receiver == null ? null : receiver.server;
        JSONObject probe = InputBridge.snapshot(this);
        boolean enabled = probe.optBoolean("inputAvailable");
        String nav = receiver == null ? "unavailable" : receiver.navigationStatus();
        boolean nativeReady = nav.equals("ready");
        boolean needsPermission = "accessibility_required".equals(probe.optString("inputStatus"));
        permission.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        start.setText(receiver == null ? R.string.start : R.string.stop);
        pair.setEnabled(receiver != null && receiver.authority != null);
        pair.setAlpha(pair.isEnabled() ? 1f : 0.4f);
        List<String> currentAddresses = identity == null ? Collections.emptyList() : identity.addresses;
        if (!addresses.equals(currentAddresses)) {
            addresses = new ArrayList<>(currentAddresses);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, addresses);
            network.setAdapter(adapter);
        }
        network.setVisibility(addresses.size() > 1 && receiver != null ? View.VISIBLE : View.GONE);
        String address = network.getSelectedItem() instanceof String ? (String)network.getSelectedItem() : addresses.isEmpty() ? "" : addresses.get(0);
        String uri = receiver == null ? null : receiver.pairingUri(address);
        if (!Objects.equals(uri, renderedURI)) { renderedURI = uri; renderQR(uri); }
        boolean connected = server != null && server.connected();
        if (receiver == null) {
            status.setText(startup.blocked() ? R.string.cannot_start : R.string.stopped);
            setStatusDot(startup.blocked() ? WARNING : MUTED);
            help.setText(startup.blocked() ? R.string.auto_start_blocked : R.string.start_help); qrCaption.setText(R.string.qr_waiting);
        } else if (receiver.error != null) {
            status.setText(R.string.cannot_start); setStatusDot(WARNING);
            help.setText("no_private_network".equals(receiver.error) ? R.string.connect_network : R.string.restart_help);
        } else if (server == null) {
            status.setText(R.string.starting); setStatusDot(MUTED); help.setText(R.string.start_help);
        } else {
            status.setText(nativeReady ? (connected ? R.string.connected : R.string.ready) : !enabled && !needsPermission ? R.string.input_paused : connected ? enabled ? R.string.connected : R.string.connected_permission : enabled ? R.string.ready : R.string.permission_needed);
            setStatusDot(nativeReady && (connected || uri != null) ? MINT : WARNING);
            help.setText(nativeReady ? R.string.native_remote_ready : enabled ? R.string.control_help : needsPermission ? R.string.permission_help : R.string.input_paused_help);
            qrCaption.setText(uri != null ? R.string.scan_qr : connected ? R.string.phone_connected : R.string.new_qr_help);
        }
        boolean imeEnabled = SofatapInputMethodService.isEnabled(this), imeSelected = SofatapInputMethodService.isSelected(this);
        enableIme.setVisibility(imeEnabled ? View.GONE : View.VISIBLE);
        selectIme.setVisibility(imeEnabled && !imeSelected ? View.VISIBLE : View.GONE);
        nativeControls.setText(getString(nav.equals("ready") ? R.string.native_remote_ready : nav.equals("pairing") ? R.string.native_remote_pairing : R.string.native_remote_setup)
            + "\n" + getString(imeSelected ? R.string.ime_selected_help : R.string.ime_setup_help));
        capabilities.setText(getString(R.string.probe_limitations) + "\n" + getString(R.string.probe_state,
            getString(probe.optBoolean("activeWindowReadable") && !probe.optBoolean("activeWindowProtected") ? R.string.available : R.string.unavailable)));
    }

    private void refreshUpdates() {
        String state = updates.status();
        int message = switch (state) {
            case "checking" -> R.string.update_checking;
            case "downloading" -> R.string.update_downloading;
            case "ready" -> R.string.update_ready;
            case "latest" -> R.string.update_latest;
            case "recent" -> R.string.update_recent;
            case "rate_limited" -> R.string.update_rate_limited;
            case "permission", "permission_missing" -> R.string.update_permission;
            case "preparing" -> R.string.update_preparing;
            case "confirm" -> R.string.update_confirmation;
            case "pending" -> R.string.update_pending;
            case "installed" -> R.string.update_installed;
            case "cancelled" -> R.string.update_cancelled;
            case "failed", "install_failed" -> R.string.update_failed;
            case "store" -> R.string.update_store;
            default -> R.string.update_current;
        };
        updateStatus.setText(getString(message, updates.version()));
        checkUpdate.setEnabled(!updates.busy() && !updates.hasInstaller());
        installUpdate.setVisibility(updates.canInstall() ? View.VISIBLE : View.GONE);
        installUpdate.setText(state.equals("confirm") ? R.string.update_continue : R.string.update_install);
        cancelUpdate.setVisibility(updates.busy() || updates.hasInstaller() ? View.VISIBLE : View.GONE);
    }

    private void renderQR(String uri) {
        if (uri == null) {
            qr.setImageResource(R.drawable.brand_mark);
            qr.setPadding(dp(54), dp(54), dp(54), dp(54));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class); hints.put(EncodeHintType.MARGIN, 4);
            BitMatrix matrix = new MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, 640, 640, hints);
            int[] pixels = new int[640 * 640];
            for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++) pixels[y * 640 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            qr.setPadding(dp(10), dp(10), dp(10), dp(10));
            qr.setImageBitmap(Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888));
        } catch (Exception error) { qr.setImageDrawable(null); qrCaption.setText(R.string.qr_failed); }
    }

    private LinearLayout section() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded(SURFACE, BORDER, 18, 1));
        return card;
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Switch calmSwitch(int label) {
        Switch value = new Switch(this);
        value.setText(label);
        value.setTextSize(15);
        value.setTextColor(TEXT);
        value.setPadding(dp(4), dp(5), dp(4), dp(5));
        return value;
    }

    private Button button(int label, Runnable action, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(android.graphics.Typeface.DEFAULT, primary ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setMinHeight(dp(48));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        applyButtonStyle(b, primary, false);
        b.setOnFocusChangeListener((v, focused) -> applyButtonStyle(b, primary, focused));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void applyButtonStyle(Button b, boolean primary, boolean focused) {
        int fill = primary ? (focused ? Color.rgb(0xAB, 0xEF, 0xD1) : MINT) : (focused ? Color.rgb(0x29, 0x3A, 0x3C) : SURFACE);
        int stroke = focused ? MINT : BORDER;
        b.setTextColor(primary ? CANVAS : TEXT);
        b.setBackground(rounded(fill, stroke, 10, focused ? 2 : 1));
        b.setScaleX(focused ? 1.02f : 1f);
        b.setScaleY(focused ? 1.02f : 1f);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    private GradientDrawable circle(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        return shape;
    }

    private void setStatusDot(int color) { statusDot.setBackground(circle(color)); }

    private void padded(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); active = true; main.post(tick); }
    @Override protected void onPause() { active = false; main.removeCallbacks(tick); super.onPause(); }
}
