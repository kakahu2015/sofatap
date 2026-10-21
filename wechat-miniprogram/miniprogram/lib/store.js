const CryptoJS = require('crypto-js')
const { HostTapPairing } = require('./pairing')

const STORAGE_KEY = 'hosttap_saved_computers_v1'
const MAX_SAVED = 20

function identity(pairing) {
  return CryptoJS.SHA256(`${pairing.host.toLowerCase()}|${pairing.port}|${pairing.fingerprint}`).toString(CryptoJS.enc.Hex)
}

function list() {
  let raw
  try { raw = wx.getStorageSync(STORAGE_KEY) }
  catch (_) { return [] }
  if (!Array.isArray(raw)) return []
  const valid = []
  for (const item of raw) {
    try {
      if (!item || typeof item.id !== 'string' || !/^[0-9a-f]{64}$/.test(item.id)) continue
      const pairing = HostTapPairing.fromFields(item.host, item.port, item.token, item.fingerprint, item.platform || '', item.name || '')
      valid.push({
        id: item.id,
        host: pairing.host,
        port: pairing.port,
        token: pairing.token,
        fingerprint: pairing.fingerprint,
        platform: pairing.platform,
        name: pairing.displayName()
      })
      if (valid.length >= MAX_SAVED) break
    } catch (_) { }
  }
  return valid
}

function write(items) {
  wx.setStorageSync(STORAGE_KEY, items.slice(0, MAX_SAVED))
}

function save(pairing) {
  const id = identity(pairing)
  const current = list()
  const exists = current.some((item) => item.id === id)
  if (!exists && current.length >= MAX_SAVED) throw new Error(`最多保存 ${MAX_SAVED} 台电脑，请先删除一个旧设备`)
  const saved = {
    id,
    host: pairing.host,
    port: pairing.port,
    token: pairing.token,
    fingerprint: pairing.fingerprint,
    platform: pairing.platform,
    name: pairing.displayName()
  }
  const next = [saved].concat(current.filter((item) => item.id !== id))
  write(next)
  return next
}

function remove(id) {
  if (!/^[0-9a-f]{64}$/.test(String(id || ''))) throw new Error('保存设备标识无效')
  const next = list().filter((item) => item.id !== id)
  write(next)
  return next
}

function resolve(saved) {
  return HostTapPairing.fromFields(saved.host, saved.port, saved.token, saved.fingerprint, saved.platform || '', saved.name || '')
}

module.exports = { list, remove, resolve, save }
