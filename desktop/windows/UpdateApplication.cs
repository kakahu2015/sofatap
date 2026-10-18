using System.Diagnostics;
using System.Text.Json;

namespace SofaTap.Desktop;

internal static class UpdateApplication
{
    internal static bool ValidId(string id) => id.Length == 32 && id.All(char.IsAsciiHexDigit);
    private static string TicketDirectory(string id)
    {
        if (!ValidId(id)) throw new InvalidDataException("更新标识无效。");
        var path = Path.Combine(WindowsUpdates.Root, id);
        UpdatePackage.NoLinks(path);
        return path;
    }
    internal static UpdateTicket ReadTicket(string id)
    {
        var path = Path.Combine(TicketDirectory(id), "ticket.json");
        if (new FileInfo(path).Length > 32768) throw new InvalidDataException("更新计划无效。");
        var ticket = JsonSerializer.Deserialize<UpdateTicket>(File.ReadAllText(path)) ?? throw new InvalidDataException("更新计划缺失。");
        if (ticket.Id != id || ticket.Release is null || !Path.IsPathFullyQualified(ticket.Installation) ||
            ticket.Installation != Path.TrimEndingDirectorySeparator(Path.GetFullPath(ticket.Installation)) ||
            Path.GetDirectoryName(ticket.Installation) is null || !UpdatePackage.IsHash(ticket.InstalledExecutableHash) ||
            ReceiverVersion.Parse(ticket.InstalledVersion) is not { } installed || ReceiverVersion.Parse(ticket.Release.Version) is not { } next || next.CompareTo(installed) <= 0 ||
            ticket.Release.Tag != "v" + ticket.Release.Version ||
            ticket.Release.Url != "https://github.com/chekusu/sofatap/releases/download/" + ticket.Release.Tag + "/" + UpdatePackage.AssetName)
            throw new InvalidDataException("更新计划与本机安装不匹配。");
        UpdatePackage.NoLinks(ticket.Installation);
        return ticket;
    }

    // Called only for a normal launch before backend startup, or after the user
    // explicitly selected Install and Restart and the owned backend has stopped.
    internal static bool StartPrepared(bool background)
    {
        var settings = WindowsUpdates.ReadSettings();
        if (settings.PendingTicket is not { } id) return false;
        var ticket = ReadTicket(id);
        var executable = Environment.ProcessPath ?? throw new IOException("安装路径不可用。");
        if (!string.Equals(executable, Path.Combine(ticket.Installation, "SofaTap.exe"), StringComparison.OrdinalIgnoreCase) ||
            WindowsUpdates.CurrentVersion != ticket.InstalledVersion || UpdatePackage.HashFile(executable) != ticket.InstalledExecutableHash)
            throw new InvalidDataException("更新计划对应其他安装，未执行更新。");
        _ = UpdatePackage.ReadManifest(Path.Combine(ticket.Installation, UpdatePackage.ManifestName), ticket.InstalledVersion);
        using var parent = Process.GetCurrentProcess();
        ticket = ticket with { ParentPid = parent.Id, ParentStartTicks = parent.StartTime.ToUniversalTime().Ticks, Background = background };
        WindowsUpdates.AtomicJson(Path.Combine(TicketDirectory(id), "ticket.json"), ticket);
        var runner = Path.Combine(TicketDirectory(id), "apply.exe");
        if (File.Exists(runner)) throw new IOException("更新安装程序已经存在，请重新检查更新。");
        File.Copy(executable, runner);
        if (UpdatePackage.HashFile(runner) != ticket.InstalledExecutableHash) throw new InvalidDataException("更新安装程序校验失败。");
        var info = new ProcessStartInfo(runner) { UseShellExecute = false, WorkingDirectory = TicketDirectory(id) };
        info.ArgumentList.Add("--apply-update"); info.ArgumentList.Add(id);
        using var process = Process.Start(info) ?? throw new IOException("无法启动更新安装程序。");
        return true;
    }

