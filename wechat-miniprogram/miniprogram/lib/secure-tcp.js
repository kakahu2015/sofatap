const {
  aesCbcDecrypt,
  aesCbcEncrypt,
  asciiBytes,
  base64UrlDecode,
  base64UrlEncode,
  bufferOf,
  concatBytes,
  constantTimeEqual,
  deriveSessionKeys,
  hmacSha256,
  pairingProof,
  randomBytes,
  utf8Decode,
  utf8Encode
} = require('./crypto')

const MAX_PLAINTEXT = 65536
const MAX_CIPHER = MAX_PLAINTEXT + 16
const TAG_BYTES = 32
const IV_BYTES = 16

function readU32(bytes, offset) {
  return (((bytes[offset] << 24) >>> 0) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0
}

function writeU32(value) {
  return new Uint8Array([(value >>> 24) & 0xff, (value >>> 16) & 0xff, (value >>> 8) & 0xff, value & 0xff])
}

function asciiDecode(bytes) {
  let out = ''
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] > 0x7f) throw new Error('安全握手编码无效')
    out += String.fromCharCode(bytes[i])
  }
  return out
}

class SecureTcp {
  constructor() {
    this.socket = null
    this.listener = null
    this.state = 'idle'
    this.incoming = new Uint8Array(0)
    this.masterKey = null
    this.clientNonce = null
    this.serverNonce = null
    this.sendEnc = null
    this.sendMac = null
    this.receiveEnc = null
    this.receiveMac = null
    this.sendSequence = 0
    this.receiveSequence = 0
    this.handshakeTimer = null
    this.connectResolve = null
    this.connectReject = null
    this.intentionalClose = false
  }

  async connect(pairing, listener) {
    if (this.state !== 'idle' && this.state !== 'closed') throw new Error('连接已经在进行中')
    this.listener = listener
    this.masterKey = base64UrlDecode(pairing.token)
    if (this.masterKey.length !== 32) throw new Error('配对密钥无效，请重新扫码')
    this.clientNonce = await randomBytes(32)
    this.state = 'connecting'
    this.intentionalClose = false
    this.incoming = new Uint8Array(0)

    return await new Promise((resolve, reject) => {
      this.connectResolve = resolve
      this.connectReject = reject
      const socket = wx.createTCPSocket()
      this.socket = socket
      socket.onConnect(() => {
        if (this.state !== 'connecting') return
        this.state = 'waitChallenge'
        socket.write(`HTMP1 HELLO ${base64UrlEncode(this.clientNonce)}\n`)
      })
      socket.onMessage((event) => this.onMessage(event))
      socket.onError(() => this.fail('无法连接电脑，请确认手机和电脑在同一局域网'))
      socket.onClose(() => {
        if (this.intentionalClose || this.state === 'closed') return
        this.fail('电脑连接已中断')
      })
      this.handshakeTimer = setTimeout(() => this.fail('电脑安全握手超时'), 10000)
      try {
        socket.connect({ address: pairing.host, port: pairing.miniProgramPort(), timeout: 5 })
      } catch (_) {
        this.fail('无法连接电脑')
      }
    })
  }

  isOpen() { return this.state === 'open' }

  async sendText(text) {
    if (!this.isOpen() || !this.socket) throw new Error('尚未连接电脑')
    const plaintext = utf8Encode(text)
    if (plaintext.length > MAX_PLAINTEXT) throw new Error('消息超过 64 KiB')
    if (this.sendSequence >= 0xffffffff) throw new Error('安全通道序号已耗尽，请重新连接')

    const sequenceBytes = writeU32(this.sendSequence)
    const iv = hmacSha256(this.sendMac, concatBytes(asciiBytes('iv\0'), sequenceBytes)).slice(0, IV_BYTES)
    const ciphertext = aesCbcEncrypt(this.sendEnc, iv, plaintext)
    if (ciphertext.length < 16 || ciphertext.length > MAX_CIPHER) throw new Error('加密消息长度无效')
    const authenticated = concatBytes(new Uint8Array([1]), sequenceBytes, iv, ciphertext)
    const tag = hmacSha256(this.sendMac, authenticated)
    const body = concatBytes(authenticated, tag)
    const frame = concatBytes(writeU32(body.length), body)
    this.sendSequence += 1
    this.socket.write(bufferOf(frame))
  }

  close() {
    this.intentionalClose = true
    this.clearHandshakeTimer()
    const socket = this.socket
    this.socket = null
    this.state = 'closed'
    this.incoming = new Uint8Array(0)
    if (socket) {
      try { socket.close() } catch (_) { }
    }
    const reject = this.connectReject
    this.connectResolve = null
    this.connectReject = null
    if (reject) reject(new Error('连接已取消'))
  }

  onMessage(event) {
    try {
      const raw = event && event.message instanceof ArrayBuffer ? event.message : (event instanceof ArrayBuffer ? event : null)
      if (!raw) throw new Error('电脑返回的数据无效')
      this.incoming = concatBytes(this.incoming, new Uint8Array(raw))
      if (this.state !== 'open') this.consumeHandshake()
      if (this.state === 'open') this.consumeFrames()
    } catch (error) {
      this.fail(error instanceof Error ? error.message : '电脑返回的数据无效')
    }
  }

