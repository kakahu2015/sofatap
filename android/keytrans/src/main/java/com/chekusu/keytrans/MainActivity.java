package com.chekusu.keytrans;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.security.GeneralSecurityException;

public final class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int MUTED = Color.rgb(112, 112, 117);
    private static final int PAGE = Color.rgb(242, 242, 247);
    private KeyTransPreferences preferences;
    private EditText apiKey, baseUrl, model, motherLanguage, foreignLanguage;
    private TextView keyStatus, imeStatus;
    private Switch typeMode, autoTranslate, deleteOriginal, autoSendAfterTranslate, autoLineBreak, autoSwitch;
    private FrameLayout pageHost;
    private View keyboardPage;
    private HostTapRemoteView remotePage;
    private Button computerTab, keyboardTab;
    private int activeTab = -1;
    private boolean activityResumed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new KeyTransPreferences(this);
        setContentView(createRoot());
        loadSettings();
        showTab(0);
        handlePairingIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (imeStatus != null) refreshImeStatus();
        if (keyStatus != null) refreshKeyStatus();
        if (activeTab == 0 && remotePage != null) remotePage.onVisible();
    }

    @Override protected void onPause() {
        activityResumed = false;
        if (remotePage != null) remotePage.onHidden();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if (!"sofatap".equalsIgnoreCase(data.getScheme()) || !"pair".equals(data.getHost())) return;
        String link = data.toString();
        if (remotePage != null) remotePage.setPairingLink(link);
        showTab(0);
    }

    private View createRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(7), dp(12), dp(7));
        tabs.setGravity(Gravity.CENTER);
        tabs.setBackgroundColor(Color.WHITE);
        computerTab = navButton(R.string.tab_computer);
        keyboardTab = navButton(R.string.tab_keyboard);
        tabs.addView(computerTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        keyboardParams.leftMargin = dp(8);
        tabs.addView(keyboardTab, keyboardParams);
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(56)));

        remotePage = new HostTapRemoteView(this, preferences);
        keyboardPage = createPage();
        computerTab.setOnClickListener(view -> showTab(0));
        keyboardTab.setOnClickListener(view -> showTab(1));
        return root;
    }

    private Button navButton(int label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void showTab(int tab) {
        if (tab == activeTab || pageHost == null) return;
        if (activeTab == 0 && remotePage != null) remotePage.onHidden();
        activeTab = tab;
        pageHost.removeAllViews();
        View page = tab == 0 ? remotePage : keyboardPage;
        if (page != null) pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
        computerTab.setSelected(tab == 0);
        keyboardTab.setSelected(tab == 1);
        computerTab.setTextColor(tab == 0 ? Color.WHITE : INK);
        computerTab.setBackground(navBackground(tab == 0));
        keyboardTab.setTextColor(tab == 1 ? Color.WHITE : INK);
        keyboardTab.setBackground(navBackground(tab == 1));
        if (tab == 0 && activityResumed && remotePage != null) remotePage.onVisible();
    }

    private GradientDrawable navBackground(boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(selected ? BLUE : Color.rgb(242, 242, 247));
        shape.setCornerRadius(dp(12));
        return shape;
    }

    private View createPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(page);

        TextView title = text("KeyTrans", 30, INK, true);
        page.addView(title);
        TextView tagline = text(R.string.tagline, 15, MUTED, false);
        LinearLayout.LayoutParams taglineParams = params(-1, -2);
        taglineParams.topMargin = dp(4);
        page.addView(tagline, taglineParams);

        LinearLayout setup = card(page, R.string.setup_section);
        imeStatus = text("", 14, MUTED, false);
        addPadded(setup, imeStatus, 0, 8);
        addButton(setup, R.string.open_ime_settings, this::openImeSettings);
        addButton(setup, R.string.select_keyboard, this::showKeyboardPicker);

        LinearLayout service = card(page, R.string.service_section);
        baseUrl = addField(service, R.string.base_url, "https://api.openai.com/v1/chat/completions", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        model = addField(service, R.string.model, "gpt-3.5-turbo", InputType.TYPE_CLASS_TEXT);
        motherLanguage = addField(service, R.string.mother_language, "Chinese (Simplified)", InputType.TYPE_CLASS_TEXT);
        foreignLanguage = addField(service, R.string.foreign_language, "English", InputType.TYPE_CLASS_TEXT);
        apiKey = addField(service, R.string.api_key, R.string.api_key_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setSingleLine(true);
        keyStatus = text("", 13, MUTED, false);
        addPadded(service, keyStatus, 2, 4);
        addButton(service, R.string.save_settings, this::saveSettings);
        addButton(service, R.string.api_key_clear, this::clearApiKey);

        LinearLayout options = card(page, R.string.mode_section);
        typeMode = addToggle(options, R.string.start_in_type_mode, R.string.start_in_type_help,
            preferences.startsInTypeMode(), enabled -> {
                preferences.setStartsInTypeMode(enabled);
                if (enabled && autoSwitch != null) {
                    autoSwitch.setOnCheckedChangeListener(null);
                    autoSwitch.setChecked(false);
                    autoSwitch.setOnCheckedChangeListener((button, checked) -> {
                        preferences.setAutoSwitchKeyboard(checked);
                        if (checked && typeMode != null) setTypeMode(false);
                    });
                    preferences.setAutoSwitchKeyboard(false);
                }
            });
        autoTranslate = addToggle(options, R.string.auto_translate, R.string.auto_translate_help,
            preferences.autoTranslate(), preferences::setAutoTranslate);
        deleteOriginal = addToggle(options, R.string.delete_original, 0,
            preferences.deleteOriginal(), preferences::setDeleteOriginal);
        autoSendAfterTranslate = addToggle(options, R.string.auto_send_after_translate, 0,
            preferences.autoSendAfterTranslate(), preferences::setAutoSendAfterTranslate);
        autoLineBreak = addToggle(options, R.string.auto_line_break, 0,
            preferences.autoLineBreak(), preferences::setAutoLineBreak);
        autoSwitch = addToggle(options, R.string.auto_switch_keyboard, 0,
            preferences.autoSwitchKeyboard(), enabled -> {
                preferences.setAutoSwitchKeyboard(enabled);
                if (enabled && typeMode != null) setTypeMode(false);
            });

        TextView privacy = text(R.string.privacy_note, 13, MUTED, false);
        LinearLayout.LayoutParams privacyParams = params(-1, -2);
        privacyParams.topMargin = dp(16);
        page.addView(privacy, privacyParams);
        return scroll;
    }

    private void loadSettings() {
        baseUrl.setText(preferences.baseUrl());
        model.setText(preferences.model());
        motherLanguage.setText(preferences.motherLanguage());
        foreignLanguage.setText(preferences.foreignLanguage());
        refreshImeStatus();
        refreshKeyStatus();
    }

    private void saveSettings() {
        String endpoint = baseUrl.getText().toString().trim();
        String modelValue = model.getText().toString().trim();
        if (modelValue.isEmpty()) {
            model.setError(getString(R.string.save_error_model));
            model.requestFocus();
            return;
        }
        try {
            TranslationClient.endpoint(endpoint);
        } catch (Exception invalid) {
            baseUrl.setError(getString(R.string.save_error_url));
            baseUrl.requestFocus();
            return;
        }

        preferences.saveSettings(endpoint, modelValue,
            nonEmpty(motherLanguage.getText().toString(), "Chinese (Simplified)"),
            nonEmpty(foreignLanguage.getText().toString(), "English"));
        try {
            String enteredKey = apiKey.getText().toString();
            if (!enteredKey.trim().isEmpty()) preferences.saveApiKey(enteredKey);
            apiKey.setText("");
            refreshKeyStatus();
            Toast.makeText(this, R.string.save_ok, Toast.LENGTH_SHORT).show();
        } catch (GeneralSecurityException error) {
            Toast.makeText(this, R.string.api_key_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void clearApiKey() {
        preferences.clearApiKey();
        apiKey.setText("");
        refreshKeyStatus();
        Toast.makeText(this, R.string.key_cleared, Toast.LENGTH_SHORT).show();
    }

    private void openImeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.open_ime_settings, Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeyboardPicker() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        if (manager != null) manager.showInputMethodPicker();
    }

    private void refreshImeStatus() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        boolean enabled = false;
        boolean selected = false;
        ComponentName own = new ComponentName(this, KeyTransInputMethodService.class);
        if (manager != null) {
            try {
                for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
                    if (own.equals(new ComponentName(info.getPackageName(), info.getServiceName()))) {
                        enabled = true;
                        break;
                    }
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    InputMethodInfo current = manager.getCurrentInputMethodInfo();
                    selected = current != null && own.equals(new ComponentName(current.getPackageName(), current.getServiceName()));
                } else {
                    String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                    selected = current != null && own.equals(ComponentName.unflattenFromString(current));
                }
            } catch (RuntimeException ignored) { }
        }
        imeStatus.setText(!enabled ? R.string.ime_status_disabled
            : selected ? R.string.ime_status_ready : R.string.ime_status_not_selected);
    }

    private void refreshKeyStatus() {
        keyStatus.setText(preferences.hasApiKey() ? R.string.api_key_saved : R.string.api_key_missing);
    }

    private void setTypeMode(boolean enabled) {
        typeMode.setOnCheckedChangeListener(null);
        typeMode.setChecked(enabled);
        typeMode.setOnCheckedChangeListener((button, checked) -> {
            preferences.setStartsInTypeMode(checked);
            if (checked && autoSwitch != null && autoSwitch.isChecked()) autoSwitch.setChecked(false);
        });
        preferences.setStartsInTypeMode(enabled);
    }

    private LinearLayout card(LinearLayout page, int heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), Color.rgb(226, 226, 231));
        card.setBackground(shape);
        LinearLayout.LayoutParams cardParams = params(-1, -2);
        cardParams.topMargin = dp(16);
        page.addView(card, cardParams);
        TextView title = text(heading, 17, INK, true);
        card.addView(title);
        return card;
    }

    private EditText addField(LinearLayout parent, int label, String hint, int inputType) {
        TextView title = text(label, 14, MUTED, false);
        addPadded(parent, title, 10, 4);
        EditText field = new EditText(this);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setHint(hint);
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setBackground(fieldBackground());
        parent.addView(field, params(-1, dp(46)));
        return field;
    }

    private EditText addField(LinearLayout parent, int label, int hint, int inputType) {
        EditText field = addField(parent, label, getString(hint), inputType);
        return field;
    }

    private Switch addToggle(LinearLayout parent, int titleId, int detailId, boolean initial,
                             android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(8));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleId, 15, INK, false);
        copy.addView(title);
        if (detailId != 0) {
            TextView detail = text(detailId, 12, MUTED, false);
            LinearLayout.LayoutParams detailParams = params(-1, -2);
            detailParams.topMargin = dp(3);
            copy.addView(detail, detailParams);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(initial);
        row.addView(toggle, params(-2, -2));
        toggle.setOnCheckedChangeListener(listener);
        parent.addView(row, params(-1, -2));
        return toggle;
    }

    private void addButton(LinearLayout parent, int title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(buttonBackground(BLUE));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams buttonParams = params(-1, dp(46));
        buttonParams.topMargin = dp(10);
        parent.addView(button, buttonParams);
    }

    private TextView text(int value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void addPadded(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams params = params(-1, -2);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        parent.addView(child, params);
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(PAGE);
        shape.setCornerRadius(dp(10));
        shape.setStroke(dp(1), Color.rgb(220, 220, 225));
        return shape;
    }

    static GradientDrawable buttonBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dpStatic(10));
        return shape;
    }

    private static String nonEmpty(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) { return dpStatic(value); }
    private static int dpStatic(int value) { return Math.round(value * android.content.res.Resources.getSystem().getDisplayMetrics().density); }
}
