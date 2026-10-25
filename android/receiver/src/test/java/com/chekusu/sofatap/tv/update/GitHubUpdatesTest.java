package com.chekusu.sofatap.tv.update;

import org.junit.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import static org.junit.Assert.*;

public class GitHubUpdatesTest {
    private File directory;
    @Before public void setup() throws Exception { directory = Files.createTempDirectory("sofatap-updates-").toFile(); }
    @After public void cleanup() { for (File f : Objects.requireNonNull(directory.listFiles())) f.delete(); directory.delete(); }
    static class Response extends HttpsURLConnection {
        final int code; final byte[] body; final Map<String,String> headers; boolean disconnected;
        Response(int code, String body, Map<String,String> headers) throws Exception { super(new URL("https://api.github.com")); this.code = code; this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); this.headers = headers; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String key) { return headers.get(key); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
    @Test public void etagCacheWorksWithoutRepeatingMetadataBody() throws Exception {
        Response first = new Response(200, "[{\"tag_name\":\"android-tv-v0.3.0\"}]", Map.of("ETag", "fixture-etag"));
        var client = new GitHubUpdates(directory, () -> true, ignored -> {}, url -> { assertEquals("https://api.github.com/repos/chekusu/sofatap/releases?per_page=100&page=1", url); return first; });
        assertEquals(1, client.releases().get(0).length()); assertTrue(first.disconnected);
        Response cached = new Response(304, "", Map.of());
        assertEquals("android-tv-v0.3.0", new GitHubUpdates(directory, () -> true, ignored -> {}, url -> cached).releases().get(0).getJSONObject(0).getString("tag_name"));
        assertEquals("fixture-etag", cached.getRequestProperty("If-None-Match")); assertFalse(cached.getInstanceFollowRedirects());
    }
    @Test public void fullPageIsFollowedAndPaginationBudgetFailsClosed() throws Exception {
        String page = "[" + String.join(",", Collections.nCopies(100, "{}")) + "]";
        AtomicInteger count = new AtomicInteger();
        var client = new GitHubUpdates(directory, () -> true, ignored -> {}, url -> new Response(200, count.incrementAndGet() == 1 ? page : "[]", Map.of()));
        assertEquals(2, client.releases().size()); assertEquals(2, count.get());
        count.set(0);
        assertThrows(IOException.class, () -> new GitHubUpdates(directory, () -> true, ignored -> {}, url -> { count.incrementAndGet(); return new Response(200, page, Map.of()); }).releases());
        assertEquals(GitHubUpdates.MAX_PAGES, count.get());
    }
    @Test public void rateLimitReturnsBoundedRetryInsteadOfLooping() throws Exception {
        long before = System.currentTimeMillis(); AtomicInteger calls = new AtomicInteger();
        GitHubUpdates.RateLimited error = assertThrows(GitHubUpdates.RateLimited.class, () -> new GitHubUpdates(directory, () -> true, ignored -> {}, url -> { calls.incrementAndGet(); return new Response(429, "", Map.of("Retry-After", "172800")); }).releases());
        assertEquals(1, calls.get()); assertTrue(error.retryAt >= before + 3_600_000); assertTrue(error.retryAt <= System.currentTimeMillis() + 86_400_000);
    }
    @Test public void metadataRedirectIsRejected() throws Exception {
        assertThrows(IOException.class, () -> new GitHubUpdates(directory, () -> true, ignored -> {}, url -> new Response(302, "", Map.of("Location", "https://evil.test"))).releases());
    }
    @Test public void cancelBeforeRequestDoesNotConnect() {
        assertThrows(InterruptedIOException.class, () -> new GitHubUpdates(directory, () -> false, ignored -> {}, url -> { fail("must not connect"); return null; }).releases());
    }
    @Test public void downloaderRejectsRedirectBeforeContactingAnotherHost() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var release = new UpdatePolicy.Release("0.3.0", "https://github.com/chekusu/sofatap/releases/download/android-tv-v0.3.0/Sofatap-Android-TV-0.3.0.apk", 3, "1".repeat(64));
        assertThrows(IOException.class, () -> new GitHubUpdates(directory, () -> true, ignored -> {}, url -> { calls.incrementAndGet(); return new Response(302, "", Map.of("Location", "https://evil.test/file.apk")); }).download(release, new File(directory, "download.part")));
        assertEquals(1, calls.get());
    }
    @Test public void downloaderAcceptsOnlyCorrectSizeAndHashAfterCdnRedirect() throws Exception {
        var release = new UpdatePolicy.Release("0.3.0", "https://github.com/chekusu/sofatap/releases/download/android-tv-v0.3.0/Sofatap-Android-TV-0.3.0.apk", 3, UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest("APK".getBytes())));
        AtomicInteger calls = new AtomicInteger(); File result = new File(directory, "download.part");
        new GitHubUpdates(directory, () -> true, ignored -> {}, url -> calls.incrementAndGet() == 1 ? new Response(302, "", Map.of("Location", "https://release-assets.githubusercontent.com/github-production-release-asset/1/2")) : new Response(200, "APK", Map.of())).download(release, result);
        assertEquals("APK", new String(Files.readAllBytes(result.toPath()))); assertEquals(2, calls.get());
        assertThrows(IOException.class, () -> new GitHubUpdates(directory, () -> true, ignored -> {}, url -> new Response(200, "BAD", Map.of())).download(release, result));
    }
}