  consumeHandshake() {
    while (this.state === 'waitChallenge' || this.state === 'waitOk') {
      const newline = this.incoming.indexOf(10)
      if (newline < 0) {
        if (this.incoming.length > 256) throw new Error('电脑安全握手无效')
        return
      }
      let lineBytes = this.incoming.slice(0, newline)
      this.incoming = this.incoming.slice(newline + 1)
      if (lineBytes.length && lineBytes[lineBytes.length - 1] === 13) lineBytes = lineBytes.slice(0, -1)
      const line = asciiDecode(lineBytes)
      const parts = line.split(' ').filter(Boolean)

      if (this.state === 'waitChallenge') {
        if (parts.length !== 4 || parts[0] !== 'HTMP1' || parts[1] !== 'CHALLENGE') throw new Error('电脑不支持微信小程序安全协议')
        const serverNonce = base64UrlDecode(parts[2])
        const serverProof = base64UrlDecode(parts[3])
        if (serverNonce.length !== 32 || serverProof.length !== 32) throw new Error('电脑安全握手无效')
        const expected = pairingProof(this.masterKey, 'server', this.clientNonce, serverNonce)
        if (!constantTimeEqual(serverProof, expected)) throw new Error('电脑身份验证失败，请重新扫码配对')
        this.serverNonce = serverNonce
        const clientProof = pairingProof(this.masterKey, 'client', this.clientNonce, serverNonce)
        this.socket.write(`HTMP1 AUTH ${base64UrlEncode(clientProof)}\n`)
        this.state = 'waitOk'
        continue
      }

      if (parts.length === 2 && parts[0] === 'HTMP1' && parts[1] === 'BUSY') throw new Error('这台电脑已有其他控制器连接')
      if (parts.length !== 2 || parts[0] !== 'HTMP1' || parts[1] !== 'OK') throw new Error('电脑安全握手失败')
      const keys = deriveSessionKeys(this.masterKey, this.clientNonce, this.serverNonce)
      this.sendEnc = keys.clientEnc
      this.sendMac = keys.clientMac
      this.receiveEnc = keys.serverEnc
      this.receiveMac = keys.serverMac
      this.sendSequence = 0
      this.receiveSequence = 0
      this.state = 'open'
      this.clearHandshakeTimer()
      const resolve = this.connectResolve
      this.connectResolve = null
      this.connectReject = null
      if (resolve) resolve()
    }
  }

  consumeFrames() {
    while (this.incoming.length >= 4) {
      const length = readU32(this.incoming, 0)
      const minimum = 1 + 4 + IV_BYTES + 16 + TAG_BYTES
      const maximum = 1 + 4 + IV_BYTES + MAX_CIPHER + TAG_BYTES
      if (length < minimum || length > maximum) throw new Error('电脑返回的加密帧长度无效')
      if (this.incoming.length < 4 + length) return
      const body = this.incoming.slice(4, 4 + length)
      this.incoming = this.incoming.slice(4 + length)
      if (body[0] !== 1) throw new Error('电脑安全协议版本不兼容')
      const sequence = readU32(body, 1)
      if (sequence !== this.receiveSequence || this.receiveSequence >= 0xffffffff) throw new Error('电脑返回的加密帧序号无效')
      const authenticatedLength = body.length - TAG_BYTES
      const authenticated = body.slice(0, authenticatedLength)
      const tag = body.slice(authenticatedLength)
      const expected = hmacSha256(this.receiveMac, authenticated)
      if (!constantTimeEqual(tag, expected)) throw new Error('电脑返回的数据认证失败')
      const iv = body.slice(5, 5 + IV_BYTES)
      const ciphertext = body.slice(5 + IV_BYTES, authenticatedLength)
      const plaintext = aesCbcDecrypt(this.receiveEnc, iv, ciphertext)
      if (plaintext.length > MAX_PLAINTEXT) throw new Error('电脑返回的数据超过 64 KiB')
      this.receiveSequence += 1
      const text = utf8Decode(plaintext)
      if (this.listener && typeof this.listener.onText === 'function') this.listener.onText(text)
    }
  }

  fail(message) {
    if (this.state === 'closed') return
    this.clearHandshakeTimer()
    const reject = this.connectReject
    this.connectResolve = null
    this.connectReject = null
    const wasOpen = this.state === 'open'
    this.intentionalClose = true
    const socket = this.socket
    this.socket = null
    this.state = 'closed'
    if (socket) {
      try { socket.close() } catch (_) { }
    }
    if (reject) reject(new Error(message))
    if (wasOpen && this.listener && typeof this.listener.onClosed === 'function') this.listener.onClosed(message)
  }

  clearHandshakeTimer() {
    if (this.handshakeTimer) clearTimeout(this.handshakeTimer)
    this.handshakeTimer = null
  }
}

module.exports = { SecureTcp }
