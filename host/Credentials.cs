using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace SofaTap;

public static class NetworkAddresses
{
    // Virtual adapters (VirtualBox host-only, Hyper-V/WSL, VPN/overlay) never carry
    // the home network the phone is on, so a pairing QR built from them can only fail.
    // Rank them last and prefer the adapter that actually has an IPv4 default gateway.
    private static readonly string[] VirtualAdapterMarkers =
    [
        "virtualbox", "vmware", "hyper-v", "vethernet", "wsl", "docker", "loopback",
        "tap-", "tap adapter", "tun", "vpn", "wireguard", "zerotier", "tailscale",
        "npcap", "hamachi", "radmin", "openvpn", "anyconnect"
    ];

    public static string[] LocalIPv4() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up && adapter.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(adapter =>
        {
            var properties = adapter.GetIPProperties();
            var virtualAdapter = VirtualAdapterMarkers.Any(marker =>
                (adapter.Name + " " + adapter.Description).Contains(marker, StringComparison.OrdinalIgnoreCase));
            var hasGateway = properties.GatewayAddresses.Any(gateway =>
                gateway.Address.AddressFamily == AddressFamily.InterNetwork && !gateway.Address.Equals(IPAddress.Any));
            var rank = virtualAdapter ? 2 : hasGateway ? 0 : 1;
            return properties.UnicastAddresses
                .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address.Address))
                .Select(address => (Address: address.Address.ToString(), Rank: rank));
        })
        .GroupBy(entry => entry.Address, StringComparer.Ordinal)
        .Select(group => (Address: group.Key, Rank: group.Min(entry => entry.Rank)))
        .OrderBy(entry => entry.Rank)
        .ThenBy(entry => entry.Address.StartsWith("192.168.", StringComparison.Ordinal) ? 0 : 1)
        .Select(entry => entry.Address)
        .ToArray();
}

public sealed class Credentials : IDisposable
{
    public string Token { get; }
    public X509Certificate2 Certificate { get; }
    public string Fingerprint => Convert.ToHexStringLower(SHA256.HashData(Certificate.RawData));
    public string[] CertificateIPv4 => Certificate.Extensions.OfType<X509SubjectAlternativeNameExtension>()
        .SelectMany(extension => extension.EnumerateIPAddresses()).Where(address => address.AddressFamily == AddressFamily.InterNetwork)
        .Select(address => address.ToString()).ToArray();
    private readonly FileStream _lock;
    private Credentials(string token, X509Certificate2 certificate, FileStream fileLock)
    { Token = token; Certificate = certificate; _lock = fileLock; }
    public static string DefaultDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SofaTap");

    public static Credentials LoadOrCreate(string directory) => LoadOrCreate(directory, NetworkAddresses.LocalIPv4());

