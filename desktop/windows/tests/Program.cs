using System.Diagnostics;
using System.Text.Json;
using SofaTap.Desktop;

const string png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
string Snapshot(string image = png, string type = "state", int count = 1, object? uri = null, bool includeUri = true,
    string host = "192.0.2.1") => JsonSerializer.Serialize(new
{
    type, name = "Test computer", platform = "windows", inputStatus = "dry_run", dryRun = true, connected = false,
    pairings = Enumerable.Range(0, count).Select(_ =>
    {
        var pairing = new Dictionary<string, object?> { ["host"] = host, ["qrPng"] = image };
        if (includeUri) pairing["uri"] = uri;
        return pairing;
    })
});
var count = 0;
void Check(bool condition, string name) { if (!condition) throw new Exception(name); count++; }
void Reject(Action action, string name)
{
    try { action(); }
    catch (Exception error) when (error is JsonException or InvalidDataException or InvalidOperationException or KeyNotFoundException or FormatException)
    { count++; return; }
    throw new Exception(name);
}
var state = NativeProtocol.Parse(Snapshot(uri: "sofatap://test-only"));
Check(state.DryRun && !state.Connected && state.Pairings.Length == 1 && state.Pairings[0].Host == "192.0.2.1", "valid private pipe snapshot");
Check(state.Pairings[0].Png.SequenceEqual(Convert.FromBase64String(png)), "PNG bytes preserved in memory");
Check(state.Pairings[0].Uri is null, "malformed optional link does not disable the QR snapshot");
var token = new string('D', 43); // Synthetic fixture only; no listener uses this credential.
var fingerprint = new string('a', 64);
string Link(string host = "192.0.2.1") => $"sofatap://pair?host={Uri.EscapeDataString(host)}&port=53319&token={token}&fingerprint={fingerprint}";
var link = Link();
var linked = NativeProtocol.Parse(Snapshot(uri: link));
Check(linked.Pairings[0].Uri == link, "complete matching link retained in memory for explicit Copy");
Check(!JsonSerializer.Serialize(linked).Contains(token) && !JsonSerializer.Serialize(linked).Contains("sofatap://"), "serializing UI state does not expose the pairing link");
Check(!linked.Pairings[0].ToString().Contains(token) && !linked.ToString().Contains(token), "record diagnostic strings redact the link");
Check(NativeProtocol.Parse(Snapshot(includeUri: false)).Pairings[0].Uri is null, "old QR-only snapshots remain compatible");
Check(NativeProtocol.Parse(Snapshot(uri: null)).Pairings[0].Uri is null, "null optional link disables Copy only");
Check(NativeProtocol.Parse(Snapshot(uri: 42)).Pairings[0].Uri is null, "non-string optional link disables Copy only");
Check(NativeProtocol.ValidPairingUri(link.Replace("sofatap:", "SOFATAP:"), "192.0.2.1") is not null, "scheme is case insensitive");
Check(NativeProtocol.ValidPairingUri(Link("Living-Room.local"), "living-room.local") is not null, "hostname comparison is case insensitive");
Check(NativeProtocol.ValidPairingUri(Link("2001:db8::17"), "2001:db8::17") is not null, "encoded IPv6 host supported");
Check(NativeProtocol.ValidPairingUri(link.Replace("host=192.0.2.1", "host=%31%39%32.0.2.1"), "192.0.2.1") is not null, "percent escapes decoded once for field validation");
Check(NativeProtocol.ValidPairingUri($"sofatap://pair?fingerprint={fingerprint}&token={token}&port=53319&host=192.0.2.1", "192.0.2.1") is not null, "query order does not matter");
foreach (var metadata in new[] { "&platform=windows", "&platform=macos", "&platform=linux", "&name=Living+Room",
    "&platform=windows&name=" + Uri.EscapeDataString("\u00a0 客厅 + TV 🛋️ \u3000"),
    "&name=" + new string('a', 80), "&name=" + Uri.EscapeDataString(string.Concat(Enumerable.Repeat("😀", 64))) })
    Check(NativeProtocol.ValidPairingUri(link + metadata, "192.0.2.1") == link + metadata, "optional bounded metadata retains copy link");
