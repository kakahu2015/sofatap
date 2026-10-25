package com.chekusu.sofatap.tv;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.chekusu.sofatap.tv.input.ImeSession;
import java.util.function.BooleanSupplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional, user-selected text input. It never injects global keys or reads editor contents. */
public final class SofatapInputMethodService extends InputMethodService {
    private static SofatapInputMethodService active;
    private final ImeSession session = new ImeSession();
    private InputConnection connection;

    @Override public void onCreate() {
        super.onCreate();
        if (active != null && active != this) active.clearEditor();
        active = this;
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        clearEditor();
        active = this;
        connection = getCurrentInputConnection();
        if (info == null || (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL || connection == null) return;
        InputConnection target = connection;
        session.start(new ImeSession.Editor() {
            private boolean valid() { return connection == target && getCurrentInputConnection() == target; }
            @Override public boolean commit(String text) { return valid() && target.commitText(text, 1); }
            @Override public boolean finishComposing() { return valid() && target.finishComposingText(); }
            @Override public boolean deleteCodePointBeforeCursor() {
                return valid() && target.deleteSurroundingTextInCodePoints(1, 0);
            }
            @Override public boolean action(int action) { return valid() && target.performEditorAction(action); }
        }, sensitive(info), info.initialSelStart, info.initialSelEnd, editorAction(info));
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd);
        session.selection(newStart, newEnd);
    }

    @Override public void onFinishInput() {
        clearEditor();
        super.onFinishInput();
    }

    @Override public void onUnbindInput() {
        clearEditor();
        super.onUnbindInput();
    }

    @Override public void onDestroy() {
        clearEditor();
        if (active == this) active = null;
        super.onDestroy();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        TextView hint = new TextView(this);
        hint.setText(R.string.ime_phone_hint);
        panel.addView(hint);
        Button switcher = new Button(this);
        switcher.setText(R.string.ime_switch_keyboard);
        switcher.setOnClickListener(view -> {
            InputMethodManager manager = getSystemService(InputMethodManager.class);
            if (manager != null) manager.showInputMethodPicker();
        });
        panel.addView(switcher);
        return panel;
    }

    public static JSONObject snapshot(Context context) {
        requireMain();
        String status = status(context);
        JSONObject result = object("textStatus", status);
        if ("ready".equals(status)) put(result, "editorEpoch", active.session.epoch());
        return result;
    }

    /** Call only after authentication, in ordered delivery, on Android main. */
    public static JSONObject apply(Context context, TVProtocol.Action action, BooleanSupplier current) {
        requireMain();
        if (current == null || !current.getAsBoolean()) return result(false, "session_expired");
        String status = status(context);
        if (!"ready".equals(status)) return result(false, status);
        if (action == null) return result(false, "unsupported_action");
        try {
            ImeSession.Result applied = active.session.apply(action.type(), action.editorEpoch(), action.text(), current);
            return result(applied.ok(), applied.code());
        } catch (RuntimeException ignored) {
            // Never log framework errors: they may contain private app/editor data.
            if (active != null) active.clearEditor();
            return result(false, "edit_rejected");
        }
    }

    public static void disconnected() {
        requireMain();
        if (active == null) return;
        if (active.connection != null && active.getCurrentInputConnection() == active.connection) {
            active.session.controllerDisconnected();
        } else active.clearEditor();
    }

    public static boolean isEnabled(Context context) {
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        if (manager == null) return false;
        ComponentName own = new ComponentName(context, SofatapInputMethodService.class);
        try {
            for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
                if (own.equals(new ComponentName(info.getPackageName(), info.getServiceName()))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    public static boolean isSelected(Context context) {
        try {
            ComponentName own = new ComponentName(context, SofatapInputMethodService.class);
            if (Build.VERSION.SDK_INT >= 34) {
                InputMethodManager manager = context.getSystemService(InputMethodManager.class);
                InputMethodInfo info = manager == null ? null : manager.getCurrentInputMethodInfo();
                return info != null && own.equals(new ComponentName(info.getPackageName(), info.getServiceName()));
            }
            String selected = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return selected != null && own.equals(ComponentName.unflattenFromString(selected));
        } catch (RuntimeException ignored) { return false; }
    }

    private static String status(Context context) {
        if (!isEnabled(context) || !isSelected(context)) return "ime_required";
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        if (keyguard == null || power == null || keyguard.isKeyguardLocked()
                || keyguard.isDeviceLocked() || !power.isInteractive()) {
            if (active != null) active.clearEditor();
            return "sensitive";
        }
        if (active == null) return "no_editor";
        if (active.connection == null || active.getCurrentInputConnection() != active.connection) {
            active.clearEditor();
            return "no_editor";
        }
        return active.session.status();
    }

    static boolean sensitive(EditorInfo info) {
        int type = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            || (type == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));
    }

    private static int editorAction(EditorInfo info) {
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        // Only standard actions explicitly advertised by this editor; no arbitrary ID from a phone.
        return action >= EditorInfo.IME_ACTION_GO && action <= EditorInfo.IME_ACTION_PREVIOUS ? action : 0;
    }

    private void clearEditor() { session.invalidate(); connection = null; }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("IME requires the main thread");
    }
    private static JSONObject result(boolean ok, String code) { return object("ok", ok, "code", code); }
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(value, (String) pairs[i], pairs[i + 1]);
        return value;
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException impossible) { throw new IllegalArgumentException("Invalid JSON field"); }
    }
}
