package com.chekusu.sofatap.tv.update;

import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;

/** Fixed public repository only. No credentials, analytics, pairing data or device identifiers. */
public final class GitHubUpdates {
    public static final int MAX_PAGES = 5;
    private static final int MAX_METADATA = 4 * 1024 * 1024;
    private final File cache;
    private final BooleanSupplier current;
    private final Consumer<HttpsURLConnection> connection;
    interface ConnectionFactory { HttpsURLConnection open(String url) throws Exception; }
    private final ConnectionFactory factory;
    public GitHubUpdates(File cache, BooleanSupplier current, Consumer<HttpsURLConnection> connection) {
        this(cache, current, connection, url -> (HttpsURLConnection)new URL(url).openConnection());
    }
    GitHubUpdates(File cache, BooleanSupplier current, Consumer<HttpsURLConnection> connection, ConnectionFactory factory) {
        this.cache = cache; this.current = current; this.connection = connection; this.factory = factory;
    }
    public static final class RateLimited extends IOException {
        public final long retryAt;
        RateLimited(long retryAt) { super("rate_limited"); this.retryAt = retryAt; }
    }
    public List<JSONArray> releases() throws Exception {
        List<JSONArray> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            requireCurrent();
            File cached = new File(cache, "release-page-" + page + ".json"); JSONObject stored = null;
            try { if (cached.isFile() && cached.length() <= MAX_METADATA + 1024) stored = new JSONObject(new String(Files.readAllBytes(cached.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { /* refresh a corrupt cache */ }
            HttpsURLConnection request = open("https://api.github.com/repos/chekusu/sofatap/releases?per_page=100&page=" + page);
            request.setRequestProperty("Accept", "application/vnd.github+json");
            request.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            if (stored != null && !stored.optString("etag").isEmpty()) request.setRequestProperty("If-None-Match", stored.getString("etag"));
            try {
                int code = request.getResponseCode(); requireCurrent();
                if (code == 403 || code == 429) throw rateLimit(request);
                JSONArray data;
                if (code == 304 && stored != null) data = stored.getJSONArray("data");
                else {
                    if (code != 200) throw new IOException("check_failed");
                    try (InputStream in = request.getInputStream()) { data = new JSONArray(new String(readLimited(in), StandardCharsets.UTF_8)); }
                    JSONObject payload = new JSONObject().put("etag", Objects.toString(request.getHeaderField("ETag"), "")).put("data", data);
                    File part = new File(cache, "release-page-" + page + ".part");
                    Files.write(part.toPath(), payload.toString().getBytes(StandardCharsets.UTF_8));
                    Files.move(part.toPath(), cached.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                result.add(data); if (data.length() < 100) return result;
            } finally { request.disconnect(); connection.accept(null); }
        }
        // Do not silently pick an old build if a future oversized repository exceeds the fetch budget.
        throw new IOException("release_list_incomplete");
    }
    public void download(UpdatePolicy.Release release, File target) throws Exception {
        UpdatePolicy.validateRelease(release); String url = release.url();
        for (int redirects = 0; redirects <= 3; redirects++) {
            requireCurrent(); if (!UpdatePolicy.allowedDownload(url, release.url())) throw new IOException("untrusted_redirect");
            HttpsURLConnection request = open(url);
            try {
                int code = request.getResponseCode(); requireCurrent();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = request.getHeaderField("Location"); if (location == null) throw new IOException("bad_redirect");
                    url = new URL(new URL(url), location).toString(); continue;
                }
                if (code != 200) throw new IOException("download_failed");
                long size = request.getContentLengthLong(); if (size != -1 && size != release.size()) throw new IOException("size_mismatch");
                try (InputStream in = request.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
                    UpdatePolicy.copyVerified(in, out, release, current); out.getFD().sync();
                }
                return;
            } finally { request.disconnect(); connection.accept(null); }
        }
        throw new IOException("too_many_redirects");
    }
    private HttpsURLConnection open(String url) throws Exception {
        HttpsURLConnection request = factory.open(url);
        request.setInstanceFollowRedirects(false); request.setConnectTimeout(10_000); request.setReadTimeout(15_000);
        request.setRequestProperty("User-Agent", "Sofatap-Android-TV-Updater");
        request.setRequestProperty("Accept-Encoding", "identity"); connection.accept(request); return request;
    }
    private byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int n;
        while ((n = in.read(buffer)) >= 0) { requireCurrent(); if (out.size() + n > MAX_METADATA) throw new IOException("metadata_too_large"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
    private void requireCurrent() throws InterruptedIOException { if (!current.getAsBoolean()) throw new InterruptedIOException("cancelled"); }
    private static RateLimited rateLimit(HttpsURLConnection request) {
        long now = System.currentTimeMillis(), wait = 60 * 60 * 1000L;
        try { wait = Math.max(wait, Long.parseLong(request.getHeaderField("Retry-After")) * 1000); } catch (Exception ignored) { }
        try { wait = Math.max(wait, Long.parseLong(request.getHeaderField("X-RateLimit-Reset")) * 1000 - now); } catch (Exception ignored) { }
        return new RateLimited(now + Math.min(wait, 24 * 60 * 60 * 1000L));
    }
}
