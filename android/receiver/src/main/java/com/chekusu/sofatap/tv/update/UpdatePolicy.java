package com.chekusu.sofatap.tv.update;

import org.json.*;
import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shared, Android-independent release and APK acceptance rules. No executable metadata. */
public final class UpdatePolicy {
    public static final String PACKAGE = "com.chekusu.sofatap.tv";
    public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    private UpdatePolicy() {}
    public record Release(String version, String url, long size, String sha256) {
        public JSONObject json() throws JSONException { return new JSONObject().put("version", version).put("url", url).put("size", size).put("sha256", sha256); }
        public static Release restore(String json) throws Exception {
            JSONObject value = new JSONObject(json);
            Release release = new Release(value.getString("version"), value.getString("url"), value.getLong("size"), value.getString("sha256"));
            validateRelease(release); return release;
        }
    }
    public record Apk(String packageName, String versionName, long versionCode, Set<String> signers) {}

    /** SemVer 2 precedence; never order a beta above the final version. */
    public static int compare(String a, String b) {
        String[] left = parts(a), right = parts(b);
        String[] lc = left[0].split("\\."), rc = right[0].split("\\.");
        for (int i = 0; i < 3; i++) { int c = new BigInteger(lc[i]).compareTo(new BigInteger(rc[i])); if (c != 0) return c; }
        if (left.length == 1 || right.length == 1) return left.length == right.length ? 0 : left.length == 1 ? 1 : -1;
        String[] lp = left[1].split("\\."), rp = right[1].split("\\.");
        for (int i = 0; i < Math.min(lp.length, rp.length); i++) {
            boolean ln = lp[i].matches("[0-9]+"), rn = rp[i].matches("[0-9]+");
            int c = ln && rn ? new BigInteger(lp[i]).compareTo(new BigInteger(rp[i])) : ln != rn ? (ln ? -1 : 1) : lp[i].compareTo(rp[i]);
            if (c != 0) return c;
        }
        return Integer.compare(lp.length, rp.length);
    }
    private static String[] parts(String version) {
        if (version == null || version.length() > 100 || !version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")) throw new IllegalArgumentException("invalid_version");
        String[] result = version.split("-", 2);
        if (result.length == 2) for (String part : result[1].split("\\.")) if (part.matches("[0-9]+") && part.length() > 1 && part.charAt(0) == '0') throw new IllegalArgumentException("invalid_version");
        return result;
    }
    public static Release latest(List<JSONArray> pages, String installed, boolean includeBeta) throws Exception {
        parts(installed); JSONObject newest = null; String version = installed;
        for (JSONArray page : pages) for (int i = 0; i < page.length(); i++) {
            JSONObject release = page.getJSONObject(i);
            String tag = release.optString("tag_name");
            if (!tag.startsWith("android-tv-v") || release.optBoolean("draft") || release.isNull("published_at")) continue;
            String candidate = tag.substring("android-tv-v".length());
            try { parts(candidate); } catch (IllegalArgumentException ignored) { continue; }
            if (!includeBeta && (release.optBoolean("prerelease") || candidate.contains("-"))) continue;
            if (compare(candidate, version) > 0) { version = candidate; newest = release; }
        }
        if (newest == null) return null;
        String expected = "Sofatap-Android-TV-" + version + ".apk";
        JSONArray assets = newest.getJSONArray("assets"); JSONObject apk = null;
        for (int i = 0; i < assets.length(); i++) if (expected.equals(assets.getJSONObject(i).optString("name"))) {
            if (apk != null) throw new IOException("ambiguous_asset"); apk = assets.getJSONObject(i);
        }
        if (apk == null || !"uploaded".equals(apk.optString("state"))) throw new IOException("release_incomplete");
        String digest = apk.optString("digest");
        if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IOException("missing_digest");
        Release result = new Release(version, apk.getString("browser_download_url"), apk.getLong("size"), digest.substring(7).toLowerCase(Locale.ROOT));
        validateRelease(result); return result;
    }
    public static void validateRelease(Release release) throws Exception {
        parts(release.version());
        String expected = "https://github.com/chekusu/sofatap/releases/download/android-tv-v" + release.version() + "/Sofatap-Android-TV-" + release.version() + ".apk";
        if (!expected.equals(release.url()) || release.size() <= 0 || release.size() > MAX_APK_BYTES || !release.sha256().matches("[0-9a-f]{64}")) throw new IOException("untrusted_release");
    }
    public static boolean allowedDownload(String url, String original) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            return original.equals(url) || ("release-assets.githubusercontent.com".equals(uri.getHost()) && uri.getRawPath().startsWith("/github-production-release-asset/"));
        } catch (Exception ignored) { return false; }
    }
    /** Streaming verification, bounded before each write; a cancelled/truncated/wrong file never becomes ready. */
    public static void copyVerified(InputStream input, OutputStream output, Release release, BooleanSupplier current) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long total = 0; byte[] buffer = new byte[32 * 1024];
        while (true) {
            if (!current.getAsBoolean()) throw new InterruptedIOException("cancelled");
            int read = input.read(buffer); if (read < 0) break;
            total += read; if (total > release.size() || total > MAX_APK_BYTES) throw new IOException("size_mismatch");
            output.write(buffer, 0, read); hash.update(buffer, 0, read);
        }
        if (!current.getAsBoolean()) throw new InterruptedIOException("cancelled");
        if (total != release.size() || !MessageDigest.isEqual(hex(hash.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII), release.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IOException("digest_mismatch");
    }
    public static void verifyApk(Release release, Apk installed, Apk archive) throws IOException {
        if (archive == null || !PACKAGE.equals(installed.packageName()) || !PACKAGE.equals(archive.packageName())) throw new IOException("wrong_package");
        if (!release.version().equals(archive.versionName()) || archive.versionCode() <= installed.versionCode() || compare(archive.versionName(), installed.versionName()) <= 0) throw new IOException("not_an_upgrade");
        if (installed.signers() == null || installed.signers().isEmpty() || !installed.signers().equals(archive.signers())) throw new IOException("wrong_signer");
    }
    public static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString(); }
}
