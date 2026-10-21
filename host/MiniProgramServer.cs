using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace SofaTap;

internal sealed class MiniProgramServer : IDisposable
{
    private const string Prefix = "HTMP1";
    private static readonly byte[] ProofLabel = Encoding.ASCII.GetBytes("HostTap MiniProgram v1\0");
    private static readonly byte[] HkdfInfo = Encoding.ASCII.GetBytes("HostTap MiniProgram v1 session");
    private static readonly JsonSerializerOptions JsonOptions = LiveTextWire.Options;

    private readonly ReceiverOptions _options;
    private readonly byte[] _masterKey;
    private readonly IInputInjector _input;
    private readonly ITextFocusProbe? _textFocus;
    private readonly Func<ILiveTextEditor>? _textEditorFactory;
    private readonly SemaphoreSlim _controller;
    private readonly TcpListener _listener;
    private CancellationTokenRegistration _stopRegistration;
    private bool _started;
    private bool _disposed;

    internal MiniProgramServer(ReceiverOptions options, string token, IInputInjector input,
        ITextFocusProbe? textFocus, Func<ILiveTextEditor>? textEditorFactory, SemaphoreSlim controller)
    {
        _options = options;
        _input = input;
        _textFocus = textFocus;
        _textEditorFactory = textEditorFactory;
        _controller = controller;
        WindowsInput.Diagnostics = Log;
        _masterKey = DecodeBase64Url(token);
        if (_masterKey.Length != 32) throw new InvalidOperationException("Invalid mini program pairing key.");
        _listener = new TcpListener(IPAddress.Any, options.MiniProgramPort);
    }

    internal void Start(CancellationToken stopping)
    {
        if (_started) return;
        _started = true;
        try { _listener.Start(8); }
        catch (SocketException)
        {
            Console.Error.WriteLine($"Mini Program control port {_options.MiniProgramPort} is unavailable; native control remains available.");
            return;
        }
        _stopRegistration = stopping.Register(_listener.Stop);
        _ = Task.Run(() => AcceptLoop(stopping), CancellationToken.None);
    }

