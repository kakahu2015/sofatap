package com.chekusu.hosttap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Phone-side HostTap controller: mouse gestures and reviewed text delivery. */
final class HostTapRemoteView extends ScrollView implements HostTapClient.Listener {
    static final int REQUEST_QR_SCAN = 6412;
    private static final int MINT = 0xffb8f3d0;
    private static final int INK = 0xfff3f6f5;
    private static final int MUTED = 0xff99a6b0;
    private static final int PAGE = 0xff0a0d11;
    private static final int PANEL = 0xff141920;
    private static final int PANEL_HIGHLIGHT = 0xff1c252d;
    private final Activity activity;
    private final HostTapPreferences preferences;
    private final HostTapClient client;
    private final TextView computerName, connectionStatus, deliveryStatus, textHelp;
    private final EditText pairingLink, draft;
    private final Button connectButton, dragButton, resendButton;
    private final Button keyboardButton;
    private final LinearLayout textCard;
    private final LinearLayout pairingList, pairedEntries;
    private final TouchPadGestures touchPadGestures;
    private final List<View> inputControls = new ArrayList<>();
    private final List<Button> arrowButtons = new ArrayList<>();
    private final Handler joystickHandler = new Handler(Looper.getMainLooper());
    private final Set<String> heldDirections = new HashSet<>();
    private final Set<String> joystickPointers = new HashSet<>();
    private final Set<String> activeModifiers = new HashSet<>();
    private final Map<String, Button> modifierButtons = new HashMap<>();
    private boolean sending, uncertain, dragging, manualResend, pairingExpanded;
    private boolean keyboardVisible;
    private boolean joystickMode;
    private String sentSnapshot, lastSentDraft;
    private ViewTreeObserver keyboardObserver;
    private final ViewTreeObserver.OnGlobalLayoutListener keyboardLayoutListener = this::syncKeyboardVisibility;
    private long joystickStartedAt;
    private final Runnable joystickTick = new Runnable() {
        @Override public void run() {
            if (!joystickMode || heldDirections.isEmpty() || !client.canControl()) {
                stopJoystick();
                return;
            }
            float dx = (heldDirections.contains("right") ? 1 : 0) - (heldDirections.contains("left") ? 1 : 0);
            float dy = (heldDirections.contains("down") ? 1 : 0) - (heldDirections.contains("up") ? 1 : 0);
            if (dx != 0 && dy != 0) { dx *= 0.7071f; dy *= 0.7071f; }
            long heldFor = SystemClock.uptimeMillis() - joystickStartedAt;
            float speed = Math.min(dp(16), dp(3) + heldFor / 90f * getResources().getDisplayMetrics().density);
            client.move(dx * speed, dy * speed);
            joystickHandler.postDelayed(this, 30);
        }
    };

