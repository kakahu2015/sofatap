package com.chekusu.sofatap.tv;

import com.chekusu.sofatap.tv.update.UpdatePolicy;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    private static final String HASH = "1".repeat(64);
    private static UpdatePolicy.Release release(String version) { return new UpdatePolicy.Release(version, "https://github.com/chekusu/sofatap/releases/download/android-tv-v" + version + "/Sofatap-Android-TV-" + version + ".apk", 3, HASH); }
    private static JSONObject entry(String version) throws Exception {
        var release = release(version);
        return new JSONObject().put("tag_name", "android-tv-v" + version).put("draft", false).put("prerelease", version.contains("-"))
            .put("published_at", "2026-10-17T00:00:00Z").put("assets", new JSONArray().put(new JSONObject().put("name", "Sofatap-Android-TV-" + version + ".apk").put("state", "uploaded")
                .put("size", 3).put("digest", "sha256:" + HASH).put("browser_download_url", release.url())));
    }
    private static JSONObject asset(JSONObject entry) throws Exception { return entry.getJSONArray("assets").getJSONObject(0); }
    @Test public void semanticOrderingNeverDowngradesAndOrdersNumericBeta() {
        assertTrue(UpdatePolicy.compare("0.10.0-beta.1", "0.9.0") > 0);
        assertTrue(UpdatePolicy.compare("0.2.0-beta.10", "0.2.0-beta.2") > 0);
        assertTrue(UpdatePolicy.compare("0.2.0", "0.2.0-beta.10") > 0);
        assertTrue(UpdatePolicy.compare("0.2.0-beta.1", "0.2.0") < 0);
        assertEquals(0, UpdatePolicy.compare("1.2.3", "1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.compare("../latest", "1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> UpdatePolicy.compare("1.0.0-beta.01", "1.0.0"));
    }
    @Test public void filtersOtherProductsDraftsAndFutureOlderUploadsAcrossPages() throws Exception {
        JSONObject desktop = entry("8.0.0").put("tag_name", "v8.0.0");
        JSONObject draft = entry("7.0.0").put("draft", true);
        JSONObject unpublished = entry("6.0.0").put("published_at", JSONObject.NULL);
        var actual = UpdatePolicy.latest(List.of(new JSONArray().put(desktop).put(draft).put(unpublished).put(entry("0.1.0")), new JSONArray().put(entry("0.3.0-beta.2"))), "0.2.0-beta.1", true);
        assertEquals("0.3.0-beta.2", actual.version());
        assertNull(UpdatePolicy.latest(List.of(new JSONArray().put(entry("0.1.9"))), "0.2.0-beta.1", true));
    }
    @Test public void stableChannelSkipsBeta() throws Exception {
        assertEquals("0.2.0", UpdatePolicy.latest(List.of(new JSONArray().put(entry("0.3.0-beta.1")).put(entry("0.2.0"))), "0.1.0", false).version());
    }
    @Test public void requiresDigestUploadedStateSizeAndExactOfficialPath() throws Exception {
        for (String field : List.of("digest", "state", "browser_download_url")) {
            JSONObject value = entry("0.3.0"); asset(value).remove(field);
            assertThrows(Exception.class, () -> UpdatePolicy.latest(List.of(new JSONArray().put(value)), "0.2.0", true));
        }
        for (Object size : List.of(0, -1, UpdatePolicy.MAX_APK_BYTES + 1)) {
            JSONObject value = entry("0.3.0"); asset(value).put("size", size);
            assertThrows(Exception.class, () -> UpdatePolicy.latest(List.of(new JSONArray().put(value)), "0.2.0", true));
        }
        JSONObject hostile = entry("0.3.0"); asset(hostile).put("browser_download_url", "https://github.com/attacker/sofatap/releases/download/android-tv-v0.3.0/Sofatap-Android-TV-0.3.0.apk");
        assertThrows(Exception.class, () -> UpdatePolicy.latest(List.of(new JSONArray().put(hostile)), "0.2.0", true));
    }
    @Test public void incompleteLatestDoesNotSilentlyFallBack() throws Exception {
        JSONObject newest = entry("0.4.0"); asset(newest).remove("digest");
        assertThrows(Exception.class, () -> UpdatePolicy.latest(List.of(new JSONArray().put(newest).put(entry("0.3.0"))), "0.2.0", true));
    }
    @Test public void onlyExactHttpsGithubAndAssetCdnRedirectsAllowed() {
        String original = release("0.3.0").url();
        assertTrue(UpdatePolicy.allowedDownload(original, original));
        assertTrue(UpdatePolicy.allowedDownload("https://release-assets.githubusercontent.com/github-production-release-asset/123/456?token=test", original));
        for (String url : List.of("http://release-assets.githubusercontent.com/github-production-release-asset/1", "https://evil.test/file", "https://release-assets.githubusercontent.com.evil.test/github-production-release-asset/1", "https://user@release-assets.githubusercontent.com/github-production-release-asset/1", "https://release-assets.githubusercontent.com:444/github-production-release-asset/1", original + "?else=1", original + "#fragment", "https://github.com/chekusu/other/file")) assertFalse(url, UpdatePolicy.allowedDownload(url, original));
    }
    @Test public void validStreamChecksHashAndLength() throws Exception {
        byte[] bytes = {1, 2, 3}; var release = new UpdatePolicy.Release("0.3.0", release("0.3.0").url(), 3, UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); UpdatePolicy.copyVerified(new ByteArrayInputStream(bytes), out, release, () -> true); assertArrayEquals(bytes, out.toByteArray());
        assertThrows(IOException.class, () -> UpdatePolicy.copyVerified(new ByteArrayInputStream(new byte[]{1, 2}), out, release, () -> true));
        assertThrows(IOException.class, () -> UpdatePolicy.copyVerified(new ByteArrayInputStream(new byte[]{1, 2, 4}), out, release, () -> true));
        assertThrows(IOException.class, () -> UpdatePolicy.copyVerified(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), out, release, () -> true));
    }
    @Test public void cancellationNeverCompletesVerification() {
        assertThrows(InterruptedIOException.class, () -> UpdatePolicy.copyVerified(new ByteArrayInputStream(new byte[]{1, 2, 3}), new ByteArrayOutputStream(), release("0.3.0"), () -> false));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedIOException.class, () -> UpdatePolicy.copyVerified(new ByteArrayInputStream(new byte[]{1, 2, 3}), new ByteArrayOutputStream(), release("0.3.0"), () -> checks.incrementAndGet() < 2));
    }
    @Test public void apkMustMatchPackageCurrentSignerAndBothVersions() throws Exception {
        var installed = new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.2.0-beta.1", 6, Set.of("official"));
        var valid = new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.3.0", 7, Set.of("official"));
        UpdatePolicy.verifyApk(release("0.3.0"), installed, valid);
        List<UpdatePolicy.Apk> invalid = Arrays.asList(null,
            new UpdatePolicy.Apk("another.package", "0.3.0", 7, Set.of("official")),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.3.0", 7, Set.of("attacker")),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.3.0", 7, Set.of()),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.3.0", 6, Set.of("official")),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.3.0", 5, Set.of("official")),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.1.9", 7, Set.of("official")),
            new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.4.0", 8, Set.of("official")));
        for (var archive : invalid) assertThrows(IOException.class, () -> UpdatePolicy.verifyApk(release("0.3.0"), installed, archive));
        assertThrows(IOException.class, () -> UpdatePolicy.verifyApk(release("0.3.0"), new UpdatePolicy.Apk(UpdatePolicy.PACKAGE, "0.2.0", 6, Set.of()), valid));
    }
    @Test public void savedReleaseCannotChangeDownloadPathOrDigest() throws Exception {
        var original = release("0.3.0"); assertEquals(original, UpdatePolicy.Release.restore(original.json().toString()));
        assertThrows(Exception.class, () -> UpdatePolicy.Release.restore(original.json().put("url", "file:///data/private").toString()));
        assertThrows(Exception.class, () -> UpdatePolicy.Release.restore(original.json().put("sha256", "").toString()));
    }
}
