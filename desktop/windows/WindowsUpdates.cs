using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;

namespace SofaTap.Desktop;

internal sealed record UpdateSettings(bool Automatic = true, DateTimeOffset? LastAttempt = null, int Failures = 0, string? PendingTicket = null, string? LastError = null);
internal sealed record UpdateTicket(string Id, string Installation, string InstalledVersion, string InstalledExecutableHash,
    UpdateRelease Release, int ParentPid = 0, long ParentStartTicks = 0, bool Background = false);
internal sealed record ReleasePageCache(string? ETag, string Json, bool HasNext);

internal sealed class WindowsUpdates : IDisposable
{
    internal static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SofaTap", "updates");
    internal static string CurrentVersion => typeof(WindowsUpdates).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0]
        ?? throw new InvalidDataException("无法读取版本号。");
    private readonly HttpClient _http = CreateClient();
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    internal UpdateSettings Settings { get; private set; } = ReadSettings();
    internal bool HasPreparedUpdate => Settings.PendingTicket is not null;
    internal event Action? Changed;

    private static HttpClient CreateClient()
    {
        var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Sofatap-Windows-Updater/1.0");
        return client;
    }
    internal static UpdateSettings ReadSettings()
    {
        var path = Path.Combine(Root, "settings.json");
        if (!File.Exists(path)) return new();
        UpdatePackage.NoLinks(Root);
        if (new FileInfo(path).Length > 16384) throw new InvalidDataException("更新设置无效。");
        return JsonSerializer.Deserialize<UpdateSettings>(File.ReadAllText(path)) ?? throw new InvalidDataException("更新设置无效。");
    }
    internal static void AtomicJson<T>(string path, T value)
    {
        UpdatePackage.NoLinks(Path.GetDirectoryName(path)!);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new IOException("更新设置不能是链接。");
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { File.WriteAllText(temp, JsonSerializer.Serialize(value)); File.Move(temp, path, overwrite: true); }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    internal static void SaveSettings(UpdateSettings settings) => AtomicJson(Path.Combine(Root, "settings.json"), settings);
    internal void SetAutomatic(bool enabled)
    {
        Settings = Settings with { Automatic = enabled };
        SaveSettings(Settings);
        Changed?.Invoke();
    }
    internal static bool ShouldCheck(UpdateSettings settings, DateTimeOffset now)
    {
        if (!settings.Automatic || settings.PendingTicket is not null) return false;
        var delay = settings.Failures == 0 ? TimeSpan.FromHours(24) : TimeSpan.FromHours(Math.Min(24, Math.Pow(2, Math.Min(settings.Failures - 1, 5))));
        return settings.LastAttempt is null || now - settings.LastAttempt >= delay;
    }
    internal async Task<string> CheckAsync(bool manual)
    {
        if (!await _gate.WaitAsync(0)) return Strings.Get("Checking for or downloading updates. Please wait.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
        deadline.CancelAfter(TimeSpan.FromMinutes(10));
        try
        {
            if (HasPreparedUpdate) return Strings.Get("Update ready. It will install the next time you open the app; you can also choose Install Update and Restart.");
            if (!manual && !ShouldCheck(Settings, DateTimeOffset.UtcNow)) return "";
            // A manual action can retry failures, but repeated clicks cannot burn
            // GitHub's anonymous API quota within the same minute.
            if (manual && Settings.LastAttempt is { } attempt && DateTimeOffset.UtcNow - attempt < TimeSpan.FromMinutes(1))
                return Strings.Get("Checked just now. Try again in one minute.");
            Settings = Settings with { LastAttempt = DateTimeOffset.UtcNow, LastError = null };
            SaveSettings(Settings);
            var releases = await ReadReleasesAsync(deadline.Token);
            var current = ReceiverVersion.Parse(CurrentVersion) ?? throw new InvalidDataException("当前版本号无效。");
            var candidate = UpdatePackage.Select(releases, current);
            if (candidate is null)
            {
                Settings = Settings with { Failures = 0 }; SaveSettings(Settings);
                return Strings.Format("HostTap Remote is up to date ({0}).", CurrentVersion);
            }
            // A managed manifest is mandatory before any in-place upgrade. This
            // prevents an updater from replacing an arbitrary extracted folder.
            var installation = Path.TrimEndingDirectorySeparator(AppContext.BaseDirectory);
            UpdatePackage.NoLinks(installation);
            _ = UpdatePackage.ReadManifest(Path.Combine(installation, UpdatePackage.ManifestName), CurrentVersion);
            var id = Guid.NewGuid().ToString("N");
            var directory = Path.Combine(Root, id);
            Directory.CreateDirectory(directory);
            try
            {
                await DownloadAsync(candidate, Path.Combine(directory, "package.zip"), deadline.Token);
                var validation = Path.Combine(directory, "validated");
                _ = UpdatePackage.ExtractVerified(Path.Combine(directory, "package.zip"), candidate, validation);
                Directory.Delete(validation, recursive: true);
                var ticket = new UpdateTicket(id, installation, CurrentVersion, UpdatePackage.HashFile(Path.Combine(installation, "SofaTap.exe")), candidate);
                AtomicJson(Path.Combine(directory, "ticket.json"), ticket);
                Settings = Settings with { PendingTicket = id, Failures = 0 }; SaveSettings(Settings);
                Changed?.Invoke();
                return Strings.Format("HostTap Remote {0} was downloaded and verified. It will install the next time you open the app. The current connection keeps working.", candidate.Version);
            }
            catch { if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true); throw; }
        }
        catch (Exception error) when (IsRecoverable(error))
        {
            Settings = Settings with { Failures = Math.Min(Settings.Failures + 1, 10), LastError = Strings.Get("Update check failed. Check the network or installation folder permissions.") };
            try { SaveSettings(Settings); } catch (Exception saveError) when (IsRecoverable(saveError)) { }
            if (manual) throw new IOException(Strings.Get("Unable to complete the update check. Check the network and installation folder write permissions and try again. Update packages must provide GitHub SHA-256 verification; the current version is unchanged."), error);
            return "";
        }
        finally { _gate.Release(); }
    }
    private async Task<List<JsonElement>> ReadReleasesAsync(CancellationToken cancellation)
    {
        var releases = new List<JsonElement>();
        for (var page = 1; page <= 20; page++)
        {
            var path = Path.Combine(Root, "releases-" + page + ".json");
            ReleasePageCache? cache = null;
            if (File.Exists(path) && new FileInfo(path).Length <= 8 * 1024 * 1024)
            {
                try { cache = JsonSerializer.Deserialize<ReleasePageCache>(File.ReadAllText(path)); }
                catch (JsonException) { }
            }
            using var request = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com/repos/chekusu/sofatap/releases?per_page=100&page=" + page);
            request.Headers.Accept.ParseAdd("application/vnd.github+json");
            request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
            if (cache?.ETag is { } etag && EntityTagHeaderValue.TryParse(etag, out var parsed)) request.Headers.IfNoneMatch.Add(parsed);
            using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
            if (response.StatusCode != HttpStatusCode.NotModified)
            {
                response.EnsureSuccessStatusCode();
                using var data = new MemoryStream();
                await CopyBoundedAsync(await response.Content.ReadAsStreamAsync(cancellation), data, 4 * 1024 * 1024, null, cancellation);
                var json = System.Text.Encoding.UTF8.GetString(data.ToArray());
                using var validated = JsonDocument.Parse(json);
                if (validated.RootElement.ValueKind != JsonValueKind.Array) throw new InvalidDataException("更新服务器返回无效清单。");
                cache = new(response.Headers.ETag?.ToString(), json,
                    response.Headers.TryGetValues("Link", out var links) && links.Any(link => link.Contains("rel=\"next\"", StringComparison.Ordinal)));
                AtomicJson(path, cache);
            }
            if (cache is null) throw new InvalidDataException("更新缓存无效。");
            using var document = JsonDocument.Parse(cache.Json);
            releases.AddRange(document.RootElement.EnumerateArray().Select(item => item.Clone()));
            if (!cache.HasNext) return releases;
        }
        throw new InvalidDataException("发布记录过多，无法安全确定最新版本。");
    }
    private async Task DownloadAsync(UpdateRelease release, string destination, CancellationToken cancellation)
    {
        var url = new Uri(release.Url);
        for (var redirects = 0; redirects <= 5; redirects++)
        {
            using var response = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellation);
            if ((int)response.StatusCode is >= 300 and <= 399)
            {
                var next = response.Headers.Location is { } location ? new Uri(url, location) : throw new InvalidDataException("更新下载重定向缺失。");
                if (next.Scheme != "https" || !next.IsDefaultPort || next.UserInfo.Length > 0 || next.Fragment.Length > 0 ||
                    next.Host is not ("github.com" or "release-assets.githubusercontent.com" or "objects.githubusercontent.com"))
                    throw new InvalidDataException("更新下载重定向来源不受信任。");
                url = next;
                continue;
            }
            if (response.StatusCode != HttpStatusCode.OK || response.Content.Headers.ContentLength is long size && size != release.Size)
                throw new InvalidDataException("更新下载大小或状态无效。");
            await using (var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                await CopyBoundedAsync(await response.Content.ReadAsStreamAsync(cancellation), output, release.Size, release.Size, cancellation);
            UpdatePackage.VerifyDownload(destination, release);
            return;
        }
        throw new InvalidDataException("更新下载重定向过多。");
    }
    internal static async Task CopyBoundedAsync(Stream source, Stream target, long maximum, long? exact, CancellationToken cancellation)
    {
        await using (source)
        {
            var buffer = new byte[81920]; long total = 0; int count;
            while ((count = await source.ReadAsync(buffer, cancellation)) > 0)
            {
                total += count;
                if (total > maximum) throw new InvalidDataException("更新下载超过允许大小。");
                await target.WriteAsync(buffer.AsMemory(0, count), cancellation);
            }
            if (exact.HasValue && total != exact.Value) throw new InvalidDataException("更新下载不完整。");
        }
    }
    internal static bool IsRecoverable(Exception error) => error is IOException or InvalidDataException or UnauthorizedAccessException or JsonException or HttpRequestException
        or InvalidOperationException or ArgumentException or OperationCanceledException or System.ComponentModel.Win32Exception or KeyNotFoundException or FormatException or OverflowException;
    public void Dispose() { _lifetime.Cancel(); _http.Dispose(); _lifetime.Dispose(); }
}
