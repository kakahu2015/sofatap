package com.chekusu.keytrans;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Foreground-only HostTap desktop controller using the paired leaf certificate pin. */
final class HostTapClient {
    interface Listener {
        void onState(String message, boolean connecting, boolean connected, boolean inputAvailable,
                     boolean protectedFocus, String computerName);
    }

    static final class Result {
        final boolean accepted;
        final boolean uncertain;
        final String error;
        Result(boolean accepted, boolean uncertain, String error) {
            this.accepted = accepted;
            this.uncertain = uncertain;
            this.error = error;
        }
    }

    private static final int MAX_TEXT_UNITS = 16_000;
    private static final long COMMAND_TIMEOUT_MS = 8_000;
    private static final long MOVE_STALE_MS = 120;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<String, Pending> pending = new HashMap<>();
    private OkHttpClient http;
    private WebSocket socket;
    private HostTapPairing pairing;
    private String computerName = "";
    private boolean connecting, connected, inputAvailable, protectedFocus;
    private boolean reportsTextFocus;
    private int generation;
    private Runnable keepAlive;
    private double queuedDX, queuedDY, queuedScroll;
    private long lastMoveAt;
    private boolean moveBusy, scrollBusy, moveScheduled, scrollScheduled;
    private final Runnable flushMove = this::flushMove;
    private final Runnable flushScroll = this::flushScroll;

    HostTapClient(Listener listener) { this.listener = listener; }

