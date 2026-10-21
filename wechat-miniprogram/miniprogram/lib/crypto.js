const CryptoJS = require('crypto-js')

function bytesToWordArray(bytes) {
  const words = []
  for (let i = 0; i < bytes.length; i++) words[i >>> 2] = (words[i >>> 2] || 0) | (bytes[i] << (24 - (i % 4) * 8))
  return CryptoJS.lib.WordArray.create(words, bytes.length)
}

function wordArrayToBytes(wordArray) {
  const size = Math.max(0, wordArray.sigBytes)
  const result = new Uint8Array(size)
  for (let i = 0; i < size; i++) result[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff
  return result
}

function concatBytes(...parts) {
  const length = parts.reduce((sum, part) => sum + part.length, 0)
  const out = new Uint8Array(length)
  let offset = 0
  parts.forEach((part) => { out.set(part, offset); offset += part.length })
  return out
}

function asciiBytes(value) {
  const out = new Uint8Array(value.length)
  for (let i = 0; i < value.length; i++) out[i] = value.charCodeAt(i) & 0x7f
  return out
}

function utf8Encode(value) { return wordArrayToBytes(CryptoJS.enc.Utf8.parse(value)) }
function utf8Decode(bytes) { return CryptoJS.enc.Utf8.stringify(bytesToWordArray(bytes)) }

function bufferOf(bytes) {
  const copy = new Uint8Array(bytes.length)
  copy.set(bytes)
  return copy.buffer
}

function base64UrlEncode(bytes) {
  return wx.arrayBufferToBase64(bufferOf(bytes)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
}

function base64UrlDecode(value) {
  if (!value || !/^[A-Za-z0-9_-]+$/.test(value)) return new Uint8Array(0)
  let padded = value.replace(/-/g, '+').replace(/_/g, '/')
  while (padded.length % 4) padded += '='
  try { return new Uint8Array(wx.base64ToArrayBuffer(padded)) }
  catch (_) { return new Uint8Array(0) }
}

function randomBytes(length) {
  return new Promise((resolve, reject) => {
    wx.getRandomValues({
      length,
      success: (result) => resolve(new Uint8Array(result.randomValues)),
      fail: () => reject(new Error('无法获取安全随机数'))
    })
  })
}

function hmacSha256(key, message) {
  return wordArrayToBytes(CryptoJS.HmacSHA256(bytesToWordArray(message), bytesToWordArray(key)))
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false
  let diff = 0
  for (let i = 0; i < left.length; i++) diff |= left[i] ^ right[i]
  return diff === 0
}

function hkdfSha256(ikm, salt, info, length) {
  const prk = hmacSha256(salt, ikm)
  let previous = new Uint8Array(0)
  const blocks = []
  let produced = 0
  let counter = 1
  while (produced < length) {
    previous = hmacSha256(prk, concatBytes(previous, info, new Uint8Array([counter++])))
    blocks.push(previous)
    produced += previous.length
  }
  return concatBytes(...blocks).slice(0, length)
}

function aesCbcEncrypt(key, iv, plaintext) {
  const encrypted = CryptoJS.AES.encrypt(bytesToWordArray(plaintext), bytesToWordArray(key), {
    iv: bytesToWordArray(iv),
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.Pkcs7
  })
  return wordArrayToBytes(encrypted.ciphertext)
}

function aesCbcDecrypt(key, iv, ciphertext) {
  const params = CryptoJS.lib.CipherParams.create({ ciphertext: bytesToWordArray(ciphertext) })
  const decrypted = CryptoJS.AES.decrypt(params, bytesToWordArray(key), {
    iv: bytesToWordArray(iv),
    mode: CryptoJS.mode.CBC,
    padding: CryptoJS.pad.Pkcs7
  })
  if (decrypted.sigBytes < 0) throw new Error('加密帧填充无效')
  return wordArrayToBytes(decrypted)
}

function pairingProof(masterKey, role, clientNonce, serverNonce) {
  return hmacSha256(masterKey, concatBytes(
    asciiBytes('HostTap MiniProgram v1\0'),
    asciiBytes(`${role}\0`),
    clientNonce,
    serverNonce
  ))
}

function deriveSessionKeys(masterKey, clientNonce, serverNonce) {
  const material = hkdfSha256(masterKey, concatBytes(clientNonce, serverNonce), asciiBytes('HostTap MiniProgram v1 session'), 128)
  return {
    clientEnc: material.slice(0, 32),
    clientMac: material.slice(32, 64),
    serverEnc: material.slice(64, 96),
    serverMac: material.slice(96, 128)
  }
}

module.exports = {
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
}
