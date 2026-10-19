package com.chekusu.keytrans;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Phone-side HostTap controller: mouse gestures and reviewed text delivery. */
final class HostTapRemoteView extends ScrollView implements HostTapClient.Listener {
    static final int REQUEST_QR_SCAN = 6412;
    private static final int BLUE = 0xff007aff;
    private static final int MINT = 0xff8ee3c1;
    private static final int INK = 0xff1c1c1e;
    private static final int MUTED = 0xff707075;
    private static final int PAGE = 0xfff2f2f7;
    private static final int PANEL = 0xffffffff;
    private final Activity activity;
    private final KeyTransPreferences preferences;
    private final HostTapClient client;
    private final TextView computerName, connectionStatus, deliveryStatus, textHelp;
    private final EditText pairingLink, draft;
    private final Button connectButton, dragButton, sendButton, resendButton, clearDraftButton;
    private final TouchPad touchPad;
    private final TouchPadGestures touchPadGestures;
    private final List<View> inputControls = new ArrayList<>();
    private boolean sending, uncertain, dragging, manualResend;
    private String sentSnapshot, lastSentDraft;

    HostTapRemoteView(Activity activity, KeyTransPreferences preferences) {
        super(activity);
        this.activity = activity;
        this.preferences = preferences;
        this.client = new HostTapClient(this);
        setFillViewport(true);
        setBackgroundColor(PAGE);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        addView(page, new LayoutParams(-1, -2));

        TextView title = text(R.string.hosttap_title, 29, INK, true);
        page.addView(title);
        TextView subtitle = text(R.string.hosttap_subtitle, 14, MUTED, false);
        page.addView(subtitle, topMargin(-1, -2, 3));

        LinearLayout connectionCard = card();
        page.addView(connectionCard, topMargin(-1, -2, 16));
        TextView pairHeading = text(R.string.hosttap_pairing, 17, INK, true);
        connectionCard.addView(pairHeading);
        computerName = text(R.string.hosttap_not_connected, 13, MUTED, false);
        connectionCard.addView(computerName, topMargin(-1, -2, 6));
        connectionStatus = text(R.string.hosttap_not_connected, 13, MUTED, false);
        connectionStatus.setPadding(dp(10), dp(8), dp(10), dp(8));
        connectionStatus.setBackground(background(0xfff0f2f4, 10));
        connectionCard.addView(connectionStatus, topMargin(-1, -2, 2));
        pairingLink = field(R.string.hosttap_pair_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        pairingLink.setSingleLine(true);
        connectionCard.addView(pairingLink, topMargin(-1, -2, 10));
        LinearLayout pairActions = new LinearLayout(activity);
        pairActions.setOrientation(LinearLayout.HORIZONTAL);
        connectionCard.addView(pairActions);
        Button scan = button(R.string.hosttap_scan_qr, false);
        Button paste = button(R.string.hosttap_paste, false);
        connectButton = button(R.string.hosttap_connect, true);
        pairActions.addView(scan, new LinearLayout.LayoutParams(0, dp(44), 1));
        pairActions.addView(paste, weightedMargin(0, dp(44), 1, 8, 0));
        connectionCard.addView(connectButton, topMargin(-1, dp(44), 8));
        Button forget = button(R.string.hosttap_forget, false);
        connectionCard.addView(forget, topMargin(-1, dp(40), 5));

        TextView mouseTitle = text(R.string.hosttap_touchpad, 17, INK, true);
        page.addView(mouseTitle, topMargin(-1, -2, 2));
        touchPad = new TouchPad(activity);
        touchPadGestures = new TouchPadGestures();
        page.addView(touchPad, topMargin(-1, dp(188), 8));
        LinearLayout mouseActions = new LinearLayout(activity);
        mouseActions.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(mouseActions, topMargin(-1, dp(44), 0));
        Button rightClick = button(R.string.hosttap_right_click, false);
        inputControls.add(rightClick);
        dragButton = button(R.string.hosttap_drag, false);
        mouseActions.addView(rightClick, new LinearLayout.LayoutParams(0, dp(40), 1));
        mouseActions.addView(dragButton, weightedMargin(0, dp(40), 1, 8, 0));

        TextView keysTitle = text(R.string.hosttap_keys, 17, INK, true);
        page.addView(keysTitle, topMargin(-1, -2, 16));
        LinearLayout keys = new LinearLayout(activity);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(keys, topMargin(-1, dp(42), 7));
        addKey(keys, "Esc", "escape"); addKey(keys, "Tab", "tab");
        addKey(keys, "↵", "enter"); addKey(keys, "←", "left");
        addKey(keys, "↑", "up"); addKey(keys, "↓", "down"); addKey(keys, "→", "right");
        Button backspace = button(R.string.hosttap_remote_backspace, false);
        inputControls.add(backspace);
        page.addView(backspace, topMargin(-1, dp(40), 6));

        LinearLayout textCard = card();
        page.addView(textCard, topMargin(-1, -2, 16));
        TextView textHeading = text(R.string.hosttap_text, 17, INK, true);
        textCard.addView(textHeading);
        textHelp = text(R.string.hosttap_not_connected, 12, MUTED, false);
        textCard.addView(textHelp, topMargin(-1, -2, 3));
        draft = field(R.string.hosttap_draft_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draft.setGravity(Gravity.TOP | Gravity.START);
        draft.setMinLines(3);
        draft.setMaxLines(7);
        draft.setPadding(dp(12), dp(11), dp(12), dp(11));
        draft.setText(preferences.hostTapDraft());
        String storedPrefix = preferences.hostTapSentPrefix();
        lastSentDraft = storedPrefix.isEmpty() ? null : storedPrefix;
        uncertain = preferences.hostTapUncertain();
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
        LinearLayout sendActions = new LinearLayout(activity);
        textCard.addView(sendActions, topMargin(-1, dp(44), 8));
        clearDraftButton = button(R.string.hosttap_clear, false);
        sendButton = button(R.string.hosttap_send, true);
        resendButton = button(R.string.hosttap_allow_resend, false);
        sendActions.addView(clearDraftButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        sendActions.addView(sendButton, weightedMargin(0, dp(44), 1, 8, 0));
        textCard.addView(resendButton, topMargin(-1, dp(40), 5));
        TextView privacy = text(R.string.hosttap_privacy, 12, MUTED, false);
        page.addView(privacy, topMargin(-1, -2, 12));

        scan.setOnClickListener(view -> scanPairingQr());
        paste.setOnClickListener(view -> pasteLink());
        connectButton.setOnClickListener(view -> connectFromField());
        forget.setOnClickListener(view -> forgetPairing());
        rightClick.setOnClickListener(view -> client.click("right"));
        dragButton.setOnClickListener(view -> setDragging(!dragging));
        backspace.setOnClickListener(view -> client.key("backspace"));
        clearDraftButton.setOnClickListener(view -> {
            draft.setText("");
            lastSentDraft = null;
            preferences.setHostTapSentPrefix("");
            uncertain = false; manualResend = false;
            preferences.setHostTapUncertain(false);
            deliveryStatus.setText(""); updateControls();
        });
        sendButton.setOnClickListener(view -> sendDraft());
        resendButton.setOnClickListener(view -> {
            uncertain = false;
            preferences.setHostTapUncertain(false);
            manualResend = true;
            deliveryStatus.setText(R.string.hosttap_send_confirmed);
            updateControls();
        });
        touchPad.setOnTouchListener(touchPadGestures);
        loadSavedPairing();
        updateControls();
    }

    void onVisible() {
        loadSavedPairing();
        HostTapPairing saved = savedPairing();
        if (saved != null && pairingLink.getText().toString().trim().isEmpty() &&
            !client.isConnected() && !client.isConnecting()) client.connect(saved);
    }

    void onHidden() {
        touchPadGestures.cancel();
        if (dragging) client.setDragging(false, result -> { });
        dragging = false;
        client.disconnect("未连接电脑");
        updateControls();
    }

    void setPairingLink(String link) {
        pairingLink.setText(link == null ? "" : link);
        pairingLink.setSelection(pairingLink.length());
    }

    void acceptScannedPairingLink(String link) {
        setPairingLink(link);
        connectFromField();
    }

    @Override public void onState(String message, boolean connecting, boolean connected, boolean inputAvailable,
                                  boolean protectedFocus, String name) {
        activity.runOnUiThread(() -> {
            if (computerName == null) return;
            computerName.setText(name == null || name.isEmpty() ? getString(R.string.hosttap_not_connected) : name);
            connectionStatus.setText(message);
            connectionStatus.setTextColor(connected && inputAvailable ? 0xff137a54 : MUTED);
            connectionStatus.setBackground(background(connected && inputAvailable ? 0xffe7f8f0 : 0xfff0f2f4, 10));
            if (protectedFocus) textHelp.setText("电脑当前是安全输入框，已阻止发送。");
            else if (connected && inputAvailable) textHelp.setText(R.string.hosttap_connected);
            else textHelp.setText(message);
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
                    lastSentDraft = sentSnapshot;
                    preferences.setHostTapSentPrefix(lastSentDraft);
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
                preferences.saveHostTapPairingLink(pairing.link);
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
        computerName.setText(pairing == null ? getString(R.string.hosttap_not_connected) :
            (pairing.name == null ? pairing.host : pairing.name));
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

    private void forgetPairing() {
        client.disconnect(getString(R.string.hosttap_pair_removed));
        preferences.clearHostTapPairing();
        loadSavedPairing();
        Toast.makeText(activity, R.string.hosttap_pair_removed, Toast.LENGTH_SHORT).show();
    }

    private void updateControls() {
        if (sendButton == null) return;
        boolean ready = client.canControl();
        String value = draft == null ? "" : draft.getText().toString();
        boolean hasDraft = !value.isEmpty() && value.length() <= 16_000;
        boolean appendOnly = lastSentDraft == null ||
            (value.startsWith(lastSentDraft) && value.length() > lastSentDraft.length());
        touchPad.setEnabled(ready);
        sendButton.setEnabled(ready && hasDraft && !sending && !uncertain && !client.isProtectedFocus() &&
            (manualResend || appendOnly));
        sendButton.setAlpha(sendButton.isEnabled() ? 1f : 0.48f);
        clearDraftButton.setEnabled(!sending);
        resendButton.setVisibility(uncertain || lastSentDraft != null ? VISIBLE : GONE);
        resendButton.setEnabled(ready && hasDraft && !sending);
        dragButton.setEnabled(ready);
        for (View control : inputControls) control.setEnabled(ready);
        if (deliveryStatus.getText().length() == 0 && client.isProtectedFocus())
            deliveryStatus.setText("电脑当前是安全输入框，已阻止发送。");
    }

    private void addKey(LinearLayout parent, String label, String key) {
        Button keyButton = button(label, false);
        keyButton.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(5);
        parent.addView(keyButton, params);
        inputControls.add(keyButton);
        keyButton.setOnClickListener(view -> client.key(key));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(13), dp(13), dp(13));
        card.setBackground(background(PANEL, 18));
        return card;
    }

    private EditText field(int hint, int inputType) {
        EditText value = new EditText(activity);
        value.setTextSize(15);
        value.setTextColor(INK);
        value.setHintTextColor(0xff929299);
        value.setHint(hint);
        value.setInputType(inputType);
        value.setBackground(background(0xfff3f4f7, 12));
        return value;
    }

    private Button button(int label, boolean primary) { return button(getString(label), primary); }
    private Button button(String label, boolean primary) {
        Button value = new Button(activity);
        value.setText(label);
        value.setTextSize(14);
        value.setAllCaps(false);
        value.setMinHeight(dp(40));
        value.setPadding(dp(7), 0, dp(7), 0);
        value.setTextColor(primary ? 0xff10231c : INK);
        value.setBackground(background(primary ? MINT : 0xffeef0f3, 12));
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

    private final class TouchPad extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TouchPad(Context context) {
            super(context);
            setBackground(background(0xff1d2b30, 20));
            setFocusable(true);
            setContentDescription(getString(R.string.hosttap_touchpad_help));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
            paint.setColor(MINT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
            canvas.drawRoundRect(centerX - dp(20), centerY - dp(35), centerX + dp(20), centerY + dp(20), dp(18), dp(18), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX, centerY + dp(9), dp(3), paint);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(dp(13));
            canvas.drawText(getString(R.string.hosttap_touchpad_help), centerX, centerY + dp(57), paint);
        }
    }

    private final class TouchPadGestures implements View.OnTouchListener {
        private float lastX, lastY, downX, downY;
        private boolean moved, scrolling;
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
                if (Math.abs(scrollVelocity) >= minimumFlingVelocity) touchPad.postOnAnimation(this);
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
                    moved = false; scrolling = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        scrolling = true;
                        moved = true;
                        lastY = lastScrollSampleY = averageY(event);
                        lastScrollSampleAt = event.getEventTime();
                        scrollVelocity = 0f;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() >= 2) {
                        scrolling = true; moved = true;
                        float y = averageY(event);
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
                    if (!moved && !scrolling) client.click("left");
                    if (scrolling) startFling(event.getEventTime());
                    releaseParentIntercept(view);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopFling();
                    releaseParentIntercept(view);
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
            touchPad.postOnAnimation(fling);
        }

        private void stopFling() {
            touchPad.removeCallbacks(fling);
            scrollVelocity = 0f;
            lastFlingAt = 0;
        }

        private void releaseParentIntercept(View view) {
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
        }

        void cancel() {
            stopFling();
            releaseParentIntercept(touchPad);
        }

        private float averageY(MotionEvent event) {
            float total = 0;
            int count = Math.min(event.getPointerCount(), 2);
            for (int i = 0; i < count; i++) total += event.getY(i);
            return total / Math.max(1, count);
        }
    }
}