    void connect(HostTapPairing target) {
        disconnect("", false);
        pairing = target;
        generation++;
        int current = generation;
        connecting = true;
        connected = false;
        inputAvailable = false;
        protectedFocus = false;
        reportsTextFocus = false;
        computerName = target.name == null ? target.host : target.name;
        publish("正在验证电脑证书和配对凭据…");
        try {
            PinTrustManager trustManager = new PinTrustManager(target.fingerprint);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] { trustManager }, null);
            http = new OkHttpClient.Builder()
                .sslSocketFactory(ssl.getSocketFactory(), trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
            Request request = new Request.Builder().url(target.endpoint())
                .header("Authorization", "Bearer " + target.token)
                .build();
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    if (current != generation) { webSocket.cancel(); return; }
                    publish("已建立加密连接，正在读取电脑状态…");
                    main.postDelayed(() -> {
                        if (current == generation && connecting) failConnection("电脑没有返回有效状态，请更新电脑端接收器。");
                    }, 10_000);
                }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    if (current != generation) return;
                    main.post(() -> receive(text, current));
                }

                @Override public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                    if (current != generation) return;
                    if (bytes.size() > 65_536) { main.post(() -> failConnection("电脑返回的数据过大，已断开连接。")); return; }
                    onMessage(webSocket, bytes.utf8());
                }

                @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                    if (current != generation) return;
                    main.post(() -> failConnection(connectionError(error, response)));
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    if (current != generation) return;
                    main.post(() -> failConnection("电脑连接已关闭。"));
                }
            });
        } catch (Exception error) {
            failConnection("无法建立安全连接，请检查配对链接和电脑端证书。");
        }
    }

    void disconnect(String message) { disconnect(message, true); }

    private void disconnect(String message, boolean notify) {
        generation++;
        connecting = false;
        connected = false;
        inputAvailable = false;
        protectedFocus = false;
        reportsTextFocus = false;
        if (keepAlive != null) main.removeCallbacks(keepAlive);
        keepAlive = null;
        main.removeCallbacks(flushMove);
        main.removeCallbacks(flushScroll);
        moveScheduled = scrollScheduled = false;
        queuedDX = queuedDY = queuedScroll = 0;
        moveBusy = scrollBusy = false;
        WebSocket old = socket; socket = null;
        OkHttpClient oldHttp = http; http = null;
        for (Pending item : new ArrayList<>(pending.values())) {
            main.removeCallbacks(item.timeout);
            item.completion.onResult(new Result(false, item.type.equals("text"), "连接中断，发送结果未确认"));
        }
        pending.clear();
        if (old != null) old.close(1000, "closed");
        if (oldHttp != null) oldHttp.dispatcher().cancelAll();
        if (notify) publish(message.isEmpty() ? "未连接电脑" : message);
    }

    boolean isConnected() { return connected; }
    boolean isConnecting() { return connecting; }
    boolean canControl() { return connected && inputAvailable; }
    boolean isProtectedFocus() { return protectedFocus; }

    void sendText(String value, Completion completion) {
        if (!canControl()) { completion.onResult(new Result(false, false, "电脑当前无法接收输入")); return; }
        if (value == null || value.isEmpty()) { completion.onResult(new Result(false, false, "先输入要发送的文字")); return; }
        if (value.length() > MAX_TEXT_UNITS) { completion.onResult(new Result(false, false, "文字超过 16,000 个 UTF-16 单元，请分段发送")); return; }
        if (protectedFocus) { completion.onResult(new Result(false, false, "电脑当前是安全输入框，已阻止发送")); return; }
        JSONObject fields = new JSONObject();
        try { fields.put("text", value); }
        catch (Exception ignored) { completion.onResult(new Result(false, false, "文字无法编码")); return; }
        sendAction("text", fields, completion);
    }

    void click(String button) { sendAction("click", object("button", button), result -> { }); }
    void key(String key) { sendAction("key", object("key", key, "modifiers", new org.json.JSONArray()), result -> { }); }

    void setDragging(boolean down, Completion completion) {
        sendAction("button", object("button", "left", "down", down), completion);
    }

    void move(double dx, double dy) {
        if (!canControl() || !Double.isFinite(dx) || !Double.isFinite(dy)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMoveAt > MOVE_STALE_MS) { queuedDX = 0; queuedDY = 0; }
        queuedDX = bound(queuedDX + dx * 1.4, -500, 500);
        queuedDY = bound(queuedDY + dy * 1.4, -500, 500);
        lastMoveAt = now;
        scheduleMove(30);
    }

    private void scheduleMove(long delay) {
        if (moveScheduled) return;
        moveScheduled = true;
        main.postDelayed(flushMove, delay);
    }

    private void flushMove() {
        moveScheduled = false;
        if (!canControl()) { queuedDX = queuedDY = 0; return; }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastMoveAt > MOVE_STALE_MS) { queuedDX = queuedDY = 0; return; }
        if (moveBusy || Math.abs(queuedDX) + Math.abs(queuedDY) < 0.01) return;
        double dx = queuedDX, dy = queuedDY;
        queuedDX = queuedDY = 0;
        moveBusy = true;
        sendAction("move", object("dx", dx, "dy", dy), result -> {
            moveBusy = false;
            if (Math.abs(queuedDX) + Math.abs(queuedDY) > 0.01) scheduleMove(20);
        });
    }

    void scroll(double dy) {
        if (!canControl() || !Double.isFinite(dy)) return;
        queuedScroll = bound(queuedScroll + dy * 8, -1200, 1200);
        scheduleScroll(35);
    }

    private void scheduleScroll(long delay) {
        if (scrollBusy || scrollScheduled) return;
        scrollScheduled = true;
        main.postDelayed(flushScroll, delay);
    }

    private void flushScroll() {
        scrollScheduled = false;
        if (!canControl()) { queuedScroll = 0; return; }
        if (scrollBusy || Math.abs(queuedScroll) < 0.01) return;
        double dy = queuedScroll; queuedScroll = 0; scrollBusy = true;
        sendAction("scroll", object("dx", 0, "dy", dy), result -> {
            scrollBusy = false;
            if (Math.abs(queuedScroll) > 0.01) scheduleScroll(20);
        });
    }

    private void receive(String raw, int current) {
        if (current != generation || raw.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            failConnection("电脑返回的数据无效，已断开连接。"); return;
        }
        try {
            JSONObject message = new JSONObject(raw);
            String type = message.optString("type", "");
            if ("hello".equals(type)) {
                if (!connecting || !(message.opt("version") instanceof Number) || message.optInt("version", -1) != 1 ||
                    !(message.opt("name") instanceof String) || !(message.opt("inputAvailable") instanceof Boolean)) {
                    failConnection("电脑端协议不兼容，请更新接收器。"); return;
                }
                String name = message.optString("name", "");
                if (name.getBytes(StandardCharsets.UTF_8).length > 256) { failConnection("电脑名称无效。"); return; }
                String status = message.optString("inputStatus", "");
                boolean dryRun = message.optBoolean("dryRun", false);
                inputAvailable = message.optBoolean("inputAvailable", false) && !dryRun &&
                    (status.isEmpty() || "ready".equals(status));
                computerName = name.isEmpty() ? (pairing == null ? "电脑" : pairing.host) : name;
                JSONObject focus = message.optJSONObject("textFocus");
                reportsTextFocus = focus != null;
                protectedFocus = focus != null && "protected".equals(focus.optString("state"));
                connecting = false;
                connected = true;
                publish(inputAvailable ? "已连接 · 请先在电脑上点选输入框" :
                    (dryRun ? "已连接 · 电脑处于演示模式" : statusMessage(status)));
                startKeepAlive();
                return;
            }
            if (!"ack".equals(type)) { failConnection("电脑端协议不兼容，请更新接收器。"); return; }
            String id = message.optString("id", "");
            Pending item = pending.remove(id);
            if (item == null) return;
            main.removeCallbacks(item.timeout);
            boolean ok = message.optBoolean("ok", false);
            String error = message.optString("error", "操作未完成");
            if ("ping".equals(item.type)) {
                if (message.has("inputAvailable")) {
                    String status = message.optString("inputStatus", "");
                    inputAvailable = message.optBoolean("inputAvailable", false) && !message.optBoolean("dryRun", false) &&
                        (status.isEmpty() || "ready".equals(status));
                }
                JSONObject focus = message.optJSONObject("textFocus");
                if (focus != null) protectedFocus = "protected".equals(focus.optString("state"));
                publish(inputAvailable ? "已连接 · 请先在电脑上点选输入框" : statusMessage(message.optString("inputStatus", "desktop_unavailable")));
            }
            boolean uncertain = false;
            if (!ok && "text".equals(item.type)) uncertain = !definitelyRejected(error);
            item.completion.onResult(new Result(ok, uncertain, safeError(error)));
        } catch (Exception error) {
            failConnection("电脑返回的数据无效，已断开连接。");
        }
    }

    private void startKeepAlive() {
        keepAlive = new Runnable() {
            @Override public void run() {
                if (!connected) return;
                boolean pingOutstanding = false;
                for (Pending item : pending.values()) if ("ping".equals(item.type)) { pingOutstanding = true; break; }
                if (!pingOutstanding) sendAction("ping", new JSONObject(), result -> {
                    if (!result.accepted) failConnection("电脑没有响应，已停止控制。");
                });
                main.postDelayed(this, reportsTextFocus ? 750 : 5_000);
            }
        };
        main.postDelayed(keepAlive, reportsTextFocus ? 750 : 5_000);
    }

    private void sendAction(String type, JSONObject fields, Completion completion) {
        if (!connected || socket == null) {
            completion.onResult(new Result(false, false, "尚未连接电脑"));
            return;
        }
        if (!"ping".equals(type) && !inputAvailable) {
            completion.onResult(new Result(false, false, "电脑当前无法接收输入"));
            return;
        }
        if (pending.size() >= 32) { completion.onResult(new Result(false, false, "操作过快，请稍后")); return; }
        String id = UUID.randomUUID().toString();
        JSONObject frame = new JSONObject();
        try {
            frame.put("id", id); frame.put("type", type);
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) { String key = keys.next(); frame.put(key, fields.get(key)); }
        } catch (Exception error) { completion.onResult(new Result(false, false, "操作无法编码")); return; }
        String wire = frame.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            completion.onResult(new Result(false, false, "文字编码后超过传输限制，请拆成几段"));
            return;
        }
        Pending item = new Pending(type, completion);
        item.timeout = () -> {
            if (pending.remove(id) != null) failConnection("电脑响应超时。文字可能已输入，请先查看电脑屏幕。");
        };
        pending.put(id, item);
        if (!socket.send(wire)) {
            pending.remove(id);
            completion.onResult(new Result(false, "text".equals(type), "连接中断，发送结果未确认"));
            if ("text".equals(type)) failConnection("发送结果未确认，请先检查电脑屏幕。");
            return;
        }
        main.postDelayed(item.timeout, COMMAND_TIMEOUT_MS);
    }

    private void failConnection(String message) {
        if (!connected && !connecting && socket == null) return;
        disconnect(message, true);
    }

    private void publish(String message) {
        listener.onState(message, connecting, connected, inputAvailable, protectedFocus, computerName);
    }

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); }
        catch (Exception ignored) { }
        return result;
    }

    private static boolean definitelyRejected(String error) {
        String code = error == null ? "" : error.split(":", 2)[0];
        return java.util.Arrays.asList("input_unavailable", "invalid_frame", "input_failed", "unsupported_key",
            "too_large", "invalid_message", "rate_limited", "live_text_unsupported", "live_text_conflict",
            "live_text_changed", "live_text_uncertain", "live_text_invalid").contains(code);
    }

    private static String safeError(String error) {
        if (definitelyRejected(error)) return error.split(":", 2)[0];
        return "电脑未确认操作";
    }

    private static String statusMessage(String status) {
        switch (status) {
            case "dry_run": return "电脑处于演示模式，输入已停用";
            case "accessibility_required": return "电脑需要启用辅助功能权限";
            case "desktop_unavailable": return "电脑桌面当前不可用";
            default: return "电脑当前无法接收输入";
        }
    }

    private static String connectionError(Throwable error, Response response) {
        if (response != null && response.code() == 401) return "配对凭据已失效，请重新配对。";
        if (response != null && response.code() == 409) return "电脑正在结束另一个控制连接，请稍后重试。";
        if (error instanceof javax.net.ssl.SSLPeerUnverifiedException || error instanceof javax.net.ssl.SSLHandshakeException)
            return "证书或主机名校验失败，请重新扫描电脑二维码。";
        return "连接电脑失败。请确认两台设备在同一网络并检查电脑接收器。";
    }

    private static double bound(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    interface Completion { void onResult(Result result); }

    private static final class Pending {
        final String type;
        final Completion completion;
        Runnable timeout;
        Pending(String type, Completion completion) { this.type = type; this.completion = completion; }
    }

    private static final class PinTrustManager extends X509ExtendedTrustManager {
        private final String expected;
        PinTrustManager(String expected) { this.expected = expected.toLowerCase(Locale.ROOT); }
        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null) throw new CertificateException("missing peer certificate");
            try {
                String actual = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                if (!expected.equals(actual)) throw new CertificateException("certificate pin mismatch");
                chain[0].checkValidity();
            } catch (CertificateException error) { throw error; }
            catch (Exception error) { throw new CertificateException("cannot verify peer certificate", error); }
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { verify(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) throws CertificateException { verify(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { verify(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("client certificates are not accepted"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) throws CertificateException { throw new CertificateException("client certificates are not accepted"); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { throw new CertificateException("client certificates are not accepted"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        private static String hex(byte[] bytes) {
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        }
    }
}
