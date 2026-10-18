namespace SofaTap.Desktop;

// Store/MSIX builds are installed into the read-only WindowsApps directory and
// Microsoft Store owns updates and launch-at-login. Building with
// -p:SofatapMsix=true defines SOFATAP_MSIX, which disables the in-app updater
// and the shell:startup shortcut UI instead of failing against those paths.
internal static class StoreBuild
{
#if SOFATAP_MSIX
    internal static readonly bool Packaged = true;
#else
    internal static readonly bool Packaged = false;
#endif
}
