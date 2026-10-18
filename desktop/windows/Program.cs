namespace SofaTap.Desktop;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        // Internal update modes never initialize the receiver or send input.
        if (args.Length == 2 && args[0] == "--apply-update")
            return UpdateApplication.ApplyAsync(args[1]).GetAwaiter().GetResult();
        string? healthTicket = null;
        ReceiverLaunchOptions? options;
        if (args.Length == 2 && args[0] == "--complete-update")
        {
            try { options = UpdateApplication.HealthLaunch(args[1]); healthTicket = args[1]; }
            catch (Exception error) when (WindowsUpdates.IsRecoverable(error)) { return 1; }
        }
        else options = ReceiverLaunchOptions.Parse(args);
        if (options is null)
        {
            ApplicationConfiguration.Initialize();
            MessageBox.Show(Strings.Get("This launcher only supports the --dry-run or --startup argument."), Strings.ProductName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return 1;
        }
        if (options.StartupPreference is bool enabled)
        {
            try
            {
                new StartupRegistration(new WindowsStartupStore()).SetEnabled(enabled);
                return 0;
            }
            catch (Exception error) when (StartupRegistration.IsRecoverableError(error)) { return 1; }
        }
        ApplicationConfiguration.Initialize();
        using var instance = new Mutex(true, @"Local\Chekusu.SofaTap.Receiver", out var created);
        using var showWindow = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\Chekusu.SofaTap.ShowWindow");
        if (!created)
        {
            // A login race must not open a window over the user's current work.
            if (!options.Startup) showWindow.Set();
            return 0;
        }
        if (options.ManagesStartup && healthTicket is null)
        {
            try
            {
                if (WindowsUpdates.ReadSettings() is { Automatic: true, PendingTicket: not null } && UpdateApplication.StartPrepared(options.Startup)) return 0;
            }
            catch (Exception error) when (WindowsUpdates.IsRecoverable(error))
            {
                try { WindowsUpdates.SaveSettings(WindowsUpdates.ReadSettings() with { PendingTicket = null, LastError = Strings.Get("Automatic update did not finish. The current version remains usable; check for updates again.") }); }
                catch (Exception saveError) when (WindowsUpdates.IsRecoverable(saveError)) { }
            }
        }
        using var window = new PairingWindow(options, healthTicket);
        _ = window.Handle; // Receive a concurrent manual reopen before Application.Run shows it.
        var registration = ThreadPool.RegisterWaitForSingleObject(showWindow, (_, _) => window.RequestShow(), null, Timeout.Infinite, executeOnlyOnce: false);
        try { Application.Run(window); }
        finally { registration.Unregister(null); }
        return 0;
    }
}