foreach (var malformed in new[]
{
    link.Replace("sofatap:", "https:"), link.Replace("//pair?", "//user@pair?"), link.Replace("//pair?", "//pair:80?"),
    link.Replace("//pair?", "//pair/?"), link + "#fragment", link + "&extra=1", link.Replace("&port=53319", "&host=192.0.2.1"),
    link.Replace("&port=53319", ""), link.Replace("host=192.0.2.1", "host=192.0.2.2"), link.Replace("host=192.0.2.1", "host=bad%2Fhost"),
    link.Replace("host=192.0.2.1", "host=%ZZ"), link.Replace("host=192.0.2.1", "host=%2531%2539%2532.0.2.1"),
    link.Replace("host=192.0.2.1", "host=%0A192.0.2.1"), link.Replace("port=53319", "port=0"), link.Replace("port=53319", "port=65536"),
    link.Replace("port=53319", "port=-1"), link.Replace("port=53319", "port=%2B53319"), link.Replace("port=53319", "port=999999999999999999999"),
    link.Replace(token, new string('D', 31)), link.Replace(token, new string('D', 257)), link.Replace(token, token + "+"),
    link.Replace(fingerprint, new string('a', 63)), link.Replace(fingerprint, new string('g', 64)), link + "\n", " " + link,
    new string('x', NativeProtocol.MaximumPairingUriLength + 1),
    link + "&platform=androidtv", link + "&platform=unknown", link + "&platform=", link + "&platform=windows&platform=macos",
    link + "&name=TV&name=TV", link + "&name=", link + "&name=%20%C2%A0", link + "&name=" + new string('a', 81),
    link + "&name=" + Uri.EscapeDataString(string.Concat(Enumerable.Repeat("😀", 65))),
    link + "&name=" + Uri.EscapeDataString(string.Concat(Enumerable.Repeat("e\u0301", 41))),
    link + "&name=TV%00", link + "&name=TV%0A", link + "&name=TV%C2%85", link + "&name=TV%E2%80%8B",
    link + "&name=TV%E2%80%AE", link + "&name=%FF", link + "&name=%ED%A0%80", link + "&name=%ZZ", link + "&na%6De=TV"
})
{
    var invalidLink = NativeProtocol.Parse(Snapshot(uri: malformed));
    Check(invalidLink.Pairings[0].Uri is null && invalidLink.Pairings[0].Png.Length > 0, "invalid optional link disables Copy without rejecting QR");
}
Reject(() => NativeProtocol.Parse("not json"), "invalid JSON rejected");
Reject(() => NativeProtocol.Parse(Snapshot(type: "other")), "unexpected message rejected");
Reject(() => NativeProtocol.Parse(Snapshot(count: 33)), "pairing count capped");
Reject(() => NativeProtocol.Parse(Snapshot(image: "not-png")), "invalid image rejected");
Reject(() => NativeProtocol.Parse(new string('x', NativeProtocol.MaximumLineLength + 1)), "line size capped");
var huge = Convert.FromBase64String(png);
System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(huge.AsSpan(16, 4), 65535);
Reject(() => NativeProtocol.Parse(Snapshot(image: Convert.ToBase64String(huge))), "pixel allocation bounded before decoder");
Reject(() => NativeProtocol.Parse(Snapshot().Replace("\"dryRun\":true", "\"dryRun\":\"true\"")), "boolean type checked");

