package com.chekusu.sofatap.tv;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.view.View;
import android.widget.Switch;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.chekusu.sofatap.tv.update.UpdatePolicy;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** No external network, input injection, permission grant or installation. Uses only an owned headless emulator. */
@RunWith(AndroidJUnit4.class)
public final class ReceiverUpdateDeviceTest {
    private Context context, fixture;
    private SharedPreferences prefs;
    private File directory;
    private ReceiverUpdates updates;
    private PackageInstaller installer;
    private int created = -1;
    @Before public void emulatorOnly() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
        Assume.assumeTrue("Only the owned disposable emulator is allowed", "yes".equals(InstrumentationRegistry.getArguments().getString("disposableEmulator")) && (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")));
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "update-device-test-").toFile();
        prefs = context.getSharedPreferences("update-device-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        fixture = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
            @Override public File getCacheDir() { return directory; }
        };
        installer = context.getPackageManager().getPackageInstaller(); updates = new ReceiverUpdates(fixture);
    }
    @After public void cleanup() {
        if (updates != null) { updates.serviceStopped(); updates.cancelInstallation(); }
        if (created >= 0) { try { installer.abandonSession(created); } catch (Exception ignored) { } }
        if (prefs != null) prefs.edit().clear().commit();
        if (directory != null) remove(directory);
    }
    private static void remove(File file) { File[] files = file.listFiles(); if (files != null) for (File child : files) remove(child); file.delete(); }
    private UpdatePolicy.Release release() {
        String version = "0.3.0";
        return new UpdatePolicy.Release(version, "https://github.com/chekusu/sofatap/releases/download/android-tv-v" + version + "/Sofatap-Android-TV-" + version + ".apk", 3, "1".repeat(64));
    }
    private void session() throws Exception {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL); params.setAppPackageName(UpdatePolicy.PACKAGE);
        created = installer.createSession(params);
        prefs.edit().putInt("install_session", created).putString("install_nonce", "private-test-nonce").commit();
        updates = new ReceiverUpdates(fixture);
    }
    @Test public void defaultsOnButOptOutPersistsAndStoppedServiceDoesNotCheck() {
        assertTrue(updates.automatic()); updates.setAutomatic(false); assertFalse(new ReceiverUpdates(fixture).automatic());
        updates.check(false); assertFalse(updates.busy()); assertFalse(prefs.contains("last_attempt"));
        updates.setAutomatic(true); updates.serviceStopped(); updates.check(false); assertFalse(updates.busy()); assertFalse(prefs.contains("last_attempt"));
    }
    @Test public void restoredPendingInstallerDoesNotDuplicateAndCanBeCancelled() throws Exception {
        session(); assertTrue(updates.hasInstaller()); assertEquals("pending", updates.status());
        int before = installer.getMySessions().size(); updates.check(true);
        assertEquals(before, installer.getMySessions().size()); assertFalse(updates.busy()); assertFalse(prefs.contains("last_attempt"));
        updates.cancelInstallation(); assertFalse(updates.hasInstaller()); assertEquals("cancelled", updates.status()); assertNull(installer.getSessionInfo(created));
    }
    @Test public void installerCallbackRequiresMatchingSessionAndNonce() throws Exception {
        session();
        Intent wrong = new Intent().putExtra("nonce", "wrong").putExtra(PackageInstaller.EXTRA_SESSION_ID, created).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED);
        updates.installerResult(wrong); assertTrue(updates.hasInstaller());
        wrong.putExtra("nonce", "private-test-nonce").putExtra(PackageInstaller.EXTRA_SESSION_ID, created + 1); updates.installerResult(wrong); assertTrue(updates.hasInstaller());
        wrong.putExtra(PackageInstaller.EXTRA_SESSION_ID, created); updates.installerResult(wrong); assertFalse(updates.hasInstaller()); assertEquals("cancelled", updates.status());
    }
    @Test public void pendingConfirmationIsStoredWithoutOpeningSystemUi() throws Exception {
        session();
        Intent pending = new Intent().putExtra("nonce", "private-test-nonce").putExtra(PackageInstaller.EXTRA_SESSION_ID, created)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, new Intent("android.intent.action.VIEW"));
        updates.installerResult(pending); assertEquals("confirm", updates.status()); assertTrue(updates.hasInstaller());
        // A new process loses the in-memory confirmation Intent, retains the session and offers cancellation/retry.
        updates = new ReceiverUpdates(fixture); assertEquals("pending", updates.status()); assertTrue(updates.hasInstaller());
        updates.cancelInstallation(); assertFalse(updates.hasInstaller());
    }
    @Test public void actualAndroidArchiveParserRejectsWrongPackageTruncatedApkAndSameVersion() throws Exception {
        File installed = new File(context.getApplicationInfo().sourceDir);
        assertThrows(IOException.class, () -> updates.verifyArchive(installed, release()));
        File testPackage = new File(InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().sourceDir);
        assertThrows(IOException.class, () -> updates.verifyArchive(testPackage, release()));
        File corrupt = new File(directory, "corrupt.apk"); Files.write(corrupt.toPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> updates.verifyArchive(corrupt, release()));
    }
    @Test public void validThrottleDoesNotStartNetworkFromRepeatedManualCheck() {
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).commit();
        updates.check(true); assertEquals("recent", updates.status()); assertFalse(updates.busy());
        prefs.edit().putLong("retry_after", System.currentTimeMillis() + 3600000).commit();
        updates.check(true); assertFalse(updates.busy());
    }
    @Test public void nativeUpdateControlsAndOptOutSurviveActivityRecreation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ReceiverStartup startup = new ReceiverStartup(context); startup.initializeFromVisibleLaunch(); startup.setRunRequested(false); startup.setEnabled(false);
        SharedPreferences settings = context.getSharedPreferences("receiver_updates", Context.MODE_PRIVATE);
        settings.edit().putBoolean("automatic", true).putLong("last_attempt", System.currentTimeMillis()).commit();
        MainActivity first = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                assertTrue(((Switch)first.findViewById(R.id.auto_update)).isChecked());
                assertTrue(first.findViewById(R.id.update_check).isEnabled());
                ((Switch)first.findViewById(R.id.auto_update)).performClick();
                first.findViewById(R.id.update_check).performClick();
                assertEquals(View.GONE, first.findViewById(R.id.update_install).getVisibility());
                assertEquals(View.GONE, first.findViewById(R.id.update_cancel).getVisibility());
            });
            assertFalse(ReceiverUpdates.get(context).automatic()); assertFalse(ReceiverUpdates.get(context).busy());
        } finally { instrumentation.runOnMainSync(first::finish); }
        MainActivity second = (MainActivity)instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { instrumentation.runOnMainSync(() -> assertFalse(((Switch)second.findViewById(R.id.auto_update)).isChecked())); }
        finally { instrumentation.runOnMainSync(second::finish); }
    }
}
