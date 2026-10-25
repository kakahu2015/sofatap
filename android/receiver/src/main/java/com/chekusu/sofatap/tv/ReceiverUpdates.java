package com.chekusu.sofatap.tv;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import com.chekusu.sofatap.tv.update.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A single updater per app process. Background work only checks/downloads; installing always needs an explicit UI action. */
public final class ReceiverUpdates {
    private static ReceiverUpdates instance;
    public static synchronized ReceiverUpdates get(Context context) { if (instance == null) instance = new ReceiverUpdates(context.getApplicationContext()); return instance; }
    private final Context context;
    private final SharedPreferences prefs;
    private final File cache, apk, part;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpsURLConnection activeConnection;
    private volatile boolean busy, automaticWork, serviceRunning;
    private volatile String status;
    private volatile UpdatePolicy.Release candidate;
    private volatile Intent confirmation;
    private volatile long operationDeadline;
    private final Runnable automaticTick = new Runnable() {
        @Override public void run() { if (serviceRunning) { check(false); main.postDelayed(this, 60 * 60 * 1000L); } }
    };
    ReceiverUpdates(Context context) {
        this.context = context; prefs = context.getSharedPreferences("receiver_updates", Context.MODE_PRIVATE);
        cache = new File(context.getCacheDir(), "receiver-updates"); cache.mkdirs();
        apk = new File(cache, "receiver-update.apk"); part = new File(cache, "receiver-update.part");
        try { candidate = UpdatePolicy.Release.restore(prefs.getString("candidate", "")); }
        catch (Exception ignored) { candidate = null; }
        if (candidate != null && UpdatePolicy.compare(candidate.version(), BuildConfig.VERSION_NAME) <= 0) {
            candidate = null; prefs.edit().remove("candidate").remove("install_session").remove("install_nonce").apply(); apk.delete();
        }
        if (candidate == null || !apk.isFile()) { candidate = null; status = "idle"; }
        else status = "ready";
        int session = sessionId();
        if (session >= 0) {
            PackageInstaller.SessionInfo existing = context.getPackageManager().getPackageInstaller().getSessionInfo(session);
            if (existing != null) status = "pending";
            else prefs.edit().remove("install_session").remove("install_nonce").apply();
        }
        // Own cache is bounded to one APK, one temporary transfer and five metadata pages.
        part.delete();
    }
    public boolean supported() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return false;
        try {
            PackageManager manager = context.getPackageManager();
            String installer = Build.VERSION.SDK_INT >= 30 ? manager.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName() : manager.getInstallerPackageName(context.getPackageName());
            return !"com.android.vending".equals(installer);
        } catch (Exception unavailable) { return false; }
    }
    public boolean automatic() { return prefs.getBoolean("automatic", true); }
    public void setAutomatic(boolean enabled) {
        prefs.edit().putBoolean("automatic", enabled).apply();
        if (!enabled && automaticWork) cancelTransfer();
        if (enabled && serviceRunning) check(false);
    }
    void serviceStarted() { if (!serviceRunning) { serviceRunning = true; main.post(automaticTick); } }
    void serviceStopped() {
        serviceRunning = false; main.removeCallbacks(automaticTick);
        if (automaticWork) cancelTransfer();
    }
    public String status() { return supported() ? status : "store"; }
    public String version() { return candidate == null ? BuildConfig.VERSION_NAME : candidate.version(); }
    public boolean busy() { return busy; }
    public boolean hasInstaller() { return sessionId() >= 0; }
    public boolean canInstall() { return supported() && !busy && candidate != null && (sessionId() < 0 || confirmation != null); }
    public synchronized void check(boolean manual) {
        if (!supported() || busy || sessionId() >= 0 || (!manual && (!automatic() || !serviceRunning))) return;
        long now = System.currentTimeMillis(), last = prefs.getLong("last_attempt", 0), retry = prefs.getLong("retry_after", 0);
        if (now < retry) { if (manual) status = "rate_limited"; return; }
        if (last <= now && now - last < (manual ? 60_000L : 24 * 60 * 60 * 1000L)) {
            if (manual && candidate == null) status = "recent";
            return;
        }
        prefs.edit().putLong("last_attempt", now).apply();
        busy = true; automaticWork = !manual; status = "checking";
        operationDeadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000L;
        int token = generation.incrementAndGet();
        worker.execute(() -> {
            try {
                GitHubUpdates api = new GitHubUpdates(cache, () -> current(token), connection -> activeConnection = connection);
                UpdatePolicy.Release newest = UpdatePolicy.latest(api.releases(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME.contains("-"));
                requireCurrent(token);
                if (newest == null) { clearCandidate(); status = "latest"; return; }
                if (candidate != null && candidate.equals(newest) && apk.isFile()) {
                    try { verifyFile(apk, newest, token); status = "ready"; return; }
                    catch (Exception invalidCache) { requireCurrent(token); clearCandidate(); }
                }
                status = "downloading"; api.download(newest, part); requireCurrent(token);
                verifyArchive(part, newest); requireCurrent(token);
                Files.move(part.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                prefs.edit().putString("candidate", newest.json().toString()).apply(); candidate = newest; status = "ready";
            } catch (GitHubUpdates.RateLimited limited) {
                prefs.edit().putLong("retry_after", limited.retryAt).apply(); status = "rate_limited";
            } catch (Exception failure) { status = current(token) ? "failed" : "cancelled"; }
            finally { part.delete(); activeConnection = null; automaticWork = false; busy = false; }
        });
    }
    public void cancelTransfer() {
        generation.incrementAndGet(); HttpsURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
        status = "cancelled";
    }
    /** The only method allowed to open an Android install/settings surface; called directly by a visible button. */
    public synchronized void install(Activity activity) {
        if (!supported() || busy || candidate == null) return;
        Intent approve = confirmation;
        if (approve != null && sessionId() >= 0) {
            try { activity.startActivity(new Intent(approve)); confirmation = null; status = "pending"; }
            catch (ActivityNotFoundException denied) { status = "install_failed"; }
            return;
        }
        if (sessionId() >= 0) return;
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            status = "permission";
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))); }
            catch (ActivityNotFoundException denied) { status = "permission_missing"; }
            return; // Returning from settings never installs automatically.
        }
        busy = true; automaticWork = false; status = "preparing";
        operationDeadline = SystemClock.elapsedRealtime() + 10 * 60 * 1000L;
        UpdatePolicy.Release release = candidate; int token = generation.incrementAndGet();
        worker.execute(() -> {
            int created = -1;
            try {
                verifyFile(apk, release, token);
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(UpdatePolicy.PACKAGE); params.setSize(release.size());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                requireCurrent(token); created = installer.createSession(params);
                String nonce = UUID.randomUUID().toString();
                if (!prefs.edit().putInt("install_session", created).putString("install_nonce", nonce).commit()) throw new IOException("storage_failed");
                try (PackageInstaller.Session session = installer.openSession(created)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, release.size())) {
                        UpdatePolicy.copyVerified(in, out, release, () -> current(token)); session.fsync(out);
                    }
                    requireCurrent(token);
                    Intent result = new Intent(context, UpdateResultReceiver.class).setAction("com.chekusu.sofatap.tv.UPDATE_RESULT")
                        .setData(Uri.parse("sofatap-update:" + nonce)).putExtra("nonce", nonce);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent callback = PendingIntent.getBroadcast(context, created, result, flags);
                    status = "pending"; session.commit(callback.getIntentSender());
                }
            } catch (Exception failure) {
                if (created >= 0) { try { context.getPackageManager().getPackageInstaller().abandonSession(created); } catch (Exception ignored) { } }
                clearSession(); status = current(token) ? "install_failed" : "cancelled";
            } finally { busy = false; }
        });
    }
    public synchronized void cancelInstallation() {
        if (busy) { cancelTransfer(); return; }
        int id = sessionId();
        if (id >= 0) { try { context.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { } }
        clearSession(); status = "cancelled";
    }
    void installerResult(Intent result) {
        String nonce = prefs.getString("install_nonce", ""); int id = sessionId();
        if (id < 0 || nonce.isEmpty() || !nonce.equals(result.getStringExtra("nonce")) || result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return;
        int value = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (value == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation = Build.VERSION.SDK_INT >= 33 ? result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : result.getParcelableExtra(Intent.EXTRA_INTENT);
            status = confirmation == null ? "pending" : "confirm";
            // Never start an Activity here: the user may be watching another TV app now.
        } else {
            clearSession();
            if (value == PackageInstaller.STATUS_SUCCESS) { clearCandidate(); status = "installed"; }
            else status = value == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "install_failed";
        }
    }
    private int sessionId() { return prefs.getInt("install_session", -1); }
    private void clearSession() { confirmation = null; prefs.edit().remove("install_session").remove("install_nonce").commit(); }
    private void clearCandidate() { candidate = null; prefs.edit().remove("candidate").apply(); apk.delete(); }
    private boolean current(int token) { return token == generation.get() && SystemClock.elapsedRealtime() <= operationDeadline; }
    private void requireCurrent(int token) throws InterruptedIOException { if (!current(token)) throw new InterruptedIOException("cancelled"); }
    private void verifyFile(File file, UpdatePolicy.Release release, int token) throws Exception {
        UpdatePolicy.validateRelease(release);
        OutputStream discard = new OutputStream() { @Override public void write(int value) { } @Override public void write(byte[] bytes, int offset, int count) { } };
        try (InputStream in = new FileInputStream(file)) { UpdatePolicy.copyVerified(in, discard, release, () -> current(token)); }
        verifyArchive(file, release); requireCurrent(token);
    }
    void verifyArchive(File file, UpdatePolicy.Release release) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        UpdatePolicy.verifyApk(release, evidence(installed), archive == null ? null : evidence(archive));
    }
    private static UpdatePolicy.Apk evidence(PackageInfo info) throws Exception {
        Signature[] certificates = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> signers = new HashSet<>();
        if (certificates != null) for (Signature cert : certificates) signers.add(UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())));
        return new UpdatePolicy.Apk(info.packageName, info.versionName, Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode, signers);
    }
}
