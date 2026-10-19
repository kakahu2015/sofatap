package com.chekusu.keytrans;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** KeyTrans' user-selected Android keyboard. Network requests run off the IME thread. */
public final class KeyTransInputMethodService extends InputMethodService {
    private static final int BLUE = 0xff007aff;
    private static final int BG = 0xfff2f2f7;
    private static final int INK = 0xff1c1c1e;
    private static final int MUTED = 0xff77777d;
    private static final int MAX_TEXT_CODE_POINTS = 1500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newSingleThreadExecutor();

    private KeyTransPreferences preferences;
    private InputConnection connection;
    private EditorInfo editorInfo;
    private long editorEpoch;
    private long requestEpoch;
    private long feedbackEpoch;
    private boolean editorUsable;
    private boolean sensitive;
    private boolean typeMode;
    private boolean shiftEnabled = true;
    private boolean symbolMode;
    private boolean sourceExpanded;
    private boolean resultExpanded;
    private boolean translating;
    private boolean canSend;
    private boolean sourceIsInEditor;
    private int sourceLength;
    private String sourceText = "";
    private String translatedText = "";
    private String typedText = "";
    private String feedback = "";
    private View panel;
    private TextView statusView;
    private TextView sourceValueView;
    private TextView resultValueView;
    private TextView draftValueView;
    private TranslationClient.Cancellation cancellation;

    @Override public void onCreate() {
        super.onCreate();
        preferences = new KeyTransPreferences(this);
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        cancelTranslation();
        editorEpoch++;
        connection = getCurrentInputConnection();
        editorInfo = info;
        editorUsable = info != null && (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL
            && connection != null;
        sensitive = info != null && isSensitive(info);
        typeMode = preferences.startsInTypeMode();
        shiftEnabled = true;
        symbolMode = false;
        sourceExpanded = false;
        resultExpanded = false;
        sourceText = "";
        translatedText = "";
        typedText = "";
        feedback = "";
        canSend = false;
        sourceIsInEditor = false;
        sourceLength = 0;
        if (panel != null) renderPanel();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (preferences.autoTranslate() && editorUsable && !sensitive) {
            long expectedEditor = editorEpoch;
            main.postDelayed(() -> autoTranslateEditorText(expectedEditor), 150);
        }
    }

    @Override public void onFinishInput() {
        invalidateEditor();
        super.onFinishInput();
    }

    @Override public void onUnbindInput() {
        invalidateEditor();
        super.onUnbindInput();
    }

    @Override public void onDestroy() {
        invalidateEditor();
        requests.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        if (preferences == null) preferences = new KeyTransPreferences(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(7), dp(10), dp(5));
        panel.setBackgroundColor(BG);
        renderPanel();
        return panel;
    }

    private void renderPanel() {
        if (!(panel instanceof LinearLayout root)) return;
        root.removeAllViews();
        root.addView(createToolbar(), lp(-1, dp(34)));
        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setTextColor(translating ? BLUE : 0xffb42318);
        root.addView(statusView, lp(-1, dp(24)));
        updateStatus();
        if (typeMode) createTypePanel(root); else createTranslatePanel(root);
    }

    private View createToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(modeButton(R.string.ime_tab_translate, !typeMode, false), new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(modeButton(R.string.ime_tab_type, typeMode, true), new LinearLayout.LayoutParams(0, -1, 1));
        TextView settings = new TextView(this);
        settings.setText("⚙");
        settings.setTextSize(19);
        settings.setGravity(Gravity.CENTER);
        settings.setTextColor(BLUE);
        settings.setContentDescription(getString(R.string.ime_settings));
        settings.setOnClickListener(view -> openSettings());
        row.addView(settings, lp(dp(38), -1));
        return row;
    }

    private View modeButton(int title, boolean active, boolean selectType) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setTextColor(active ? 0xffffffff : BLUE);
        button.setBackground(MainActivity.buttonBackground(active ? BLUE : 0xffffffff));
        button.setOnClickListener(view -> {
            if (translating) {
                cancelTranslation();
                translatedText = "";
                canSend = false;
            }
            typeMode = selectType;
            preferences.setStartsInTypeMode(selectType);
            feedback = "";
            renderPanel();
        });
        return button;
    }