    private async Task AcceptLoop(CancellationToken stopping)
    {
        while (!stopping.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(stopping); }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) when (stopping.IsCancellationRequested || _disposed) { break; }
            catch (SocketException) when (stopping.IsCancellationRequested || _disposed) { break; }
            _ = HandleAcceptedClient(client, stopping);
        }
    }

    private async Task HandleAcceptedClient(TcpClient client, CancellationToken stopping)
    {
        using (client)
        {
            try { await HandleClient(client, stopping); }
            catch (OperationCanceledException) when (stopping.IsCancellationRequested) { }
            catch (IOException) { }
            catch (SocketException) { }
            catch (CryptographicException) { }
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken stopping)
    {
        client.NoDelay = true;
        Log($"accept from {(client.Client.RemoteEndPoint as IPEndPoint)?.Address}");
        using var stream = client.GetStream();
        using var handshake = CancellationTokenSource.CreateLinkedTokenSource(stopping);
        handshake.CancelAfter(TimeSpan.FromSeconds(10));

        var hello = await ReadLine(stream, 256, handshake.Token);
        var helloParts = hello.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (helloParts.Length != 3 || helloParts[0] != Prefix || helloParts[1] != "HELLO") { Log("reject: bad hello"); return; }
        var clientNonce = DecodeBase64Url(helloParts[2]);
        if (clientNonce.Length != 32) { Log("reject: bad nonce"); return; }

        var serverNonce = RandomNumberGenerator.GetBytes(32);
        var serverProof = Proof("server", clientNonce, serverNonce);
        await WriteLine(stream, $"{Prefix} CHALLENGE {EncodeBase64Url(serverNonce)} {EncodeBase64Url(serverProof)}", handshake.Token);

        var auth = await ReadLine(stream, 256, handshake.Token);
        var authParts = auth.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (authParts.Length != 3 || authParts[0] != Prefix || authParts[1] != "AUTH") return;
        var candidate = DecodeBase64Url(authParts[2]);
        var expected = Proof("client", clientNonce, serverNonce);
        if (candidate.Length != expected.Length || !CryptographicOperations.FixedTimeEquals(candidate, expected)) return;

        if (!await _controller.WaitAsync(0, handshake.Token))
        {
            Log("busy: controller already held");
            await WriteLine(stream, $"{Prefix} BUSY", handshake.Token);
            return;
        }
        Log("auth ok");

        try
        {
            await WriteLine(stream, $"{Prefix} OK", handshake.Token);
            var material = Hkdf(_masterKey, Combine(clientNonce, serverNonce), HkdfInfo, 128);
            using var channel = new SecureChannel(stream,
                material.AsSpan(64, 32).ToArray(), material.AsSpan(96, 32).ToArray(),
                material.AsSpan(0, 32).ToArray(), material.AsSpan(32, 32).ToArray());
            CryptographicOperations.ZeroMemory(material);
            await RunController(channel, stopping);
        }
        finally
        {
            _input.ReleaseAll();
            _controller.Release();
        }
    }

    private async Task RunController(SecureChannel channel, CancellationToken stopping)
    {
        var focusTracker = new TextFocusTracker();
        TextFocusMetadata Focus(string code) => focusTracker.Update(
            ReceiverDistribution.SupportsTextInspection && code == "ready" && !_options.DryRun && _textFocus is not null
                ? _textFocus.Read() : new(TextFocusKind.Unknown), code == "ready" && !_options.DryRun);
        var status = ReceiverPlatform.StatusCode(_input, _options.DryRun);
        Log($"hello status={status} dryRun={_options.DryRun}");
        using var liveText = ReceiverDistribution.SupportsTextInspection && !_options.DryRun && _textEditorFactory is not null
            ? new LiveTextSession(_textEditorFactory(), () => ReceiverPlatform.StatusCode(_input, _options.DryRun) == "ready") : null;

        await channel.SendJson(ReceiverServer.CreateHello(Environment.MachineName, ReceiverPlatform.Id,
            _options.DryRun, status, Focus(status), liveText is not null), stopping);
        var session = new ActionSession(_input, liveText: liveText);

        while (!stopping.IsCancellationRequested)
        {
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(stopping);
            idle.CancelAfter(TimeSpan.FromSeconds(20));
            byte[] bytes;
            try { bytes = await channel.Receive(idle.Token); }
            catch (OperationCanceledException) when (!stopping.IsCancellationRequested) { return; }
            if (bytes.Length == 0) return;

            ActionAck ack;
            bool isPing = false;
            string actionType = "";
            try
            {
                var action = Protocol.Parse(bytes);
                actionType = action.Type;
                isPing = action.Type == "ping";
                ack = session.Execute(action);
            }
            catch (ProtocolException error)
            {
                Log($"action rejected type={actionType} bytes={bytes.Length} error={error.Message}");
                ack = ActionAck.Failure(Protocol.TryReadId(bytes), error.Message);
                await channel.SendJson(ack, stopping);
                if (error.Message.StartsWith("Session action limit", StringComparison.Ordinal)) return;
                continue;
            }
            catch (JsonException) { Log($"malformed json bytes={bytes.Length}"); ack = ActionAck.Failure("", "Malformed JSON."); }
            catch (InvalidOperationException) { ack = ActionAck.Failure(Protocol.TryReadId(bytes), "Invalid JSON string."); }

            if (!isPing) Log($"action type={actionType} ok={ack.Ok} error={ack.Error}");

            if (isPing)
            {
                var currentStatus = ReceiverPlatform.StatusCode(_input, _options.DryRun);
                if (currentStatus != "ready") liveText?.End();
                await channel.SendJson(new { ack.Type, ack.Id, ack.Ok, ack.Error,
                    inputAvailable = currentStatus == "ready", inputStatus = currentStatus, dryRun = _options.DryRun,
                    textFocus = Focus(currentStatus) }, stopping);
            }
            else await channel.SendJson(ack, stopping);
        }
    }

    private static readonly object LogGate = new();

    // Diagnostics only: record action types and outcomes, never payload text or keys.
    private static void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(Credentials.DefaultDirectory);
            var line = $"{DateTime.Now:HH:mm:ss.fff} {message}{Environment.NewLine}";
            lock (LogGate) File.AppendAllText(Path.Combine(Credentials.DefaultDirectory, "miniprogram.log"), line);
        }
        catch { }
    }

    private byte[] Proof(string role, byte[] clientNonce, byte[] serverNonce)
    {
        var roleBytes = Encoding.ASCII.GetBytes(role + "\0");
        var transcript = new byte[ProofLabel.Length + roleBytes.Length + clientNonce.Length + serverNonce.Length];
        var offset = 0;
        ProofLabel.CopyTo(transcript, offset); offset += ProofLabel.Length;
        roleBytes.CopyTo(transcript, offset); offset += roleBytes.Length;
        clientNonce.CopyTo(transcript, offset); offset += clientNonce.Length;
        serverNonce.CopyTo(transcript, offset);
        using var hmac = new HMACSHA256(_masterKey);
        return hmac.ComputeHash(transcript);
    }

    private static byte[] Hkdf(byte[] ikm, byte[] salt, byte[] info, int length)
    {
        using var extract = new HMACSHA256(salt);
        var prk = extract.ComputeHash(ikm);
        try
        {
            var result = new byte[length];
            var previous = Array.Empty<byte>();
            var written = 0;
            byte counter = 1;
            while (written < length)
            {
                using var expand = new HMACSHA256(prk);
                var input = new byte[previous.Length + info.Length + 1];
                previous.CopyTo(input, 0);
                info.CopyTo(input, previous.Length);
                input[^1] = counter++;
                previous = expand.ComputeHash(input);
                var take = Math.Min(previous.Length, length - written);
                previous.AsSpan(0, take).CopyTo(result.AsSpan(written));
                written += take;
            }
            return result;
        }
        finally { CryptographicOperations.ZeroMemory(prk); }
    }

    private static byte[] Combine(byte[] first, byte[] second)
    {
        var result = new byte[first.Length + second.Length];
        first.CopyTo(result, 0);
        second.CopyTo(result, first.Length);
        return result;
    }

    private static async Task<string> ReadLine(NetworkStream stream, int maximumBytes, CancellationToken cancellation)
    {
        var bytes = new List<byte>(64);
        var one = new byte[1];
        while (bytes.Count <= maximumBytes)
        {
            var count = await stream.ReadAsync(one, cancellation);
            if (count == 0) throw new IOException("Connection closed during handshake.");
            if (one[0] == (byte)'\n')
            {
                if (bytes.Count > 0 && bytes[^1] == (byte)'\r') bytes.RemoveAt(bytes.Count - 1);
                return Encoding.ASCII.GetString([.. bytes]);
            }
            if (one[0] > 0x7F) throw new IOException("Invalid handshake encoding.");
            bytes.Add(one[0]);
        }
        throw new IOException("Handshake line is too long.");
    }

    private static async Task WriteLine(NetworkStream stream, string value, CancellationToken cancellation)
    {
        var bytes = Encoding.ASCII.GetBytes(value + "\n");
        await stream.WriteAsync(bytes, cancellation);
    }

    private static string EncodeBase64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] DecodeBase64Url(string value)
    {
        if (value.Length == 0 || value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) return [];
        var padded = value.Replace('-', '+').Replace('_', '/');
        padded += new string('=', (4 - padded.Length % 4) % 4);
        try { return Convert.FromBase64String(padded); }
        catch (FormatException) { return []; }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _stopRegistration.Dispose();
        _listener.Stop();
        CryptographicOperations.ZeroMemory(_masterKey);
    }

    private sealed class SecureChannel(NetworkStream stream, byte[] sendEnc, byte[] sendMac, byte[] receiveEnc, byte[] receiveMac) : IDisposable
    {
        private const int IvBytes = 16;
        private const int TagBytes = 32;
        private const int MaximumCipherBytes = Protocol.MaximumFrameBytes + 16;
        private uint _sendSequence;
        private uint _receiveSequence;

        internal async Task SendJson(object value, CancellationToken cancellation)
        {
            var plaintext = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
            if (plaintext.Length > Protocol.MaximumFrameBytes) throw new IOException("Response exceeds 64 KiB.");
            await Send(plaintext, cancellation);
        }

        private async Task Send(byte[] plaintext, CancellationToken cancellation)
        {
            if (_sendSequence == uint.MaxValue) throw new IOException("Secure channel sequence exhausted.");
            var iv = RandomNumberGenerator.GetBytes(IvBytes);
            byte[] ciphertext;
            using (var aes = Aes.Create())
            {
                aes.Key = sendEnc;
                aes.IV = iv;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                using var encryptor = aes.CreateEncryptor();
                ciphertext = encryptor.TransformFinalBlock(plaintext, 0, plaintext.Length);
            }
            var authenticated = new byte[1 + 4 + IvBytes + ciphertext.Length];
            authenticated[0] = 1;
            BinaryPrimitives.WriteUInt32BigEndian(authenticated.AsSpan(1, 4), _sendSequence++);
            iv.CopyTo(authenticated, 5);
            ciphertext.CopyTo(authenticated, 5 + IvBytes);
            byte[] tag;
            using (var hmac = new HMACSHA256(sendMac)) tag = hmac.ComputeHash(authenticated);
            var length = authenticated.Length + tag.Length;
            var frame = new byte[4 + length];
            BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(0, 4), (uint)length);
            authenticated.CopyTo(frame, 4);
            tag.CopyTo(frame, 4 + authenticated.Length);
            await stream.WriteAsync(frame, cancellation);
        }

        internal async Task<byte[]> Receive(CancellationToken cancellation)
        {
            var lengthBytes = new byte[4];
            if (!await ReadExactlyOrEof(lengthBytes, cancellation)) return [];
            var encodedLength = BinaryPrimitives.ReadUInt32BigEndian(lengthBytes);
            var minimum = 1 + 4 + IvBytes + 16 + TagBytes;
            var maximum = 1 + 4 + IvBytes + MaximumCipherBytes + TagBytes;
            if (encodedLength < (uint)minimum || encodedLength > (uint)maximum)
                throw new IOException("Invalid secure frame length.");
            var length = (int)encodedLength;
            var body = new byte[length];
            await stream.ReadExactlyAsync(body, cancellation);
            if (body[0] != 1) throw new IOException("Unsupported secure frame version.");
            var sequence = BinaryPrimitives.ReadUInt32BigEndian(body.AsSpan(1, 4));
            if (sequence != _receiveSequence || _receiveSequence == uint.MaxValue) throw new IOException("Invalid secure frame sequence.");
            var authenticatedLength = body.Length - TagBytes;
            byte[] expected;
            using (var hmac = new HMACSHA256(receiveMac)) expected = hmac.ComputeHash(body.AsSpan(0, authenticatedLength).ToArray());
            if (!CryptographicOperations.FixedTimeEquals(expected, body.AsSpan(authenticatedLength, TagBytes)))
                throw new CryptographicException("Secure frame authentication failed.");
            var iv = body.AsSpan(5, IvBytes).ToArray();
            var ciphertext = body.AsSpan(5 + IvBytes, authenticatedLength - 5 - IvBytes).ToArray();
            byte[] plaintext;
            using (var aes = Aes.Create())
            {
                aes.Key = receiveEnc;
                aes.IV = iv;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                using var decryptor = aes.CreateDecryptor();
                plaintext = decryptor.TransformFinalBlock(ciphertext, 0, ciphertext.Length);
            }
            if (plaintext.Length > Protocol.MaximumFrameBytes) throw new IOException("Secure frame plaintext is too large.");
            _receiveSequence++;
            return plaintext;
        }

        private async Task<bool> ReadExactlyOrEof(byte[] target, CancellationToken cancellation)
        {
            var offset = 0;
            while (offset < target.Length)
            {
                var count = await stream.ReadAsync(target.AsMemory(offset), cancellation);
                if (count == 0) return offset == 0 ? false : throw new IOException("Truncated secure frame.");
                offset += count;
            }
            return true;
        }

        public void Dispose()
        {
            CryptographicOperations.ZeroMemory(sendEnc);
            CryptographicOperations.ZeroMemory(sendMac);
            CryptographicOperations.ZeroMemory(receiveEnc);
            CryptographicOperations.ZeroMemory(receiveMac);
        }
    }
}