    HostTapRemoteView(Activity activity, HostTapPreferences preferences) {
        super(activity);
        this.activity = activity;
        this.preferences = preferences;
        this.client = new HostTapClient(this);
        setFillViewport(true);
        setBackgroundColor(PAGE);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), dp(4));
        addView(page, new LayoutParams(-1, -2));

        LinearLayout connectionCard = card();
        connectionCard.setPadding(0, 0, 0, 0);
        connectionCard.setBackgroundColor(Color.TRANSPARENT);
        page.addView(connectionCard, topMargin(-1, -2, 0));
        LinearLayout connectionHeader = new LinearLayout(activity);
        connectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandMark = new ImageView(activity);
        brandMark.setImageResource(R.drawable.hosttap_mark);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        brandParams.rightMargin = dp(9);
        connectionHeader.addView(brandMark, brandParams);
        LinearLayout connectionCopy = new LinearLayout(activity);
        connectionCopy.setOrientation(LinearLayout.VERTICAL);
        connectionCopy.setGravity(Gravity.CENTER_VERTICAL);
        connectionHeader.addView(connectionCopy, new LinearLayout.LayoutParams(0, -1, 1));
        computerName = text(R.string.hosttap_not_connected, 14, INK, true);
        computerName.setSingleLine(true);
        computerName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        computerName.setClickable(true);
        computerName.setFocusable(true);
        connectionCopy.addView(computerName, new LinearLayout.LayoutParams(-1, -2));
        connectionStatus = text(R.string.hosttap_not_connected, 12, MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(3);
        connectionCopy.addView(connectionStatus, statusParams);
        Button guideButton = button("?", false);
        guideButton.setTextSize(20);
        guideButton.setContentDescription(getString(R.string.hosttap_guide));
        connectionHeader.addView(guideButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        Button settingsButton = button("⚙", false);
        settingsButton.setTextSize(18);
        settingsButton.setContentDescription(getString(R.string.hosttap_controls_title));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        settingsParams.leftMargin = dp(6);
        connectionHeader.addView(settingsButton, settingsParams);

        pairingList = new LinearLayout(activity);
        pairingList.setOrientation(LinearLayout.VERTICAL);
        pairingList.setVisibility(GONE);
        connectionCard.addView(pairingList, topMargin(-1, -2, 8));
        pairedEntries = new LinearLayout(activity);
        pairedEntries.setOrientation(LinearLayout.VERTICAL);
        pairingList.addView(pairedEntries);
        TextView addDevice = text(R.string.hosttap_pairing_add_device, 14, INK, true);
        pairingList.addView(addDevice, topMargin(-1, -2, 10));
        pairingLink = field(R.string.hosttap_pair_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pairingLink.setSingleLine(true);
        pairingList.addView(pairingLink, topMargin(-1, -2, 8));
        LinearLayout pairActions = new LinearLayout(activity);
        pairActions.setOrientation(LinearLayout.HORIZONTAL);
        pairingList.addView(pairActions);
        Button scan = button(R.string.hosttap_scan_qr, false);
        Button paste = button(R.string.hosttap_paste, false);
        connectButton = button(R.string.hosttap_connect, true);
        pairActions.addView(scan, new LinearLayout.LayoutParams(0, dp(44), 1));
        pairActions.addView(paste, weightedMargin(0, dp(44), 1, 8, 0));
        pairingList.addView(connectButton, topMargin(-1, dp(44), 8));

        touchPadGestures = new TouchPadGestures();
        FrameLayout touchSurface = new FrameLayout(activity);
        GradientDrawable touchSurfaceBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] { 0xff13211e, 0xff10171d });
        touchSurfaceBackground.setCornerRadius(dp(30));
        touchSurfaceBackground.setStroke(dp(1), 0xff283b38);
        touchSurface.setBackground(touchSurfaceBackground);
        touchSurface.setClickable(true);
        touchSurface.setFocusable(true);
        touchSurface.setContentDescription(getString(R.string.hosttap_touchpad) + ". " +
            getString(R.string.hosttap_touchpad_help));
        FrameLayout.LayoutParams connectionHeaderParams = new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP);
        connectionHeaderParams.leftMargin = dp(12);
        connectionHeaderParams.rightMargin = dp(12);
        connectionHeaderParams.topMargin = dp(8);
        touchSurface.addView(connectionHeader, connectionHeaderParams);
        LinearLayout touchSurfaceHint = new LinearLayout(activity);
        touchSurfaceHint.setOrientation(LinearLayout.VERTICAL);
        touchSurfaceHint.setGravity(Gravity.CENTER);
        TextView pointerMark = text("⌖", 44, MINT, false);
        pointerMark.setGravity(Gravity.CENTER);
        TextView padTitle = text(R.string.hosttap_touchpad, 14, INK, true);
        TextView padHint = text(R.string.hosttap_touchpad_help, 11, MUTED, false);
        padHint.setGravity(Gravity.CENTER);
        touchSurfaceHint.addView(pointerMark);
        touchSurfaceHint.addView(padTitle, topMargin(-1, -2, 3));
        touchSurfaceHint.addView(padHint, topMargin(-1, -2, 2));
        FrameLayout.LayoutParams touchHintParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        touchSurface.addView(touchSurfaceHint, touchHintParams);
        touchSurface.setOnTouchListener(touchPadGestures);
        touchSurface.setMinimumHeight(dp(170));
        LinearLayout.LayoutParams touchSurfaceParams = new LinearLayout.LayoutParams(-1, 0, 1);
        touchSurfaceParams.topMargin = dp(8);
        page.addView(touchSurface, touchSurfaceParams);
        LinearLayout mouseActions = new LinearLayout(activity);
        mouseActions.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(mouseActions, topMargin(-1, dp(42), 8));
        Button rightClick = button(R.string.hosttap_right_click, false);
        inputControls.add(rightClick);
        dragButton = button(R.string.hosttap_drag, false);
        mouseActions.addView(rightClick, new LinearLayout.LayoutParams(0, dp(42), 1));
        mouseActions.addView(dragButton, weightedMargin(0, dp(42), 1, 8, 0));

        LinearLayout keys = new LinearLayout(activity);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(keys, topMargin(-1, -2, 12));
        LinearLayout shortcutRows = new LinearLayout(activity);
        shortcutRows.setOrientation(LinearLayout.VERTICAL);
        keys.addView(shortcutRows, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout shortcutRow = new LinearLayout(activity);
        shortcutRows.addView(shortcutRow, new LinearLayout.LayoutParams(-1, dp(42)));
        addKey(shortcutRow, "Esc", "escape");
        addKey(shortcutRow, "Tab", "tab");
        addKey(shortcutRow, "↵", "enter");
        Button backspace = button(R.string.hosttap_remote_backspace, false);
        backspace.setTextSize(13);
        LinearLayout.LayoutParams backspaceParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        backspaceParams.leftMargin = dp(5);
        shortcutRow.addView(backspace, backspaceParams);
        inputControls.add(backspace);
        backspace.setOnClickListener(view -> sendRemoteKey("backspace"));

        LinearLayout modifierRow = new LinearLayout(activity);
        shortcutRows.addView(modifierRow, topMargin(-1, dp(42), 5));
        addModifier(modifierRow, "ctrl", "ctrl");
        addModifier(modifierRow, "Win", "win");
        addModifier(modifierRow, "alt", "alt");
        keyboardButton = button("⌨", false);
        keyboardButton.setTextSize(18);
        keyboardButton.setContentDescription(getString(R.string.hosttap_open_keyboard));
        LinearLayout.LayoutParams keyboardButtonParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        keyboardButtonParams.leftMargin = dp(5);
        modifierRow.addView(keyboardButton, keyboardButtonParams);

        LinearLayout dpad = new LinearLayout(activity);
        dpad.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dpadParams = new LinearLayout.LayoutParams(dp(132), dp(88));
        dpadParams.leftMargin = dp(8);
        keys.addView(dpad, dpadParams);
        LinearLayout dpadTop = new LinearLayout(activity);
        dpad.addView(dpadTop, new LinearLayout.LayoutParams(-1, 0, 1));
        addKey(dpadTop, "↑", "up");
        LinearLayout dpadBottom = new LinearLayout(activity);
        LinearLayout.LayoutParams dpadBottomParams = new LinearLayout.LayoutParams(-1, 0, 1);
        dpadBottomParams.topMargin = dp(4);
        dpad.addView(dpadBottom, dpadBottomParams);
        addKey(dpadBottom, "←", "left");
        addKey(dpadBottom, "↓", "down");
        addKey(dpadBottom, "→", "right");

        textCard = card();
        page.addView(textCard, topMargin(-1, -2, 16));
        textCard.setVisibility(GONE);
        LinearLayout textHeadingRow = new LinearLayout(activity);
        textHeadingRow.setGravity(Gravity.CENTER_VERTICAL);
        textCard.addView(textHeadingRow, topMargin(-1, dp(44), 0));
        TextView textHeading = text(R.string.hosttap_text, 17, INK, true);
        textHeadingRow.addView(textHeading, new LinearLayout.LayoutParams(0, -2, 1));
        Button closeComposerButton = button("×", false);
        closeComposerButton.setContentDescription(getString(R.string.hosttap_close_text));
        textHeadingRow.addView(closeComposerButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        textHelp = text(R.string.hosttap_not_connected, 12, MUTED, false);
        textCard.addView(textHelp, topMargin(-1, -2, 3));
        draft = draftField(R.string.hosttap_draft_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draft.setGravity(Gravity.TOP | Gravity.START);
        draft.setMinLines(3);
        draft.setMaxLines(7);
        draft.setPadding(dp(12), dp(11), dp(12), dp(11));
        draft.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        draft.setText(preferences.hostTapDraft());
        String storedPrefix = preferences.hostTapSentPrefix();
        lastSentDraft = storedPrefix.isEmpty() ? null : storedPrefix;
        uncertain = preferences.hostTapUncertain();
        if (uncertain) textCard.setVisibility(VISIBLE);
        textCard.addView(draft, topMargin(-1, -2, 8));
        deliveryStatus = text("", 12, MUTED, false);
        textCard.addView(deliveryStatus, topMargin(-1, -2, 0));
        if (uncertain) deliveryStatus.setText(R.string.hosttap_text_uncertain);
        else if (lastSentDraft != null) deliveryStatus.setText(R.string.hosttap_sent_status);
        draft.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                preferences.setHostTapDraft(s.toString());
                if (manualResend) manualResend = false;
                if (lastSentDraft != null && !s.toString().startsWith(lastSentDraft)) {
                    deliveryStatus.setText(R.string.hosttap_sent_prefix_edited);
                } else if (lastSentDraft != null && !uncertain && !sending) {
                    deliveryStatus.setText(R.string.hosttap_sent_status);
                }
                updateControls();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        draft.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_NULL ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN);
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.isShiftPressed())
                return false;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                sendDraft();
                return true;
            }
            return false;
        });
        draft.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ENTER || event.getAction() != KeyEvent.ACTION_DOWN ||
                event.isShiftPressed()) return false;
            sendDraft();
            return true;
        });
        resendButton = button(R.string.hosttap_allow_resend, false);
        textCard.addView(resendButton, topMargin(-1, dp(40), 5));

        computerName.setOnClickListener(view -> setPairingExpanded(!pairingExpanded));
        guideButton.setOnClickListener(view -> showGestureGuide());
        settingsButton.setOnClickListener(view -> showControllerSettings());
        scan.setOnClickListener(view -> scanPairingQr());
        paste.setOnClickListener(view -> pasteLink());
        connectButton.setOnClickListener(view -> connectFromField());
        rightClick.setOnClickListener(view -> client.click("right"));
        dragButton.setOnClickListener(view -> setDragging(!dragging));
        keyboardButton.setOnClickListener(view -> toggleSystemKeyboard());
        closeComposerButton.setOnClickListener(view -> closeComposer());
        resendButton.setOnClickListener(view -> {
            uncertain = false;
            preferences.setHostTapUncertain(false);
            manualResend = true;
            deliveryStatus.setText(R.string.hosttap_send_confirmed);
            updateControls();
        });
        loadSavedPairing();
        updateControls();
        updateKeyboardButton();
        updateArrowButtons();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        View decor = activity.getWindow().getDecorView();
        keyboardObserver = decor.getViewTreeObserver();
        if (keyboardObserver.isAlive()) keyboardObserver.addOnGlobalLayoutListener(keyboardLayoutListener);
        syncKeyboardVisibility();
    }

    @Override protected void onDetachedFromWindow() {
        stopJoystick();
        if (keyboardObserver != null && keyboardObserver.isAlive())
            keyboardObserver.removeOnGlobalLayoutListener(keyboardLayoutListener);
        keyboardObserver = null;
        super.onDetachedFromWindow();
    }

    void onVisible() {
        loadSavedPairing();
        HostTapPairing saved = savedPairing();
        if (saved != null && pairingLink.getText().toString().trim().isEmpty() &&
            !client.isConnected() && !client.isConnecting()) client.connect(saved);
    }

    void onHidden() {
        touchPadGestures.cancel();
        stopJoystick();
        if (dragging) client.setDragging(false, result -> { });
        dragging = false;
        hideSystemKeyboard();
        client.disconnect("未连接电脑");
        updateControls();
    }

    void setPairingLink(String link) {
        pairingLink.setText(link == null ? "" : link);
        pairingLink.setSelection(pairingLink.length());
        setPairingExpanded(true);
    }

    void acceptScannedPairingLink(String link) {
        setPairingLink(link);
        connectFromField();
    }

    @Override public void onState(String message, boolean connecting, boolean connected, boolean inputAvailable,
                                  boolean protectedFocus, String name) {
        activity.runOnUiThread(() -> {
            if (computerName == null) return;
            updatePairingSelector(savedPairing(), connected ? name : null);
            connectionStatus.setText(message);
            connectionStatus.setTextColor(protectedFocus ? 0xffffb38a : connected && inputAvailable ? MINT : MUTED);
            if (protectedFocus) textHelp.setText(R.string.hosttap_protected_focus);
            else if (connected && inputAvailable) textHelp.setText(R.string.hosttap_connected);
            else textHelp.setText(message);
            if (!connected || !inputAvailable || protectedFocus) stopJoystick();
            if (!connected && dragging) { dragging = false; dragButton.setText(R.string.hosttap_drag); }
            updateControls();
        });
    }

    private void handleAction(String type, HostTapClient.Result result) {
        if ("text".equals(type)) {
            sending = false;
            if (result.accepted) {
                String current = draft.getText().toString();
                if (current.equals(sentSnapshot) || current.startsWith(sentSnapshot)) {
                    String remaining = current.substring(sentSnapshot.length());
                    draft.setText(remaining);
                    draft.setSelection(draft.length());
                    lastSentDraft = null;
                    preferences.setHostTapSentPrefix("");
                    deliveryStatus.setText(R.string.hosttap_sent_status);
                    uncertain = false;
                    manualResend = false;
                    preferences.setHostTapUncertain(false);
                } else {
                    deliveryStatus.setText("发送已确认，但草稿已被修改。请检查电脑后再继续。");
                    uncertain = true;
                    preferences.setHostTapUncertain(true);
                    manualResend = false;
                }
            } else if (result.uncertain) {
                uncertain = true;
                preferences.setHostTapUncertain(true);
                deliveryStatus.setText(R.string.hosttap_text_uncertain);
            } else {
                deliveryStatus.setText(result.error);
            }
        }
        updateControls();
    }

    private void sendDraft() {
        String value = draft.getText().toString();
        if (value.isEmpty() || sending || uncertain || !client.canControl() || client.isProtectedFocus()) return;
        if (value.length() > 16_000) {
            deliveryStatus.setText("文字超过 16,000 个 UTF-16 单元，请分段发送。");
            return;
        }
        String payload = value;
        if (!manualResend && lastSentDraft != null) {
            if (!value.startsWith(lastSentDraft)) {
                deliveryStatus.setText(R.string.hosttap_sent_prefix_edited);
                return;
            }
            payload = value.substring(lastSentDraft.length());
            if (payload.isEmpty()) return;
        }
        sentSnapshot = value;
        sending = true;
        deliveryStatus.setText("正在等待电脑确认… 请勿重复发送。");
        updateControls();
        client.sendText(payload, result -> handleAction("text", result));
    }

    private void setDragging(boolean down) {
        if (!client.canControl()) return;
        dragButton.setEnabled(false);
        client.setDragging(down, result -> activity.runOnUiThread(() -> {
            dragButton.setEnabled(true);
            if (result.accepted) {
                dragging = down;
                dragButton.setText(down ? "松开拖拽" : getString(R.string.hosttap_drag));
            } else Toast.makeText(activity, result.error, Toast.LENGTH_SHORT).show();
            updateControls();
        }));
    }

    private void connectFromField() {
        String raw = pairingLink.getText().toString().trim();
        HostTapPairing pairing;
        try {
            if (!raw.isEmpty()) {
                pairing = HostTapPairing.parse(raw);
                preferences.rememberHostTapPairingLink(pairing.link);
                pairingLink.setText("");
                Toast.makeText(activity, R.string.hosttap_saved, Toast.LENGTH_SHORT).show();
            } else {
                pairing = savedPairing();
                if (pairing == null) throw new IllegalArgumentException(getString(R.string.hosttap_invalid_link));
            }
            client.connect(pairing);
            loadSavedPairing();
        } catch (Exception error) {
            Toast.makeText(activity, error.getMessage() == null ? getString(R.string.hosttap_invalid_link) : error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void scanPairingQr() {
        activity.startActivityForResult(new android.content.Intent(activity, QrScannerActivity.class), REQUEST_QR_SCAN);
    }

    private void loadSavedPairing() {
        HostTapPairing pairing = savedPairing();
        updatePairingSelector(pairing, null);
        renderPairingEntries();
    }

    private void updatePairingSelector(HostTapPairing pairing, String connectedName) {
        String name = connectedName;
        if (name == null || name.isEmpty()) {
            name = pairing == null ? getString(R.string.hosttap_not_connected) :
                (pairing.name == null || pairing.name.isEmpty() ? pairing.host : pairing.name);
        }
        boolean mac = pairing != null && "macos".equals(pairing.platform);
        Button metaModifier = modifierButtons.get("win");
        Button altModifier = modifierButtons.get("alt");
        if (metaModifier != null) metaModifier.setText(mac ? "⌘" : "Win");
        if (altModifier != null) altModifier.setText(mac ? "⌥" : "alt");
        computerName.setText(name + (pairingExpanded ? "   ▲" : "   ▼"));
        computerName.setContentDescription(name + ". " + getString(pairingExpanded ?
            R.string.hosttap_pairing_collapse : R.string.hosttap_pairing_expand));
    }

    private void setPairingExpanded(boolean expanded) {
        pairingExpanded = expanded;
        pairingList.setVisibility(expanded ? VISIBLE : GONE);
        updatePairingSelector(savedPairing(), null);
        if (expanded) renderPairingEntries();
    }

    private void renderPairingEntries() {
        pairedEntries.removeAllViews();
        List<String> links = preferences.hostTapPairingLinks();
        String selected = preferences.hostTapPairingLink();
        if (links.isEmpty()) {
            pairedEntries.addView(text(R.string.hosttap_pairing_empty, 13, MUTED, false),
                topMargin(-1, -2, 4));
            return;
        }
        for (String link : links) {
            final HostTapPairing pairing;
            try { pairing = HostTapPairing.parse(link); }
            catch (RuntimeException invalid) { continue; }
            String name = pairing.name == null || pairing.name.isEmpty() ? pairing.host : pairing.name;
            boolean isSelected = link.equals(selected);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button choose = button(name + (isSelected ? "  ✓" : ""), isSelected);
            choose.setSingleLine(true);
            choose.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Button remove = button("×", false);
            remove.setContentDescription(getString(R.string.hosttap_pairing_remove_device));
            row.addView(choose, new LinearLayout.LayoutParams(0, dp(42), 1));
            row.addView(remove, weightedMargin(dp(48), dp(42), 0, 8, 0));
            choose.setOnClickListener(view -> selectPairing(link));
            remove.setOnClickListener(view -> removePairing(link));
            pairedEntries.addView(row, topMargin(-1, dp(42), 5));
        }
    }

    private void selectPairing(String link) {
        try {
            HostTapPairing pairing = HostTapPairing.parse(link);
            preferences.saveHostTapPairingLink(pairing.link);
            client.connect(pairing);
            loadSavedPairing();
            setPairingExpanded(false);
        } catch (Exception error) {
            Toast.makeText(activity, error.getMessage() == null ? getString(R.string.hosttap_invalid_link) :
                error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void removePairing(String link) {
        String selected = preferences.hostTapPairingLink();
        List<String> links = preferences.hostTapPairingLinks();
        links.remove(link);
        try {
            preferences.saveHostTapPairingLinks(links);
            if (link.equals(selected)) {
                client.disconnect(getString(R.string.hosttap_pair_removed));
                if (links.isEmpty()) {
                    preferences.clearHostTapPairing();
                } else {
                    preferences.saveHostTapPairingLink(links.get(0));
                    client.connect(HostTapPairing.parse(links.get(0)));
                }
            }
            loadSavedPairing();
            Toast.makeText(activity, R.string.hosttap_pair_removed, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, error.getMessage() == null ? getString(R.string.hosttap_invalid_link) :
                error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private HostTapPairing savedPairing() {
        String raw = preferences.hostTapPairingLink();
        if (raw.isEmpty()) return null;
        try { return HostTapPairing.parse(raw); }
        catch (RuntimeException invalid) { preferences.clearHostTapPairing(); return null; }
    }

    private void pasteLink() {
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).coerceToText(activity) == null) {
            Toast.makeText(activity, R.string.hosttap_clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        pairingLink.setText(clip.getItemAt(0).coerceToText(activity).toString());
    }

    private void showGestureGuide() {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.hosttap_guide)
            .setMessage(R.string.hosttap_guide_message)
            .setPositiveButton(R.string.hosttap_done, null)
            .show();
    }

    private void showControllerSettings() {
        CharSequence[] modes = {
            getString(R.string.hosttap_keyboard_arrows),
            getString(R.string.hosttap_mouse_joystick)
        };
        new AlertDialog.Builder(activity)
            .setTitle(R.string.hosttap_controls_title)
            .setSingleChoiceItems(modes, joystickMode ? 1 : 0, (dialog, choice) -> {
                setJoystickMode(choice == 1);
                dialog.dismiss();
            })
            .setNegativeButton(R.string.hosttap_done, null)
            .show();
    }

    private void addModifier(LinearLayout parent, String label, String value) {
        Button modifier = button(label, false);
        modifier.setTextSize(13);
        modifier.setContentDescription(getString("ctrl".equals(value) ? R.string.hosttap_modifier_ctrl :
            "win".equals(value) ? R.string.hosttap_modifier_win : R.string.hosttap_modifier_alt));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(5);
        parent.addView(modifier, params);
        modifierButtons.put(value, modifier);
        inputControls.add(modifier);
        modifier.setOnClickListener(view -> {
            if (activeModifiers.contains(value)) activeModifiers.remove(value);
            else activeModifiers.add(value);
            updateModifierButtons();
        });
    }

    private void updateModifierButtons() {
        for (Map.Entry<String, Button> item : modifierButtons.entrySet()) {
            boolean selected = activeModifiers.contains(item.getKey());
            Button button = item.getValue();
            button.setSelected(selected);
            button.setTextColor(selected ? PAGE : INK);
            button.setBackground(background(selected ? MINT : PANEL_HIGHLIGHT, 14));
        }
    }

    private void sendRemoteKey(String key) {
        client.key(key, activeModifiers);
        if (!activeModifiers.isEmpty()) {
            activeModifiers.clear();
            updateModifierButtons();
        }
    }

    private void updateControls() {
        boolean ready = client.canControl();
        String value = draft == null ? "" : draft.getText().toString();
        boolean hasDraft = !value.isEmpty() && value.length() <= 16_000;
        resendButton.setVisibility(uncertain || lastSentDraft != null ? VISIBLE : GONE);
        resendButton.setEnabled(ready && hasDraft && !sending);
        dragButton.setEnabled(ready);
        for (View control : inputControls) control.setEnabled(ready);
        if (deliveryStatus.getText().length() == 0 && client.isProtectedFocus())
            deliveryStatus.setText(R.string.hosttap_protected_focus);
    }

    private void setJoystickMode(boolean enabled) {
        if (joystickMode == enabled) return;
        joystickMode = enabled;
        if (!enabled) stopJoystick();
        updateArrowButtons();
    }

    private void updateArrowButtons() {
        for (Button arrow : arrowButtons)
            arrow.setBackground(background(joystickMode ? 0xff20332b : PANEL_HIGHLIGHT, 12));
    }

    private void startJoystick(String direction) {
        if (!joystickMode || !client.canControl()) return;
        if (heldDirections.add(direction) && heldDirections.size() == 1)
            joystickStartedAt = SystemClock.uptimeMillis();
        joystickHandler.removeCallbacks(joystickTick);
        joystickHandler.post(joystickTick);
    }

    private void stopJoystickDirection(String direction) {
        heldDirections.remove(direction);
        if (heldDirections.isEmpty()) joystickHandler.removeCallbacks(joystickTick);
    }

    private void stopJoystick() {
        joystickHandler.removeCallbacks(joystickTick);
        heldDirections.clear();
    }

    private void toggleSystemKeyboard() {
        if (keyboardVisible) {
            hideSystemKeyboard();
            return;
        }
        textCard.setVisibility(VISIBLE);
        draft.requestFocus();
        draft.post(() -> {
            if (!draft.isAttachedToWindow()) return;
            fullScroll(View.FOCUS_DOWN);
            InputMethodManager manager = activity.getSystemService(InputMethodManager.class);
            if (manager != null) manager.showSoftInput(draft, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void closeComposer() {
        if (keyboardVisible) hideSystemKeyboard();
        draft.clearFocus();
        textCard.setVisibility(GONE);
        updateKeyboardButton();
    }

    private void hideSystemKeyboard() {
        InputMethodManager manager = activity.getSystemService(InputMethodManager.class);
        if (manager != null && draft.getWindowToken() != null)
            manager.hideSoftInputFromWindow(draft.getWindowToken(), 0);
    }

    private void syncKeyboardVisibility() {
        View decor = activity.getWindow().getDecorView();
        boolean visible;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()));
                return;
            }
        }
        Rect visibleFrame = new Rect();
        decor.getWindowVisibleDisplayFrame(visibleFrame);
        int obscuredHeight = decor.getRootView().getHeight() - visibleFrame.bottom;
        visible = obscuredHeight > dp(160);
        setKeyboardVisible(visible);
    }

    private void setKeyboardVisible(boolean visible) {
        if (keyboardVisible == visible && keyboardButton != null) return;
        keyboardVisible = visible;
        updateKeyboardButton();
    }

    private void updateKeyboardButton() {
        if (keyboardButton == null) return;
        keyboardButton.setContentDescription(getString(keyboardVisible ?
            R.string.hosttap_close_keyboard : R.string.hosttap_open_keyboard));
        keyboardButton.setSelected(keyboardVisible);
        keyboardButton.setTextColor(keyboardVisible ? PAGE : INK);
        keyboardButton.setBackground(background(keyboardVisible ? MINT : PANEL_HIGHLIGHT, 12));
    }

    private void addKey(LinearLayout parent, String label, String key) {
        Button keyButton = button(label, false);
        keyButton.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(5);
        parent.addView(keyButton, params);
        inputControls.add(keyButton);
        boolean arrow = "left".equals(key) || "right".equals(key) || "up".equals(key) || "down".equals(key);
        if (arrow) {
            arrowButtons.add(keyButton);
            keyButton.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN && joystickMode) {
                    joystickPointers.add(key);
                    startJoystick(key);
                    return true;
                }
                if (joystickPointers.contains(key)) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        joystickPointers.remove(key);
                        stopJoystickDirection(key);
                    }
                    return true;
                }
                return false;
            });
        }
        keyButton.setOnClickListener(view -> { if (!joystickMode) sendRemoteKey(key); });
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable shape = background(PANEL, 20);
        shape.setStroke(dp(1), 0xff242d35);
        card.setBackground(shape);
        return card;
    }

    private EditText field(int hint, int inputType) {
        EditText value = new EditText(activity);
        return styleField(value, hint, inputType);
    }

    private EditText draftField(int hint, int inputType) {
        return styleField(new SendActionEditText(activity), hint, inputType);
    }

    private EditText styleField(EditText value, int hint, int inputType) {
        value.setTextSize(15);
        value.setTextColor(INK);
        value.setHintTextColor(0xff7f8a93);
        value.setHint(hint);
        value.setInputType(inputType);
        value.setBackground(background(PANEL_HIGHLIGHT, 14));
        return value;
    }

    private Button button(int label, boolean primary) { return button(getString(label), primary); }
    private Button button(String label, boolean primary) {
        Button value = new Button(activity);
        value.setText(label);
        value.setTextSize(14);
        value.setAllCaps(false);
        value.setMinHeight(dp(40));
        value.setMinWidth(0);
        value.setPadding(dp(7), 0, dp(7), 0);
        value.setTextColor(primary ? PAGE : INK);
        value.setBackground(background(primary ? MINT : PANEL_HIGHLIGHT, 14));
        return value;
    }

    private TextView text(int value, int size, int color, boolean bold) {
        return text(getString(value), size, color, bold);
    }

    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable background(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private LinearLayout.LayoutParams topMargin(int width, int height, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(margin);
        return params;
    }

    private LinearLayout.LayoutParams weightedMargin(int width, int height, float weight, int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.leftMargin = dp(left); params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String getString(int resource) { return activity.getString(resource); }

    private static final class SendActionEditText extends EditText {
        SendActionEditText(Context context) { super(context); }

        @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection connection = super.onCreateInputConnection(outAttrs);
            if (connection != null) outAttrs.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
            return connection;
        }
    }

    private final class TouchPadGestures implements View.OnTouchListener {
        private float lastX, lastY, downX, downY;
        private boolean moved, scrolling, pageScrolling;
        private final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private final int minimumFlingVelocity = ViewConfiguration.get(activity).getScaledMinimumFlingVelocity();
        private final int maximumFlingVelocity = ViewConfiguration.get(activity).getScaledMaximumFlingVelocity();
        private float scrollVelocity, lastScrollSampleY;
        private long lastScrollSampleAt, lastFlingAt;
        private final Runnable fling = new Runnable() {
            @Override public void run() {
                if (!client.canControl() || Math.abs(scrollVelocity) < minimumFlingVelocity) {
                    stopFling();
                    return;
                }
                long now = SystemClock.uptimeMillis();
                float seconds = Math.min(0.05f, Math.max(0f, (now - lastFlingAt) / 1000f));
                if (seconds > 0f) {
                    client.scroll(scrollVelocity * seconds);
                    scrollVelocity *= (float) Math.exp(-5.0 * seconds);
                }
                lastFlingAt = now;
                if (Math.abs(scrollVelocity) >= minimumFlingVelocity) HostTapRemoteView.this.postOnAnimation(this);
                else stopFling();
            }
        };

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (!client.canControl()) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    stopFling();
                    releaseParentIntercept(view);
                }
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopFling();
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                    lastX = downX = event.getX(); lastY = downY = event.getY();
                    moved = false; scrolling = false; pageScrolling = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 3) {
                        pageScrolling = true;
                        scrolling = false;
                        moved = true;
                        lastY = averageY(event, event.getPointerCount());
                        stopFling();
                    } else if (event.getPointerCount() >= 2) {
                        scrolling = true;
                        moved = true;
                        lastY = lastScrollSampleY = averageY(event, 2);
                        lastScrollSampleAt = event.getEventTime();
                        scrollVelocity = 0f;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (pageScrolling && event.getPointerCount() >= 3) {
                        float y = averageY(event, event.getPointerCount());
                        smoothScrollBy(0, Math.round(lastY - y));
                        lastY = y;
                    } else if (pageScrolling) {
                        moved = true;
                    } else if (event.getPointerCount() >= 2) {
                        scrolling = true; moved = true;
                        float y = averageY(event, 2);
                        client.scroll(lastY - y);
                        sampleScrollVelocity(y, event.getEventTime());
                        lastY = y;
                    } else if (!scrolling) {
                        float x = event.getX(), y = event.getY();
                        float dx = x - lastX, dy = y - lastY;
                        if (Math.abs(x - downX) + Math.abs(y - downY) > touchSlop) moved = true;
                        if (moved) client.move(dx, dy);
                        lastX = x; lastY = y;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && !scrolling && !pageScrolling) client.click("left");
                    if (scrolling && !pageScrolling) startFling(event.getEventTime());
                    releaseParentIntercept(view);
                    moved = false; scrolling = false; pageScrolling = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopFling();
                    releaseParentIntercept(view);
                    moved = false; scrolling = false; pageScrolling = false;
                    return true;
                default: return true;
            }
        }

        private void sampleScrollVelocity(float y, long eventTime) {
            long elapsed = eventTime - lastScrollSampleAt;
            if (elapsed > 0 && elapsed <= 100) {
                float instantaneous = (lastScrollSampleY - y) * 1000f / elapsed;
                instantaneous = Math.max(-maximumFlingVelocity, Math.min(maximumFlingVelocity, instantaneous));
                scrollVelocity = scrollVelocity * 0.35f + instantaneous * 0.65f;
            } else {
                scrollVelocity = 0f;
            }
            lastScrollSampleY = y;
            lastScrollSampleAt = eventTime;
        }

        private void startFling(long eventTime) {
            long idle = eventTime - lastScrollSampleAt;
            if (idle > 100) scrollVelocity = 0f;
            else if (idle > 0) scrollVelocity *= (float) Math.exp(-5.0 * idle / 1000.0);
            if (Math.abs(scrollVelocity) < minimumFlingVelocity) {
                stopFling();
                return;
            }
            lastFlingAt = SystemClock.uptimeMillis();
            HostTapRemoteView.this.postOnAnimation(fling);
        }

        private void stopFling() {
            HostTapRemoteView.this.removeCallbacks(fling);
            scrollVelocity = 0f;
            lastFlingAt = 0;
        }

        private void releaseParentIntercept(View view) {
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
        }

        void cancel() {
            stopFling();
            releaseParentIntercept(HostTapRemoteView.this);
        }

        private float averageY(MotionEvent event, int pointerCount) {
            float total = 0;
            int count = Math.min(event.getPointerCount(), pointerCount);
            for (int i = 0; i < count; i++) total += event.getY(i);
            return total / Math.max(1, count);
        }
    }
}