    private void createTranslatePanel(LinearLayout root) {
        root.addView(textCard(R.string.source_text, sourceText, R.string.source_placeholder, true),
            lp(-1, dp(sourceExpanded ? 155 : resultExpanded ? 55 : 105)));
        addGap(root, 5);
        root.addView(textCard(R.string.translation_result, translatedText, R.string.translation_placeholder, false),
            lp(-1, dp(resultExpanded ? 155 : sourceExpanded ? 55 : 105)));
        addGap(root, 6);
        root.addView(buttonRow(R.string.foreign_to_native, "⌫", 24,
            () -> translateClipboard(false), this::deleteOneFromEditor, false), lp(-1, dp(40)));
        addGap(root, 5);
        root.addView(buttonRow(R.string.native_to_foreign, getString(R.string.send), 14,
            () -> translateClipboard(true), this::sendTranslation, !canSend), lp(-1, dp(40)));
    }

    private View textCard(int titleId, String content, int placeholderId, boolean sourceCard) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(4), dp(5), dp(4));
        card.setBackground(cardBackground());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleId);
        title.setTextSize(12);
        title.setTextColor(MUTED);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView expand = new TextView(this);
        boolean expanded = sourceCard ? sourceExpanded : resultExpanded;
        expand.setText(expanded ? "⤡" : "⤢");
        expand.setTextSize(18);
        expand.setTextColor(BLUE);
        expand.setGravity(Gravity.CENTER);
        expand.setContentDescription(getString(titleId));
        expand.setOnClickListener(view -> {
            if (sourceCard) sourceExpanded = !sourceExpanded;
            else resultExpanded = !resultExpanded;
            renderPanel();
        });
        titleRow.addView(expand, lp(dp(30), dp(28)));
        card.addView(titleRow, lp(-1, dp(28)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        TextView value = new TextView(this);
        value.setText(content.isEmpty() ? getString(placeholderId) : content);
        value.setTextSize(14);
        value.setTextColor(content.isEmpty() ? 0xff99999f : INK);
        value.setMovementMethod(ScrollingMovementMethod.getInstance());
        value.setGravity(Gravity.TOP);
        value.setPadding(0, dp(2), dp(4), dp(2));
        scroll.addView(value, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (sourceCard) sourceValueView = value; else resultValueView = value;
        return card;
    }

    private void createTypePanel(LinearLayout root) {
        LinearLayout draftRow = new LinearLayout(this);
        draftRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView draft = new TextView(this);
        draft.setText(typedText.isEmpty() ? getString(R.string.type_here) : typedText);
        draft.setTextSize(16);
        draft.setTextColor(typedText.isEmpty() ? 0xff99999f : INK);
        draft.setPadding(dp(15), dp(6), dp(8), dp(6));
        draft.setBackground(cardBackground());
        draft.setGravity(Gravity.CENTER_VERTICAL);
        draft.setMaxLines(2);
        draft.setEllipsize(android.text.TextUtils.TruncateAt.END);
        draftValueView = draft;
        draftRow.addView(draft, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button clear = keyButton("×", this::clearDraft, false);
        clear.setContentDescription(getString(R.string.clear_draft));
        LinearLayout.LayoutParams clearParams = lp(dp(42), dp(42));
        clearParams.leftMargin = dp(6);
        draftRow.addView(clear, clearParams);
        root.addView(draftRow, lp(-1, dp(46)));
        addGap(root, 4);

        Button translate = button(getString(R.string.ai_translate), true, () -> translateDraft());
        translate.setTextSize(15);
        root.addView(translate, lp(-1, dp(38)));
        addGap(root, 4);

        if (symbolMode) createSymbolKeys(root); else createLetterKeys(root);
    }

    private void createLetterKeys(LinearLayout root) {
        createKeyRow(root, new String[]{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"}, null);
        createKeyRow(root, new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l"}, null);
        createKeyRow(root, new String[]{"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"}, new int[]{12, 9, 9, 9, 9, 9, 9, 9, 12});
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        addKey(bottom, "?123", 13, 12, () -> { symbolMode = true; renderPanel(); });
        addKey(bottom, ",", 8, 8, () -> appendDraft(","));
        addKey(bottom, "🌐", 8, 8, this::showKeyboardPicker);
        addKey(bottom, "space", 34, 8, () -> appendDraft(" "));
        addKey(bottom, ".", 8, 8, () -> appendDraft("."));
        addKey(bottom, "↵", 13, 12, this::sendDraftDirectly);
        root.addView(bottom, lp(-1, dp(42)));
    }

    private void createSymbolKeys(LinearLayout root) {
        createKeyRow(root, new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}, null);
        createKeyRow(root, new String[]{"@", "#", "$", "%", "&", "-", "+", "(", ")"}, null);
        LinearLayout third = new LinearLayout(this);
        addKey(third, "ABC", 13, 12, () -> { symbolMode = false; renderPanel(); });
        for (String value : new String[]{"*", "\"", "'", ":", ";", "!"}) addKey(third, value, 9, 8, () -> appendDraft(value));
        addKey(third, "⌫", 13, 12, this::deleteDraft);
        root.addView(third, lp(-1, dp(42)));
        LinearLayout bottom = new LinearLayout(this);
        addKey(bottom, "?123", 13, 12, () -> { symbolMode = false; renderPanel(); });
        addKey(bottom, ",", 8, 8, () -> appendDraft(","));
        addKey(bottom, "🌐", 8, 8, this::showKeyboardPicker);
        addKey(bottom, "space", 34, 8, () -> appendDraft(" "));
        addKey(bottom, ".", 8, 8, () -> appendDraft("."));
        addKey(bottom, "↵", 13, 12, this::sendDraftDirectly);
        root.addView(bottom, lp(-1, dp(42)));
    }

    private void createKeyRow(LinearLayout root, String[] keys, int[] weights) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            int weight = weights == null ? 10 : weights[i];
            Runnable action;
            if ("⇧".equals(key)) action = () -> { shiftEnabled = !shiftEnabled; renderPanel(); };
            else if ("⌫".equals(key)) action = this::deleteDraft;
            else action = () -> appendDraft(shiftEnabled ? key.toUpperCase(java.util.Locale.ROOT) : key);
            addKey(row, key, weight, 7, action);
        }
        root.addView(row, lp(-1, dp(42)));
    }

    private void addKey(LinearLayout row, String title, int weight, int horizontalMargin, Runnable action) {
        Button key = keyButton(title, action, false);
        key.setTextSize(title.length() > 1 && !"space".equals(title) ? 11 : 16);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, -1, weight);
        keyParams.leftMargin = dp(horizontalMargin / 2);
        keyParams.rightMargin = dp(horizontalMargin / 2);
        keyParams.bottomMargin = dp(4);
        row.addView(key, keyParams);
    }

    private View buttonRow(int leftTitle, String rightTitle, int rightSize,
                           Runnable leftAction, Runnable rightAction, boolean rightDisabled) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button left = button(getString(leftTitle), false, leftAction);
        row.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        View divider = new View(this);
        divider.setBackgroundColor(Color.WHITE);
        row.addView(divider, lp(dp(1), dp(24)));
        Button right = button(rightTitle, false, rightAction);
        right.setTextSize(rightSize);
        right.setEnabled(!rightDisabled);
        right.setAlpha(rightDisabled ? 0.5f : 1f);
        row.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        return row;
    }

    private Button keyButton(String title, Runnable action, boolean blue) {
        return button(title, blue, action);
    }

    private Button button(String title, boolean blue, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        button.setTextColor(blue ? Color.WHITE : INK);
        button.setBackground(MainActivity.buttonBackground(blue ? BLUE : Color.WHITE));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void translateClipboard(boolean motherToForeign) {
        if (!editorUsable || !isCurrentEditor()) return;
        if (sensitive) { showFeedback(getString(R.string.sensitive_field)); return; }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        if (value == null || value.toString().trim().isEmpty()) {
            showFeedback(getString(R.string.clipboard_empty));
            return;
        }
        String text = value.toString();
        if (clipboard != null) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        }
        startTranslation(text, motherToForeign, false, false);
    }

    private void autoTranslateEditorText(long expectedEditor) {
        if (expectedEditor != editorEpoch || !editorUsable || sensitive || !isCurrentEditor() || translating) return;
        InputConnection current = connection;
        CharSequence before = current == null ? null : current.getTextBeforeCursor(3002, 0);
        String text = before == null ? "" : before.toString().trim();
        if (text.isEmpty()) return;
        startTranslation(text, true, true, true);
    }

    private void translateDraft() {
        if (sensitive) { showFeedback(getString(R.string.sensitive_field)); return; }
        String text = typedText.trim();
        if (text.isEmpty()) { showFeedback(getString(R.string.empty_translation)); return; }
        startTranslation(text, true, false, true);
    }

    private void startTranslation(String text, boolean motherToForeign,
                                  boolean sourceInCurrentEditor, boolean insertWhenReady) {
        if (sensitive) { showFeedback(getString(R.string.sensitive_field)); return; }
        int length = text.codePointCount(0, text.length());
        if (length == 0) { showFeedback(getString(R.string.empty_translation)); return; }
        if (length > MAX_TEXT_CODE_POINTS) { showFeedback(getString(R.string.text_too_long)); return; }
        if (!isCurrentEditor()) return;
        String apiKey = preferences.apiKey();
        if (apiKey.isEmpty()) {
            showFeedback(getString(R.string.api_key_missing));
            return;
        }

        cancelTranslation();
        long requestId = ++requestEpoch;
        long editorId = editorEpoch;
        InputConnection target = connection;
        TranslationClient.Cancellation call = new TranslationClient.Cancellation();
        cancellation = call;
        String baseUrl = preferences.baseUrl();
        String model = preferences.model();
        String sourceLanguage = motherToForeign ? preferences.motherLanguage() : preferences.foreignLanguage();
        String targetLanguage = motherToForeign ? preferences.foreignLanguage() : preferences.motherLanguage();
        sourceText = text;
        translatedText = getString(R.string.translating);
        sourceIsInEditor = sourceInCurrentEditor;
        sourceLength = length;
        canSend = false;
        translating = true;
        feedback = "";
        updateVisibleText();
        updateStatus();

        requests.execute(() -> {
            try {
                String result = TranslationClient.translate(baseUrl, model, apiKey,
                    sourceLanguage, targetLanguage, text, call);
                main.post(() -> {
                    if (!isRequestCurrent(requestId, editorId, target)) return;
                    translating = false;
                    cancellation = null;
                    translatedText = result;
                    canSend = motherToForeign;
                    updateVisibleText();
                    updateStatus();
                    if (insertWhenReady || (motherToForeign && preferences.autoSendAfterTranslate())) {
                        sendTranslation();
                    }
                });
            } catch (CancellationException ignored) {
                // A replaced editor or newer request owns the IME now. Never replay late text.
            } catch (IOException failure) {
                main.post(() -> {
                    if (!isRequestCurrent(requestId, editorId, target)) return;
                    translating = false;
                    cancellation = null;
                    translatedText = "";
                    canSend = false;
                    updateVisibleText();
                    showFeedback(getString(R.string.translation_failed));
                });
            } catch (RuntimeException failure) {
                main.post(() -> {
                    if (!isRequestCurrent(requestId, editorId, target)) return;
                    translating = false;
                    cancellation = null;
                    translatedText = "";
                    canSend = false;
                    updateVisibleText();
                    showFeedback(getString(R.string.translation_failed));
                });
            }
        });
    }

    private boolean isRequestCurrent(long requestId, long editorId, InputConnection target) {
        return requestId == requestEpoch && editorId == editorEpoch && target == connection
            && editorUsable && !sensitive && isCurrentEditor();
    }

    private void sendTranslation() {
        String text = translatedText.trim();
        if (text.isEmpty() || translating) { showFeedback(getString(R.string.empty_translation)); return; }
        if (!canSend && !typeMode && !sourceIsInEditor) return;
        InputConnection current = connection;
        if (!editorUsable || current == null || !isCurrentEditor()) return;
        if (sourceIsInEditor && preferences.autoTranslate()) {
            if (preferences.deleteOriginal()) {
                if (!current.deleteSurroundingTextInCodePoints(sourceLength, 0)) {
                    showFeedback(getString(R.string.translation_failed));
                    return;
                }
            } else if (!current.commitText("\n", 1)) {
                showFeedback(getString(R.string.translation_failed));
                return;
            }
        }
        if (!current.commitText(text, 1)) {
            showFeedback(getString(R.string.translation_failed));
            return;
        }
        sourceText = "";
        translatedText = "";
        canSend = false;
        sourceIsInEditor = false;
        sourceLength = 0;
        if (typeMode) typedText = "";
        updateVisibleText();
        afterInsert(current);
    }

    private void deleteOneFromEditor() {
        if (!editorUsable || sensitive || !isCurrentEditor()) return;
        if (connection != null) connection.deleteSurroundingTextInCodePoints(1, 0);
    }

    private void appendDraft(String value) {
        if (typedText.codePointCount(0, typedText.length()) + value.codePointCount(0, value.length()) > MAX_TEXT_CODE_POINTS) {
            showFeedback(getString(R.string.text_too_long));
            return;
        }
        typedText += value;
        if (draftValueView != null) {
            draftValueView.setText(typedText.isEmpty() ? getString(R.string.type_here) : typedText);
            draftValueView.setTextColor(typedText.isEmpty() ? 0xff99999f : INK);
        }
    }

    private void deleteDraft() {
        if (typedText.isEmpty()) return;
        int end = typedText.offsetByCodePoints(typedText.length(), -1);
        typedText = typedText.substring(0, end);
        if (draftValueView != null) draftValueView.setText(typedText.isEmpty() ? getString(R.string.type_here) : typedText);
    }

    private void clearDraft() {
        typedText = "";
        shiftEnabled = true;
        if (draftValueView != null) {
            draftValueView.setText(R.string.type_here);
            draftValueView.setTextColor(0xff99999f);
        }
    }

    private void sendDraftDirectly() {
        String text = typedText.trim();
        if (text.isEmpty()) { showFeedback(getString(R.string.nothing_to_send)); return; }
        if (!editorUsable || !isCurrentEditor() || connection == null) return;
        if (!connection.commitText(text, 1)) { showFeedback(getString(R.string.translation_failed)); return; }
        typedText = "";
        shiftEnabled = true;
        updateVisibleText();
        afterInsert(connection);
    }

    private void afterInsert(InputConnection target) {
        if (target != connection || !isCurrentEditor()) return;
        if (preferences.autoLineBreak()) {
            int action = editorInfo == null ? EditorInfo.IME_ACTION_NONE
                : editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
            boolean submitted = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO
                || action == EditorInfo.IME_ACTION_DONE;
            if (submitted) target.performEditorAction(action);
            else target.commitText("\n", 1);
        }
        if (preferences.autoSwitchKeyboard()) {
            main.postDelayed(() -> {
                if (target == connection && isCurrentEditor()) {
                    try { switchToNextInputMethod(false); }
                    catch (RuntimeException ignored) { }
                }
            }, 10);
        }
    }

    private void updateVisibleText() {
        if (sourceValueView != null) sourceValueView.setText(sourceText.isEmpty() ? getString(R.string.source_placeholder) : sourceText);
        if (resultValueView != null) resultValueView.setText(translatedText.isEmpty() ? getString(R.string.translation_placeholder) : translatedText);
        if (draftValueView != null) {
            draftValueView.setText(typedText.isEmpty() ? getString(R.string.type_here) : typedText);
            draftValueView.setTextColor(typedText.isEmpty() ? 0xff99999f : INK);
        }
        if (panel != null) renderPanel();
        else updateStatus();
    }

    private void updateStatus() {
        if (statusView == null) return;
        String value = translating ? getString(R.string.translating) : feedback;
        statusView.setText(value);
        statusView.setTextColor(translating ? BLUE : 0xffb42318);
        statusView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showFeedback(String message) {
        feedback = message;
        long id = ++feedbackEpoch;
        updateStatus();
        main.postDelayed(() -> {
            if (id != feedbackEpoch) return;
            feedback = "";
            updateStatus();
        }, 2500);
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    private void showKeyboardPicker() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showInputMethodPicker();
    }

    private boolean isCurrentEditor() {
        return connection != null && getCurrentInputConnection() == connection;
    }

    private void cancelTranslation() {
        requestEpoch++;
        translating = false;
        if (cancellation != null) cancellation.cancel();
        cancellation = null;
    }

    private void invalidateEditor() {
        cancelTranslation();
        editorEpoch++;
        connection = null;
        editorInfo = null;
        editorUsable = false;
        sensitive = false;
        sourceText = "";
        translatedText = "";
        typedText = "";
        canSend = false;
        sourceIsInEditor = false;
        sourceLength = 0;
        feedback = "";
        if (panel != null) renderPanel();
    }

    static boolean isEnabled(Context context) {
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        if (manager == null) return false;
        ComponentName own = new ComponentName(context, KeyTransInputMethodService.class);
        try {
            for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
                if (own.equals(new ComponentName(info.getPackageName(), info.getServiceName()))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    static boolean isSelected(Context context) {
        ComponentName own = new ComponentName(context, KeyTransInputMethodService.class);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                InputMethodManager manager = context.getSystemService(InputMethodManager.class);
                InputMethodInfo info = manager == null ? null : manager.getCurrentInputMethodInfo();
                return info != null && own.equals(new ComponentName(info.getPackageName(), info.getServiceName()));
            }
            String selected = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return selected != null && own.equals(ComponentName.unflattenFromString(selected));
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean isSensitive(EditorInfo info) {
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            || (type == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
    }

    private void addGap(LinearLayout root, int height) { root.addView(new View(this), lp(-1, dp(height))); }

    private android.graphics.drawable.GradientDrawable cardBackground() {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(8));
        shape.setStroke(dp(1), 0xffd9d9de);
        return shape;
    }

    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
