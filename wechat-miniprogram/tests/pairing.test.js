const test = require('node:test')
const assert = require('node:assert/strict')
const { HostTapPairing, utf8Length } = require('../miniprogram/lib/pairing')

const TOKEN = 'A'.repeat(43)
const FINGERPRINT = 'B'.repeat(64)
const link = (query) => `sofatap://pair?${query}`
const valid = `host=192.168.1.20&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}&platform=windows&name=${encodeURIComponent('客厅电脑')}`

test('parses a receiver pairing link', () => {
  const pairing = HostTapPairing.parse(link(valid))
  assert.equal(pairing.host, '192.168.1.20')
  assert.equal(pairing.port, 53319)
  assert.equal(pairing.token, TOKEN)
  assert.equal(pairing.fingerprint, FINGERPRINT.toLowerCase())
  assert.equal(pairing.platform, 'windows')
  assert.equal(pairing.name, '客厅电脑')
  assert.equal(pairing.displayName(), '客厅电脑')
  assert.equal(pairing.endpointLabel(), '192.168.1.20:53319')
  assert.equal(pairing.miniProgramPort(), 53320)
})

test('falls back to the host when the QR carries no name', () => {
  const pairing = HostTapPairing.parse(link(`host=10.0.0.7&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}`))
  assert.equal(pairing.name, '')
  assert.equal(pairing.platform, '')
  assert.equal(pairing.displayName(), '10.0.0.7')
})

test('maps port 65535 to the fixed mini program port', () => {
  const pairing = HostTapPairing.parse(link(`host=192.168.0.2&port=65535&token=${TOKEN}&fingerprint=${FINGERPRINT}`))
  assert.equal(pairing.miniProgramPort(), 53320)
})

test('round-trips fields, including non-ASCII names', () => {
  const pairing = HostTapPairing.fromFields('172.16.5.9', 53319, TOKEN, FINGERPRINT, 'macos', '书房的 Mac')
  assert.equal(pairing.host, '172.16.5.9')
  assert.equal(pairing.name, '书房的 Mac')
  assert.equal(pairing.platform, 'macos')
})

test('rejects links that are not HostTap pairing codes', () => {
  assert.throws(() => HostTapPairing.parse(''), /配对链接无效/)
  assert.throws(() => HostTapPairing.parse('https://example.com'), /请扫描电脑接收器/)
  assert.throws(() => HostTapPairing.parse(link(valid) + '#x'), /请扫描电脑接收器/)
  assert.throws(() => HostTapPairing.parse(link(valid + '&')), /配对字段/)
  assert.throws(() => HostTapPairing.parse(link(valid) + '/'), /配对链接无效/)
})

test('rejects incomplete, duplicated or unknown fields', () => {
  const base = `host=192.168.1.20&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}`
  assert.throws(() => HostTapPairing.parse(link(`host=192.168.1.20&port=53319&token=${TOKEN}`)), /配对字段不完整/)
  assert.throws(() => HostTapPairing.parse(link(`${base}&platform=windows&platform=macos`)), /配对字段重复或未知/)
  assert.throws(() => HostTapPairing.parse(link(`${base}&platform=windows&extra=1`)), /配对字段重复或未知/)
})

test('accepts only IPv4 hosts', () => {
  const withHost = (host) => link(`host=${host}&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}`)
  assert.throws(() => HostTapPairing.parse(withHost('fe80::1')), /IPv4/)
  assert.throws(() => HostTapPairing.parse(withHost('192.168.1')), /IPv4/)
  assert.throws(() => HostTapPairing.parse(withHost('192.168.01.2')), /IPv4/)
  assert.throws(() => HostTapPairing.parse(withHost('192.168.1.256')), /IPv4/)
  assert.throws(() => HostTapPairing.parse(withHost('example.com')), /IPv4/)
})

test('rejects out-of-range ports and malformed credentials', () => {
  const linkWith = (port, token, fingerprint) => link(`host=192.168.1.20&port=${port}&token=${token}&fingerprint=${fingerprint}`)
  assert.throws(() => HostTapPairing.parse(linkWith('0', TOKEN, FINGERPRINT)), /配对凭据无效/)
  assert.throws(() => HostTapPairing.parse(linkWith('70000', TOKEN, FINGERPRINT)), /配对凭据无效/)
  assert.throws(() => HostTapPairing.parse(linkWith('53319', 'short', FINGERPRINT)), /配对凭据无效/)
  assert.throws(() => HostTapPairing.parse(linkWith('53319', TOKEN, 'ff')), /配对凭据无效/)
  assert.throws(() => HostTapPairing.parse(linkWith('53319', 'A'.repeat(40) + '!', FINGERPRINT)), /配对凭据无效/)
})

test('rejects unknown platforms and unsafe device names', () => {
  const base = `host=192.168.1.20&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}`
  assert.throws(() => HostTapPairing.parse(link(`${base}&platform=android`)), /平台字段无效/)
  assert.throws(() => HostTapPairing.parse(link(`${base}&name=${encodeURIComponent('客厅\u0007电脑')}`)), /电脑名称字段无效/)
  assert.throws(() => HostTapPairing.parse(link(`${base}&name=${'x'.repeat(81)}`)), /电脑名称字段无效/)
})

test('rejects oversized links', () => {
  const huge = link(`host=192.168.1.20&port=53319&token=${TOKEN}&fingerprint=${FINGERPRINT}&name=${'x'.repeat(2100)}`)
  assert.throws(() => HostTapPairing.parse(huge), /配对链接无效/)
})

test('counts UTF-8 bytes the way the receiver does', () => {
  assert.equal(utf8Length('abc'), 3)
  assert.equal(utf8Length('客厅电脑'), 12)
  assert.equal(utf8Length('😀'), 4)
  assert.equal(utf8Length('é'), 2)
})