    internal static ReceiverLaunchOptions HealthLaunch(string id)
    {
        var ticket = ReadTicket(id);
        if (!string.Equals(Environment.ProcessPath, Path.Combine(ticket.Installation, "SofaTap.exe"), StringComparison.OrdinalIgnoreCase) || WindowsUpdates.CurrentVersion != ticket.Release.Version ||
            !File.Exists(Path.Combine(TicketDirectory(id), "applying")))
            throw new InvalidDataException("更新验证启动无效。");
        _ = UpdatePackage.ReadManifest(Path.Combine(ticket.Installation, UpdatePackage.ManifestName), ticket.Release.Version);
        return new(false, ticket.Background);
    }
    internal static void MarkHealthy(string id)
    {
        _ = HealthLaunch(id);
        WindowsUpdates.AtomicJson(Path.Combine(TicketDirectory(id), "healthy.json"), new { process = Environment.ProcessId, version = WindowsUpdates.CurrentVersion });
    }

    internal static async Task<int> ApplyAsync(string id)
    {
        UpdateTicket? ticket = null;
        string? stage = null; string? backup = null;
        Process? replacement = null;
        UpdateTransaction? transaction = null;
        var mayRestartOriginal = false;
        try
        {
            ticket = ReadTicket(id);
            if (!string.Equals(Environment.ProcessPath, Path.Combine(TicketDirectory(id), "apply.exe"), StringComparison.OrdinalIgnoreCase) ||
                UpdatePackage.HashFile(Environment.ProcessPath!) != ticket.InstalledExecutableHash || ticket.ParentPid <= 0 || ticket.ParentStartTicks <= 0)
                throw new InvalidDataException("更新安装程序身份不匹配。");
            // The ticket is consumed once. Failed or interrupted installs cannot
            // retry automatically on every subsequent user launch.
            using (new FileStream(Path.Combine(TicketDirectory(id), "applying"), FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
            try
            {
                using var parent = Process.GetProcessById(ticket.ParentPid);
                if (parent.StartTime.ToUniversalTime().Ticks != ticket.ParentStartTicks ||
                    !string.Equals(parent.MainModule?.FileName, Path.Combine(ticket.Installation, "SofaTap.exe"), StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("更新等待的进程不匹配。");
                await parent.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(30));
            }
            catch (ArgumentException) { /* Parent already exited after starting this helper. */ }
            using var receiverLock = new Mutex(false, @"Local\Chekusu.SofaTap.Receiver");
            bool locked;
            try { locked = receiverLock.WaitOne(TimeSpan.FromSeconds(10)); }
            catch (AbandonedMutexException) { locked = true; }
            if (!locked) throw new IOException("接收器仍在运行，暂不更新。");
            try
            {
                var settings = WindowsUpdates.ReadSettings();
                if (settings.PendingTicket != id) throw new InvalidDataException("更新任务已被替换。");
                WindowsUpdates.SaveSettings(settings with { PendingTicket = null });
                var oldManifest = UpdatePackage.ReadManifest(Path.Combine(ticket.Installation, UpdatePackage.ManifestName), ticket.InstalledVersion);
                if (UpdatePackage.HashFile(Path.Combine(ticket.Installation, "SofaTap.exe")) != ticket.InstalledExecutableHash)
                    throw new InvalidDataException("安装文件已改变，未执行更新。");
                mayRestartOriginal = true;
                var parentDirectory = Path.GetDirectoryName(ticket.Installation)!;
                stage = Path.Combine(parentDirectory, ".Sofatap-stage-" + id);
                backup = Path.Combine(parentDirectory, ".Sofatap-backup-" + id);
                if (Directory.Exists(backup) || File.Exists(backup)) throw new IOException("备份位置已存在。");
                _ = UpdatePackage.ExtractVerified(Path.Combine(TicketDirectory(id), "package.zip"), ticket.Release, stage);
                UpdatePackage.PreserveUnmanagedFiles(ticket.Installation, oldManifest, stage);
                // Rename operations stay on the same volume. A locked executable
                // or lack of write permission leaves the original installation intact.
                transaction = new UpdateTransaction(ticket.Installation, stage, backup, Path.Combine(parentDirectory, ".Sofatap-failed-" + id));
                transaction.Install();
            }
            finally { receiverLock.ReleaseMutex(); }
            // Program's single-instance check uses named-object creation. Remove
            // our handle before the new receiver tries to create that object.
            receiverLock.Dispose();
            var start = new ProcessStartInfo(Path.Combine(ticket.Installation, "SofaTap.exe")) { UseShellExecute = false, WorkingDirectory = ticket.Installation };
            start.ArgumentList.Add("--complete-update"); start.ArgumentList.Add(id);
            replacement = Process.Start(start) ?? throw new IOException("新版本无法启动。");
            var healthPath = Path.Combine(TicketDirectory(id), "healthy.json");
            var deadline = DateTime.UtcNow.AddSeconds(45);
            while (DateTime.UtcNow < deadline)
            {
                if (replacement.HasExited) throw new IOException("新版本提前退出。");
                if (File.Exists(healthPath))
                {
                    using var health = JsonDocument.Parse(File.ReadAllText(healthPath));
                    if (health.RootElement.GetProperty("process").GetInt32() != replacement.Id || health.RootElement.GetProperty("version").GetString() != ticket.Release.Version)
                        throw new InvalidDataException("新版本启动校验不匹配。");
                    WindowsUpdates.AtomicJson(Path.Combine(TicketDirectory(id), "result.json"), new { status = "installed", version = ticket.Release.Version });
                    // Keep the previous complete directory as a recoverable backup.
                    // It includes any user-created files; never silently delete it.
                    return 0;
                }
                await Task.Delay(200);
            }
            throw new IOException("新版本未能启动接收服务。");
        }
        catch (Exception error) when (WindowsUpdates.IsRecoverable(error) || error is TimeoutException)
        {
            var replacementStopped = true;
            try
            {
                if (replacement is not null && !replacement.HasExited)
                {
                    replacement.Kill(entireProcessTree: true);
                    await replacement.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
                }
            }
            catch (Exception stopError) when (WindowsUpdates.IsRecoverable(stopError) || stopError is TimeoutException) { replacementStopped = false; }
            var restored = transaction?.Started != true;
            try
            {
                if (transaction?.Started == true && replacementStopped) { transaction.Rollback(); restored = true; }
            }
            catch (Exception restoreError) when (WindowsUpdates.IsRecoverable(restoreError)) { restored = false; }
            if (ticket is not null)
            {
                var message = restored
                    ? Strings.Get("Automatic update did not finish; the previous version was kept. Check for updates again.")
                    : Strings.Get("The update did not finish. The previous version was kept in the .Sofatap-backup- folder next to the installation folder; restore that folder.");
                try
                {
                    var settings = WindowsUpdates.ReadSettings();
                    WindowsUpdates.SaveSettings(settings with { PendingTicket = null, LastError = message, Failures = settings.Failures + 1, LastAttempt = DateTimeOffset.UtcNow });
                    WindowsUpdates.AtomicJson(Path.Combine(TicketDirectory(id), "result.json"), new { status = restored ? "rolled-back" : "recovery-needed", error = message });
                }
                catch (Exception saveError) when (WindowsUpdates.IsRecoverable(saveError)) { }
                if (restored && mayRestartOriginal && ticket.ParentPid > 0)
                {
                    try
                    {
                        var start = new ProcessStartInfo(Path.Combine(ticket.Installation, "SofaTap.exe")) { UseShellExecute = false, WorkingDirectory = ticket.Installation };
                        if (ticket.Background) start.ArgumentList.Add("--startup");
                        using var recovered = Process.Start(start);
                    }
                    catch (Exception startError) when (WindowsUpdates.IsRecoverable(startError)) { }
                }
            }
            return 1;
        }
        finally { replacement?.Dispose(); }
    }
}
