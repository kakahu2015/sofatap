using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using SofaTap.Desktop;

internal static class UpdateChecks
{
    internal static async Task<int> RunAsync()
    {
        var count = 0;
        void Check(bool condition, string description) { if (!condition) throw new Exception(description); count++; }
        void Reject(Action action, string description)
        {
            try { action(); } catch (Exception error) when (error is IOException or InvalidDataException or ArgumentException or InvalidOperationException or KeyNotFoundException) { count++; return; }
            throw new Exception(description);
        }
        ReceiverVersion V(string text) => ReceiverVersion.Parse(text) ?? throw new Exception(text);
        Check(V("0.10.0-beta.1").CompareTo(V("0.9.9")) > 0, "Numeric minor order");
        Check(V("1.0.0-beta.10").CompareTo(V("1.0.0-beta.2")) > 0, "Numeric prerelease order");
        Check(V("1.0.0").CompareTo(V("1.0.0-rc.999")) > 0, "Stable follows prerelease");
        Check(V("1.0.0-beta.1").CompareTo(V("1.0.0")) < 0, "Stable cannot downgrade to beta");
        Check(V("1.0.0-beta.1+abc").CompareTo(V("1.0.0-beta.1+def")) == 0, "Build metadata does not cause upgrade");
        Check(V("1.0.0--1").CompareTo(V("1.0.0-2")) > 0, "Hyphen identifier is alphanumeric, not negative number");
        foreach (var bad in new[] { "1", "01.0.0", "1.0.0-beta.01", "v1.0.0", "not-a-version", "1.0.0-", "1.0.0\n", "1.0.0-beta..1" })
            Check(ReceiverVersion.Parse(bad) is null, "Invalid semantic version rejected");
        JsonElement Release(string tag, string? digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", string? url = null, long size = 50, string state = "uploaded", bool draft = false, bool prerelease = false) =>
            JsonSerializer.SerializeToElement(new { tag_name = tag, draft, prerelease, assets = new[] { new { name = UpdatePackage.AssetName, digest, size, state, browser_download_url = url ?? "https://github.com/chekusu/sofatap/releases/download/" + tag + "/" + UpdatePackage.AssetName } } });
        var chosen = UpdatePackage.Select([Release("v0.9.0-beta.2"), Release("v0.10.0-beta.1"), Release("v2.0.0", draft: true)], V("0.8.0-beta.1"));
        Check(chosen?.Version == "0.10.0-beta.1", "Desktop channel chooses highest eligible release, not newest API position");
        Check(UpdatePackage.Select([Release("v0.8.0-beta.1"), Release("v0.7.9")], V("0.8.0-beta.1")) is null, "Equal and old releases skipped");
        foreach (var bad in new[] { Release("v1.0.0", digest: null), Release("v1.0.0", digest: "sha256:bad"), Release("v1.0.0", size: 0),
            Release("v1.0.0", size: UpdatePackage.MaximumDownload + 1), Release("v1.0.0", state: "new"),
            Release("v1.0.0", url: "https://evil.test/x.zip"), Release("v1.0.0", url: "http://github.com/chekusu/sofatap/releases/download/v1.0.0/" + UpdatePackage.AssetName),
            Release("v1.0.0", url: "https://github.com/other/repo/releases/download/v1.0.0/" + UpdatePackage.AssetName),
            Release("v1.0.0", url: "https://github.com/chekusu/sofatap/releases/download/v1.0.0/" + UpdatePackage.AssetName + "?x=1") })
            Reject(() => UpdatePackage.Select([bad], V("0.8.0")), "Missing integrity or wrong origin fails closed");
        Check(UpdatePackage.Select([Release("v2.0.0-beta.1"), Release("v1.1.0", prerelease: true)], V("1.0.0")) is null, "Stable channel excludes prerelease tags and prerelease metadata");
        Reject(() => UpdatePackage.Select([JsonSerializer.SerializeToElement(new { draft = false, tag_name = "v2.0.0", assets = new[] { new { name = UpdatePackage.AssetName, size = "bad", state = "uploaded" } } })], V("1.0.0")), "Malformed asset metadata fails closed");
        var now = DateTimeOffset.UtcNow;
        Check(WindowsUpdates.ShouldCheck(new(), now), "First normal launch checks by default");
        Check(!WindowsUpdates.ShouldCheck(new(Automatic: false), now), "Opt-out never auto checks");
        Check(!WindowsUpdates.ShouldCheck(new(LastAttempt: now.AddHours(-23)), now), "Normal successful checks limited to daily");
        Check(WindowsUpdates.ShouldCheck(new(LastAttempt: now.AddHours(-25)), now), "Next daily check eligible");
        Check(!WindowsUpdates.ShouldCheck(new(LastAttempt: now, Failures: 4), now.AddHours(7)), "Errors back off");
        Check(WindowsUpdates.ShouldCheck(new(LastAttempt: now, Failures: 4), now.AddHours(8)), "Backoff eventually retries");
        Check(!WindowsUpdates.ShouldCheck(new(PendingTicket: new string('a', 32)), now), "Staged update does not redownload");
        Check(!WindowsUpdates.ShouldCheck(new(LastAttempt: now.AddDays(1)), now), "Clock reversal does not poll-loop");
        var temporaryRoot = Path.GetTempPath();
        // macOS exposes /var as a system symlink; exercise production's strict
        // no-junction policy from the physical test directory instead.
        if (OperatingSystem.IsMacOS() && temporaryRoot.StartsWith("/var/", StringComparison.Ordinal)) temporaryRoot = "/private" + temporaryRoot;
        var root = Path.Combine(temporaryRoot, "sofatap-update-check-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));
            var content = new Dictionary<string, byte[]> { ["SofaTap.exe"] = Encoding.UTF8.GetBytes("inert frontend"), ["SofaTapHost.exe"] = Encoding.UTF8.GetBytes("inert backend"), ["licenses/MIT.txt"] = Encoding.UTF8.GetBytes("fixture license") };
            (string Path, UpdateRelease Release) Package(string? additional = null, int? attributes = null, bool badManifest = false, bool badHash = false, string? product = null)
            {
                var archivePath = Path.Combine(root, Guid.NewGuid().ToString("N") + ".zip");
                var manifest = new PackageManifest(product ?? UpdatePackage.Product, "0.9.0-beta.1", "x64", content.ToDictionary(item => item.Key, item => badHash ? new string('b', 64) : Hash(item.Value)));
                using (var stream = File.Create(archivePath))
                using (var zip = new ZipArchive(stream, ZipArchiveMode.Create))
                {
                    foreach (var item in content)
                    {
                        var entry = zip.CreateEntry(UpdatePackage.ArchiveRoot + item.Key);
                        using var output = entry.Open(); output.Write(item.Value);
                    }
                    var manifestEntry = zip.CreateEntry(UpdatePackage.ArchiveRoot + UpdatePackage.ManifestName);
                    using (var output = manifestEntry.Open()) output.Write(JsonSerializer.SerializeToUtf8Bytes(badManifest ? manifest with { Version = "0.8.0" } : manifest));
                    if (additional is not null)
                    {
                        var entry = zip.CreateEntry(additional);
                        if (attributes.HasValue) entry.ExternalAttributes = attributes.Value;
                        if (!additional.EndsWith('/')) using (var output = entry.Open()) output.WriteByte(1);
                    }
                }
                return (archivePath, new("v0.9.0-beta.1", "0.9.0-beta.1", "https://github.com/chekusu/sofatap/releases/download/v0.9.0-beta.1/" + UpdatePackage.AssetName,
                    UpdatePackage.HashFile(archivePath), new FileInfo(archivePath).Length));
            }
            var valid = Package(); var validDestination = Path.Combine(root, "verified");
            var manifest = UpdatePackage.ExtractVerified(valid.Path, valid.Release, validDestination);
            Check(File.ReadAllText(Path.Combine(validDestination, "SofaTap.exe")) == "inert frontend", "Verified archive extracts exact inert bytes");
            Check(manifest.Version == valid.Release.Version, "Archive version tied to selected release");
            Reject(() => UpdatePackage.VerifyDownload(valid.Path, valid.Release with { Sha256 = new string('b', 64) }), "Wrong SHA-256 rejects package");
            Reject(() => UpdatePackage.VerifyDownload(valid.Path, valid.Release with { Size = valid.Release.Size - 1 }), "Wrong downloaded size rejects package");
            Reject(() => UpdatePackage.ExtractVerified(valid.Path, valid.Release, validDestination), "Existing stage cannot be overwritten");
            foreach (var path in new[] { "../escape", "/absolute", "SofaTap-Windows-x64/../escape", "SofaTap-Windows-x64/a/../../escape", "SofaTap-Windows-x64/a\\..\\escape",
                "SofaTap-Windows-x64/C:/evil", "SofaTap-Windows-x64/CON", "SofaTap-Windows-x64/aux.txt", "SofaTap-Windows-x64/file.", "SofaTap-Windows-x64/file ",
                "SofaTap-Windows-x64/file:ads", "SofaTap-Windows-x64/SOFATAP.EXE", "SofaTap-Windows-x64/undeclared.txt", "OtherRoot/file" })
            {
                var bad = Package(path); var destination = Path.Combine(root, Guid.NewGuid().ToString("N"));
                Reject(() => UpdatePackage.ExtractVerified(bad.Path, bad.Release, destination), "Traversal, alternate streams, ambiguous names or undeclared files rejected");
                Check(!Directory.Exists(destination), "Unsafe package creates no partial stage");
            }
            foreach (var bad in new[] { Package(UpdatePackage.ArchiveRoot + "link", unchecked((int)0xA1FF0000)), Package(UpdatePackage.ArchiveRoot + "link", (int)FileAttributes.ReparsePoint),
                Package(badManifest: true), Package(badHash: true), Package(product: "other.app") })
                Reject(() => UpdatePackage.ExtractVerified(bad.Path, bad.Release, Path.Combine(root, Guid.NewGuid().ToString("N"))), "Symlinks, wrong version/product and per-file hashes rejected");
            foreach (var failMove in new[] { 0, 1, 2 })
            {
                var folder = Path.Combine(root, "transaction-" + failMove); Directory.CreateDirectory(folder);
                var installed = Path.Combine(folder, "installed"); var staged = Path.Combine(folder, "stage"); var backup = Path.Combine(folder, "backup"); var failed = Path.Combine(folder, "failed");
                Directory.CreateDirectory(installed); Directory.CreateDirectory(staged);
                File.WriteAllText(Path.Combine(installed, "data"), "old"); File.WriteAllText(Path.Combine(staged, "data"), "new");
                var moves = 0;
                var transaction = new UpdateTransaction(installed, staged, backup, failed, (from, to) => { moves++; if (moves == failMove) throw new IOException("fixture locked directory"); Directory.Move(from, to); });
                try { transaction.Install(); }
                catch (IOException) { }
                transaction.Rollback();
                Check(File.ReadAllText(Path.Combine(installed, "data")) == "old", "Rollback retains original after success or either rename failure");
                transaction.Rollback(); Check(Directory.Exists(installed), "Rollback is idempotent");
            }
            var oldDirectory = Path.Combine(root, "old-custom"); Directory.CreateDirectory(oldDirectory);
            foreach (var item in content) { var path = Path.Combine(oldDirectory, item.Key); Directory.CreateDirectory(Path.GetDirectoryName(path)!); File.WriteAllBytes(path, item.Value); }
            File.WriteAllText(Path.Combine(oldDirectory, "my-notes.txt"), "keep me");
            UpdatePackage.PreserveUnmanagedFiles(oldDirectory, manifest, validDestination);
            Check(File.ReadAllText(Path.Combine(validDestination, "my-notes.txt")) == "keep me", "Custom install files preserved");
            Reject(() => UpdatePackage.PreserveUnmanagedFiles(oldDirectory, manifest, validDestination), "Conflicting custom files fail safely without overwrite");
            var output = new MemoryStream();
            await WindowsUpdates.CopyBoundedAsync(new MemoryStream([1, 2, 3]), output, 3, 3, default);
            Check(output.ToArray().SequenceEqual(new byte[] { 1, 2, 3 }), "Bounded stream reads exact expected bytes");
            foreach (var expected in new[] { 2, 4 })
            {
                try { await WindowsUpdates.CopyBoundedAsync(new MemoryStream([1, 2, 3]), new MemoryStream(), expected, expected, default); throw new Exception("Invalid size accepted"); }
                catch (InvalidDataException) { count++; }
            }
            using (var stalled = new StalledStream())
            using (var limit = new CancellationTokenSource(TimeSpan.FromMilliseconds(50)))
            {
                try { await WindowsUpdates.CopyBoundedAsync(stalled, new MemoryStream(), 3, 3, limit.Token); throw new Exception("Stalled body ignored deadline"); }
                catch (OperationCanceledException) { count++; }
            }
            if (!OperatingSystem.IsWindows())
            {
                var link = Path.Combine(root, "linked"); Directory.CreateSymbolicLink(link, validDestination);
                Reject(() => UpdatePackage.NoLinks(link), "Installation link rejected");
            }
        }
        finally { Directory.Delete(root, recursive: true); }
        return count;
    }
    private sealed class StalledStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        { await Task.Delay(Timeout.Infinite, cancellationToken); return 0; }
    }

}
