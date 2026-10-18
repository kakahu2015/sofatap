using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Runtime.Versioning;
using System.Text.Json;

namespace SofaTap.Desktop;

[SupportedOSPlatform("windows")]
internal sealed class WindowsStartupStore : IStartupStore
{
    private readonly string _executable;
    private readonly string _shortcut;
    private readonly string _preference;

    internal WindowsStartupStore() : this(
        Environment.ProcessPath ?? throw new IOException("Executable path is unavailable."),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Startup, Environment.SpecialFolderOption.DoNotVerify), "SofaTap.lnk"),
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify), "SofaTap", "desktop-startup.json")) { }

    // Explicit paths are used by isolated tests; never point them at user Startup.
    internal WindowsStartupStore(string executable, string shortcut, string preference)
    {
        if (!Path.IsPathFullyQualified(executable) || !Path.IsPathFullyQualified(shortcut) || !Path.IsPathFullyQualified(preference))
            throw new IOException("Windows startup paths are unavailable.");
        _executable = executable;
        _shortcut = shortcut;
        _preference = preference;
    }

    public bool ShortcutExists => File.Exists(_shortcut);

    public bool? ReadPreference()
    {
        if (!File.Exists(_preference)) return null;
        using var json = JsonDocument.Parse(File.ReadAllText(_preference));
        return json.RootElement.GetProperty("enabled").GetBoolean();
    }

    public void WritePreference(bool enabled)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_preference)!);
        var temporary = _preference + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(new { enabled }));
            File.Move(temporary, _preference, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    public void EnsureShortcut()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_shortcut)!);
        WithShortcut((link, file) =>
        {
            var existing = ReadShortcut(link);
            if (ShortcutExists) ValidateOwnership(existing.Target);
            var directory = Path.GetDirectoryName(_executable)!;
            if (ShortcutExists && string.Equals(existing.Target, _executable, StringComparison.OrdinalIgnoreCase) &&
                existing.Arguments == "--startup" && string.Equals(existing.WorkingDirectory, directory, StringComparison.OrdinalIgnoreCase)) return;
            link.SetPath(_executable);
            link.SetArguments("--startup");
            link.SetWorkingDirectory(directory);
            link.SetDescription(Strings.Get("HostTap Remote iPhone remote receiver"));
            link.SetIconLocation(_executable, 0);
            file.Save(_shortcut, true);
            // Preserve this stable shortcut name and Windows' Startup Apps approval.
            // Never write StartupApproved or use another registration to evade a disable.
        });
        var saved = ReadShortcut();
        if (!string.Equals(saved.Target, _executable, StringComparison.OrdinalIgnoreCase) || saved.Arguments != "--startup" ||
            !string.Equals(saved.WorkingDirectory, Path.GetDirectoryName(_executable), StringComparison.OrdinalIgnoreCase))
            throw new IOException("Windows did not save the expected startup shortcut.");
    }

    internal ShortcutMetadata ReadShortcut()
    {
        ShortcutMetadata? metadata = null;
        WithShortcut((link, _) => metadata = ReadShortcut(link));
        return metadata!;
    }

    private static ShortcutMetadata ReadShortcut(IShellLinkW link)
    {
        var target = new StringBuilder(32768);
        var arguments = new StringBuilder(32768);
        var directory = new StringBuilder(32768);
        link.GetPath(target, target.Capacity, IntPtr.Zero, 4); // SLGP_RAWPATH, never resolve or launch.
        link.GetArguments(arguments, arguments.Capacity);
        link.GetWorkingDirectory(directory, directory.Capacity);
        return new(target.ToString(), arguments.ToString(), directory.ToString());
    }

    private static void ValidateOwnership(string target)
    {
        if (!string.Equals(Path.GetFileName(target), "SofaTap.exe", StringComparison.OrdinalIgnoreCase))
            throw new IOException("The Sofatap startup shortcut points to another application.");
    }

    private void WithShortcut(Action<IShellLinkW, IPersistFile> action)
    {
        var shellLink = new ShellLink();
        try
        {
            var link = (IShellLinkW)shellLink;
            var file = (IPersistFile)shellLink;
            if (ShortcutExists) file.Load(_shortcut, 0); // STGM_READ; no shell resolution or activation.
            action(link, file);
        }
        finally { Marshal.FinalReleaseComObject(shellLink); }
    }

    public void RemoveShortcut()
    {
        if (!File.Exists(_shortcut)) return;
        WithShortcut((link, _) => ValidateOwnership(ReadShortcut(link).Target));
        File.Delete(_shortcut);
    }

    internal sealed record ShortcutMetadata(string Target, string Arguments, string WorkingDirectory);

    // IShellLinkW vtable order is defined by shobjidl_core.h. Use the Unicode
    // interface directly instead of WScript's IDispatch/dynamic property setter.
    // https://learn.microsoft.com/windows/win32/api/shobjidl_core/nn-shobjidl_core-ishelllinkw
    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class ShellLink { }

    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int capacity, IntPtr findData, uint flags);
        void GetIDList(out IntPtr itemIdList);
        void SetIDList(IntPtr itemIdList);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder description, int capacity);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string description);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder directory, int capacity);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string directory);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder arguments, int capacity);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string arguments);
        void GetHotkey(out ushort hotkey);
        void SetHotkey(ushort hotkey);
        void GetShowCmd(out int showCommand);
        void SetShowCmd(int showCommand);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder iconPath, int capacity, out int iconIndex);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string iconPath, int iconIndex);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string relativePath, uint reserved);
        void Resolve(IntPtr window, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
    }
}