    internal static Credentials LoadOrCreate(string directory, IReadOnlyCollection<string> localAddresses)
    {
        Directory.CreateDirectory(directory);
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(directory, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        var lockPath = Path.Combine(directory, "receiver.lock");
        FileStream fileLock;
        try { fileLock = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { throw new InvalidOperationException("Another Sofatap receiver is using this profile. Close it before starting another."); }
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(lockPath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        try
        {
            var path = Path.Combine(directory, "pairing.dat");
            Secret secret;
            X509Certificate2? loaded = null;
            if (File.Exists(path))
            {
                var stored = File.ReadAllBytes(path);
                var clear = OperatingSystem.IsWindows() ? Dpapi.Unprotect(stored) : stored;
                try { secret = JsonSerializer.Deserialize<Secret>(clear) ?? throw new InvalidDataException("Invalid pairing profile."); }
                finally { CryptographicOperations.ZeroMemory(clear); }
                if (secret.Token.Length != 43 || secret.Token.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_')))
                    throw new InvalidDataException("Invalid pairing profile. Use a new data directory to pair again.");
                loaded = LoadCertificate(secret);
                var certificateIPs = loaded.Extensions.OfType<X509SubjectAlternativeNameExtension>()
                    .SelectMany(extension => extension.EnumerateIPAddresses())
                    .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                    .Select(address => address.ToString()).ToHashSet(StringComparer.Ordinal);
                var currentIPs = localAddresses.ToHashSet(StringComparer.Ordinal);
                // Pure additions (for example a VPN adapter), pure removals and
                // offline restarts retain the paired TLS leaf and bearer token.
                // A replacement address still needs a new certificate even when
                // another address, such as a stable VPN endpoint, remains present.
                // NativeState advertises only addresses covered by this certificate.
                var hasNewAddress = currentIPs.Any(address => !certificateIPs.Contains(address));
                var networkChanged = hasNewAddress && (certificateIPs.Count == 0
                    || certificateIPs.Any(address => !currentIPs.Contains(address)));
                if (!loaded.HasPrivateKey || loaded.NotAfter.ToUniversalTime() < DateTime.UtcNow.AddDays(30)
                    || loaded.NotAfter.ToUniversalTime() - loaded.NotBefore.ToUniversalTime() > TimeSpan.FromDays(366)
                    || networkChanged)
                {
                    loaded.Dispose(); loaded = null;
                    // An address replacement still needs a new SAN-bound
                    // certificate and explicit pairing; never relax TLS validation.
                    secret = CreateSecret(localAddresses); SaveSecret(path, secret);
                    Console.Error.WriteLine("Local network identity changed or certificate renewed. Scan the pairing QR again on the iPhone.");
                }
            }
            else { secret = CreateSecret(localAddresses); SaveSecret(path, secret); }
            loaded ??= LoadCertificate(secret);
            return new(secret.Token, loaded, fileLock);
        }
        catch { fileLock.Dispose(); throw; }
    }
    private static X509Certificate2 LoadCertificate(Secret secret) => X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(secret.Pfx), "",
        // Schannel needs a named key container for server TLS. Keep it in this
        // user's store and let certificate disposal remove it (no PersistKeySet).
        OperatingSystem.IsWindows() ? X509KeyStorageFlags.UserKeySet :
        (OperatingSystem.IsMacOS() ? X509KeyStorageFlags.DefaultKeySet : X509KeyStorageFlags.EphemeralKeySet) | X509KeyStorageFlags.Exportable);
    private static Secret CreateSecret(IEnumerable<string> localAddresses)
    {
        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest("CN=Sofatap Local Receiver", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, true));
        var sans = new SubjectAlternativeNameBuilder(); sans.AddDnsName("localhost"); sans.AddIpAddress(IPAddress.Loopback); sans.AddIpAddress(IPAddress.IPv6Loopback);
        foreach (var address in localAddresses) sans.AddIpAddress(IPAddress.Parse(address));
        request.CertificateExtensions.Add(sans.Build());
        var notBefore = DateTimeOffset.UtcNow.AddMinutes(-5);
        using var certificate = request.CreateSelfSigned(notBefore, notBefore.AddDays(365));
        return new(Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_'),
            Convert.ToBase64String(certificate.Export(X509ContentType.Pfx, "")));
    }
    private static void SaveSecret(string path, Secret secret)
    {
        var clear = JsonSerializer.SerializeToUtf8Bytes(secret);
        var stored = OperatingSystem.IsWindows() ? Dpapi.Protect(clear) : clear;
        var temporary = path + ".new-" + Guid.NewGuid().ToString("N");
        try
        {
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
                output.Write(stored); output.Flush(true);
            }
            File.Move(temporary, path, true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }
    public void Dispose() { Certificate.Dispose(); _lock.Dispose(); }
    private sealed record Secret(string Token, string Pfx);

    internal static class Dpapi
    {
        [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Size; public IntPtr Data; }
        public static byte[] Protect(byte[] bytes) => Transform(bytes, true);
        public static byte[] Unprotect(byte[] bytes) => Transform(bytes, false);
        private static byte[] Transform(byte[] bytes, bool protect)
        {
            var input = new Blob { Size = bytes.Length, Data = Marshal.AllocHGlobal(bytes.Length) };
            Blob output = default;
            try
            {
                Marshal.Copy(bytes, 0, input.Data, bytes.Length);
                var ok = protect
                    ? CryptProtectData(ref input, "Sofatap local pairing", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                    : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
                if (!ok) throw new InvalidOperationException("Windows could not unlock this user's pairing profile. Run under the original Windows user, or choose a new data directory.");
                var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, output.Size); return result;
            }
            finally
            {
                for (var index = 0; index < bytes.Length; index++) Marshal.WriteByte(input.Data, index, 0);
                Marshal.FreeHGlobal(input.Data);
                if (output.Data != IntPtr.Zero) { for (var index = 0; index < output.Size; index++) Marshal.WriteByte(output.Data, index, 0); LocalFree(output.Data); }
            }
        }
        [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CryptProtectData(ref Blob input, string description, IntPtr entropy, IntPtr reserved, IntPtr prompt, uint flags, out Blob output);
        [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, uint flags, out Blob output);
        [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
    }
}
