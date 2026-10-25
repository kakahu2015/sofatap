package com.chekusu.sofatap.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Boot preference and current-run intent are deliberately separate. No pairing data lives here. */
final class ReceiverStartup {
    static final String PREFERENCES = "receiver-startup";
    private final SharedPreferences preferences;

    ReceiverStartup(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    void initializeFromVisibleLaunch() {
        if (!preferences.contains("initialized")) {
            preferences.edit().putBoolean("initialized", true).putBoolean("enabled", true)
                .putBoolean("run_requested", true).apply();
        }
    }

    boolean enabled() { return preferences.getBoolean("enabled", true); }
    void setEnabled(boolean value) { preferences.edit().putBoolean("enabled", value).apply(); }
    boolean runRequested() { return preferences.getBoolean("run_requested", false); }
    void setRunRequested(boolean value) { preferences.edit().putBoolean("run_requested", value).apply(); }
    boolean blocked() { return preferences.getBoolean("blocked", false); }
    void setBlocked(boolean value) { preferences.edit().putBoolean("blocked", value).apply(); }

    boolean shouldStartFor(String action) {
        if (!preferences.getBoolean("initialized", false) || !enabled()) return false;
        // Stop lasts for this run. An update must not undo Stop; the next real boot may start again.
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) && runRequested());
    }

    static boolean start(Context context) {
        ReceiverStartup settings = new ReceiverStartup(context);
        settings.setRunRequested(true);
        try {
            context.startForegroundService(new Intent(context, ReceiverService.class));
            settings.setBlocked(false);
            return true;
        } catch (IllegalStateException | SecurityException denied) {
            // OEM/OS policy is authoritative. The native screen offers a manual retry.
            settings.setBlocked(true);
            return false;
        }
    }

    static void stop(Context context) {
        ReceiverStartup settings = new ReceiverStartup(context);
        settings.setRunRequested(false);
        settings.setBlocked(false);
        context.stopService(new Intent(context, ReceiverService.class));
    }
}
