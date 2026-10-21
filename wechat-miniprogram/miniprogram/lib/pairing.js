const MAX_LINK_BYTES = 2048
const MAX_NAME_BYTES = 256
const MAX_NAME_SCALARS = 80

function utf8Length(value) {
  let bytes = 0
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code <= 0x7f) bytes += 1
    else if (code <= 0x7ff) bytes += 2
    else if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length) {
      const next = value.charCodeAt(i + 1)
      if (next >= 0xdc00 && next <= 0xdfff) {
        bytes += 4
        i += 1
      } else bytes += 3
    } else bytes += 3
  }
  return bytes
}

function scalarCount(value) {
  let count = 0
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i)
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < value.length) {
      const next = value.charCodeAt(i + 1)
      if (next >= 0xdc00 && next <= 0xdfff) i += 1
    }
    count += 1
  }
  return count
}

function containsUnsafeNameCodePoint(value) {
  for (let i = 0; i < value.length; i++) {
    const unit = value.charCodeAt(i)
    if (unit <= 0x001f || (unit >= 0x007f && unit <= 0x009f) ||
      unit === 0x00ad || unit === 0x061c || unit === 0x070f || unit === 0x180e ||
      (unit >= 0x200b && unit <= 0x200f) || (unit >= 0x202a && unit <= 0x202e) ||
      (unit >= 0x2060 && unit <= 0x206f) || unit === 0xfeff) return true
  }
  return false
}

function decodeQueryPart(value) {
  try { return decodeURIComponent(value) }
  catch (_) { throw new Error('配对链接包含无效转义') }
}

function validIpv4(value) {
  const parts = value.split('.')
  if (parts.length !== 4) return false
  return parts.every((part) => {
    if (!/^[0-9]{1,3}$/.test(part)) return false
    if (part.length > 1 && part[0] === '0') return false
    const number = Number(part)
    return Number.isInteger(number) && number >= 0 && number <= 255
  })
}

function allowedField(key) {
  return key === 'host' || key === 'port' || key === 'token' || key === 'fingerprint' ||
    key === 'platform' || key === 'name'
}

class HostTapPairing {
  constructor(host, port, token, fingerprint, platform, name, link) {
    this.host = host
    this.port = port
    this.token = token
    this.fingerprint = fingerprint
    this.platform = platform
    this.name = name
    this.link = link
  }

  static parse(raw) {
    const trimmed = String(raw || '').trim()
    if (!trimmed || utf8Length(trimmed) > MAX_LINK_BYTES) throw new Error('配对链接无效')
    const prefix = 'sofatap://pair?'
    if (!trimmed.toLowerCase().startsWith(prefix) || trimmed.includes('#')) {
      throw new Error('请扫描电脑接收器显示的配对二维码')
    }
    const query = trimmed.slice(prefix.length)
    if (!query || query.includes('/')) throw new Error('配对链接无效')
    const parts = query.split('&')
    if (parts.length < 4 || parts.length > 6) throw new Error('配对字段不完整')

    const fields = Object.create(null)
    for (const part of parts) {
      const equals = part.indexOf('=')
      if (equals <= 0) throw new Error('配对字段无效')
      const key = decodeQueryPart(part.slice(0, equals))
      const value = decodeQueryPart(part.slice(equals + 1))
      if (!allowedField(key) || Object.prototype.hasOwnProperty.call(fields, key)) {
        throw new Error('配对字段重复或未知')
      }
      fields[key] = value
    }

    const host = fields.host || ''
    if (!validIpv4(host)) throw new Error('微信小程序当前仅接受电脑二维码中的 IPv4 地址')
    const portText = fields.port || ''
    if (!/^[0-9]{1,5}$/.test(portText)) throw new Error('配对地址或端口无效')
    const port = Number(portText)
    const token = fields.token || ''
    const fingerprint = fields.fingerprint || ''
    if (!Number.isInteger(port) || port < 1 || port > 65535 || token.length < 32 || token.length > 256 ||
      !/^[A-Za-z0-9_-]+$/.test(token) || !/^[0-9A-Fa-f]{64}$/.test(fingerprint)) {
      throw new Error('配对凭据无效')
    }

    const platform = fields.platform || ''
    if (platform && !['windows', 'macos', 'linux'].includes(platform)) throw new Error('电脑平台字段无效')

    let name = fields.name || ''
    if (name) {
      name = name.trim()
      if (!name || containsUnsafeNameCodePoint(name) || scalarCount(name) > MAX_NAME_SCALARS || utf8Length(name) > MAX_NAME_BYTES) {
        throw new Error('电脑名称字段无效')
      }
    }
    return new HostTapPairing(host, port, token, fingerprint.toLowerCase(), platform, name, trimmed)
  }

  static fromFields(host, port, token, fingerprint, platform, name) {
    const fields = [
      `host=${encodeURIComponent(host)}`,
      `port=${port}`,
      `token=${encodeURIComponent(token)}`,
      `fingerprint=${encodeURIComponent(fingerprint)}`
    ]
    if (platform) fields.push(`platform=${encodeURIComponent(platform)}`)
    if (name) fields.push(`name=${encodeURIComponent(name)}`)
    return HostTapPairing.parse(`sofatap://pair?${fields.join('&')}`)
  }

  displayName() { return this.name || this.host }
  endpointLabel() { return `${this.host}:${this.port}` }
  miniProgramPort() { return this.port < 65535 ? this.port + 1 : 53320 }
}

module.exports = { HostTapPairing, utf8Length }