if (!OperatingSystem.IsWindows())
{
    // A disposable local pipe fixture, not a receiver or native-input test.
    var directory = Path.Combine(Path.GetTempPath(), "sofatap-ui-pipe-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(directory);
    try
    {
        var fixture = Path.Combine(directory, "backend");
        await File.WriteAllTextAsync(fixture, "#!/usr/bin/env python3\nimport json,sys\nstate=json.loads(" + JsonSerializer.Serialize(Snapshot()) + ")\nprint(json.dumps(state),flush=True)\nfor line in sys.stdin:\n command=json.loads(line)['type']\n if command=='quit': break\n if command=='refresh':\n  state['connected']=True\n  print(json.dumps(state),flush=True)\n");
        File.SetUnixFileMode(fixture, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        using var backend = new BackendProcess();
        var initial = new TaskCompletionSource<ReceiverState>(TaskCreationOptions.RunContinuationsAsynchronously);
        var refreshed = new TaskCompletionSource<ReceiverState>(TaskCreationOptions.RunContinuationsAsynchronously);
        backend.StateReceived += value => { if (value.Connected) refreshed.TrySetResult(value); else initial.TrySetResult(value); };
        backend.Start(fixture, []);
        Check((await initial.Task.WaitAsync(TimeSpan.FromSeconds(5))).DryRun, "first pipe state received");
        await backend.SendAsync("refresh");
        Check((await refreshed.Task.WaitAsync(TimeSpan.FromSeconds(5))).Connected, "refresh command roundtrip");
        await backend.StopAsync().WaitAsync(TimeSpan.FromSeconds(7));
        Check(true, "quit stops owned backend gracefully");
        await backend.StopAsync();
        Check(true, "shutdown is repeatable");

        foreach (var failure in new[] { "malformed", "eof", "oversized" })
        {
            var stopped = Path.Combine(directory, failure + ".stopped");
            var failingFixture = Path.Combine(directory, "backend-" + failure);
            await File.WriteAllTextAsync(failingFixture, "#!/usr/bin/env python3\nimport json,os,sys\n" +
                (failure switch
                {
                    "malformed" => "print('not-json',flush=True)\n",
                    "oversized" => "sys.stdout.write('x'*" + (NativeProtocol.MaximumLineLength + 1) + ");sys.stdout.flush()\n",
                    _ => "os.close(1)\n"
                }) +
                "for line in sys.stdin:\n if json.loads(line)['type']=='quit':\n  open(" + JsonSerializer.Serialize(stopped) + ", 'w').close()\n  break\n");
            File.SetUnixFileMode(failingFixture, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            using var failingBackend = new BackendProcess();
            var failed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            failingBackend.Failed += () => failed.TrySetResult();
            failingBackend.Start(failingFixture, []);
            await failed.Task.WaitAsync(TimeSpan.FromSeconds(5));
            var deadline = DateTime.UtcNow.AddSeconds(5);
            while (!File.Exists(stopped) && DateTime.UtcNow < deadline) await Task.Delay(20);
            Check(File.Exists(stopped), failure + " automatically requests owned backend shutdown");
            await failingBackend.StopAsync().WaitAsync(TimeSpan.FromSeconds(7));
        }

        var blockedFixture = Path.Combine(directory, "blocked-backend");
        var pidFile = Path.Combine(directory, "blocked.pid");
        await File.WriteAllTextAsync(blockedFixture, "#!/usr/bin/env python3\nimport os,time\nopen(" +
            JsonSerializer.Serialize(pidFile) + ", 'w').write(str(os.getpid()))\nprint(" +
            JsonSerializer.Serialize(Snapshot()) + ",flush=True)\ntime.sleep(60)\n");
        File.SetUnixFileMode(blockedFixture, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        using var blockedBackend = new BackendProcess();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        blockedBackend.StateReceived += _ => started.TrySetResult();
        blockedBackend.Start(blockedFixture, []);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var owned = Process.GetProcessById(int.Parse(await File.ReadAllTextAsync(pidFile)));
        var sent = 0;
        var pump = Task.Run(async () =>
        {
            try { while (true) { await blockedBackend.SendAsync("refresh"); Interlocked.Increment(ref sent); } }
            catch (Exception error) when (error is IOException or InvalidOperationException or OperationCanceledException) { }
        });
        try
        {
            // A stable command count while the child does not read proves the pipe is blocked.
            var watch = Stopwatch.StartNew();
            var stable = false;
            while (watch.Elapsed < TimeSpan.FromSeconds(3))
            {
                var before = Volatile.Read(ref sent);
                await Task.Delay(100);
                if (before > 0 && before == Volatile.Read(ref sent) && !pump.IsCompleted) { stable = true; break; }
            }
            Check(stable, "fixture fills stdin pipe and leaves a writer blocked");
            watch.Restart();
            await blockedBackend.StopAsync().WaitAsync(TimeSpan.FromSeconds(9));
            Check(owned.HasExited && watch.Elapsed < TimeSpan.FromSeconds(9), "whole shutdown bounds blocked writer and stops only owned backend");
            await pump.WaitAsync(TimeSpan.FromSeconds(2));
        }
        finally
        {
            if (!owned.HasExited) { owned.Kill(entireProcessTree: true); await owned.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
            await blockedBackend.StopAsync().WaitAsync(TimeSpan.FromSeconds(9));
        }
    }
    finally { Directory.Delete(directory, recursive: true); }
}
count += StartupChecks.Run();
count += LocalizationChecks.Run();
count += await UpdateChecks.RunAsync();
Console.WriteLine($"Passed {count} native Windows UI protocol/lifecycle/localization checks; no WinForms window or real input was launched.");
