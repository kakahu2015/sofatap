const { SecureTcp } = require('./secure-tcp')
const { utf8Length } = require('./pairing')

const MAX_TEXT_UNITS = 16000
const MAX_WIRE_BYTES = 65536
const COMMAND_TIMEOUT_MS = 8000
const MOVE_STALE_MS = 120

let uuidCounter = 0
function randomUuid() {
  const now = Date.now()
  const words = [
    Math.floor(now / 0x100000000) >>> 0,
    now >>> 0,
    (uuidCounter++ >>> 0),
    Math.floor(Math.random() * 0x100000000) >>> 0
  ]
  let hex = words.map((value) => value.toString(16).padStart(8, '0')).join('').slice(0, 32).split('')
  hex[12] = '4'
  hex[16] = ((parseInt(hex[16], 16) & 3) | 8).toString(16)
  hex = hex.join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function bound(value, minimum, maximum) { return Math.max(minimum, Math.min(maximum, value)) }

function rejectedWithoutDelivery(error) {
  return error.startsWith('input_failed:') || error.startsWith('input_busy:') ||
    error.startsWith('invalid_') || error.startsWith('unsupported_') ||
    error.startsWith('live_text_unsupported:') || error.startsWith('live_text_conflict:') ||
    error.startsWith('live_text_changed:')
}

class HostTapClient {
  constructor() {
    this.listener = null
    this.transport = null
    this.pairing = null
    this.pending = new Map()
    this.generation = 0
    this.pingTimer = null
    this.reconnectTimer = null
    this.retryDelayMs = 1000
    this.foreground = true
    this.autoReconnectEnabled = true
    this.reconnectWanted = false

    this.connecting = false
    this.connected = false
    this.inputAvailable = false
    this.protectedFocus = false
    this.computerName = ''
    this.status = '未连接电脑'

    this.queuedDX = 0
    this.queuedDY = 0
    this.queuedScroll = 0
    this.lastMoveAt = 0
    this.moveBusy = false
    this.scrollBusy = false
    this.moveTimer = null
    this.scrollTimer = null
  }

  setListener(listener) { this.listener = listener; this.publish() }
  isConnected() { return this.connected }
  canControl() { return this.connected && this.inputAvailable }

  state() {
    return {
      status: this.status,
      connecting: this.connecting,
      connected: this.connected,
      inputAvailable: this.inputAvailable,
      protectedFocus: this.protectedFocus,
      computerName: this.computerName,
      autoReconnectEnabled: this.autoReconnectEnabled
    }
  }

  publish() {
    if (typeof this.listener === 'function') this.listener(this.state())
  }

  async connect(target) {
    this.pairing = target
    this.reconnectWanted = true
    this.retryDelayMs = 1000
    this.clearReconnectTimer()
    await this.connectInternal(target)
  }

  async disconnect() {
    this.reconnectWanted = false
    this.clearReconnectTimer()
    this.closeTransport()
    this.status = '未连接电脑'
    this.publish()
  }

  setAutoReconnect(enabled) {
    this.autoReconnectEnabled = !!enabled
    if (!enabled) {
      this.reconnectWanted = false
      this.clearReconnectTimer()
    } else if (this.pairing) {
      this.reconnectWanted = true
      if (this.foreground && !this.connected && !this.connecting) this.scheduleReconnect(0)
    }
    this.publish()
  }

  setForeground(active) {
    this.foreground = !!active
    if (!active) {
      this.clearReconnectTimer()
      if (this.connected || this.connecting) {
        this.closeTransport()
        this.status = '小程序进入后台，连接已暂停'
        this.publish()
      }
      return
    }
    if (this.reconnectWanted && this.autoReconnectEnabled && this.pairing && !this.connected && !this.connecting) {
      this.scheduleReconnect(0)
    }
  }

  async connectInternal(target) {
    this.closeTransport()
    const current = ++this.generation
    this.pairing = target
    this.computerName = target.displayName()
    this.connecting = true
    this.connected = false
    this.inputAvailable = false
    this.protectedFocus = false
    this.status = '正在验证电脑身份…'
    this.publish()

    let helloResolve
    let helloReject
    const hello = new Promise((resolve, reject) => { helloResolve = resolve; helloReject = reject })
    const transport = new SecureTcp()
    this.transport = transport

    try {
      await transport.connect(target, {
        onText: (message) => {
          if (current === this.generation) this.receive(message, current, helloResolve, helloReject)
        },
        onClosed: (message) => {
          if (current !== this.generation) return
          if (helloReject) helloReject(new Error(message))
          this.connectionLost(message, current)
        }
      })
      if (current !== this.generation) return
      this.status = '安全连接已建立，正在读取电脑状态…'
      this.publish()
      await Promise.race([
        hello,
        new Promise((_, reject) => setTimeout(() => reject(new Error('电脑没有返回有效状态，请更新电脑端接收器')), 10000))
      ])
    } catch (error) {
      if (current !== this.generation) return
      const message = error instanceof Error ? error.message : '无法连接电脑'
      this.closeTransport()
      this.status = message
      this.publish()
      if (this.shouldRetry(message)) this.scheduleReconnect()
      throw new Error(message)
    }
  }

  receive(raw, current, helloResolve, helloReject) {
    if (current !== this.generation || utf8Length(raw) > MAX_WIRE_BYTES) {
      if (helloReject) helloReject(new Error('电脑返回的数据无效'))
      this.connectionLost('电脑返回的数据无效，已断开连接', current)
      return
    }
    let message
    try { message = JSON.parse(raw) }
    catch (_) {
      if (helloReject) helloReject(new Error('电脑返回的数据不是有效 JSON'))
      this.connectionLost('电脑返回的数据无效，已断开连接', current)
      return
    }

    if (message.type === 'hello') {
      if (!this.connecting || message.version !== 1 || typeof message.name !== 'string' || typeof message.inputAvailable !== 'boolean') {
        if (helloReject) helloReject(new Error('电脑端协议不兼容，请更新接收器'))
        this.connectionLost('电脑端协议不兼容，请更新接收器', current)
        return
      }
      const status = typeof message.inputStatus === 'string' ? message.inputStatus : ''
      const dryRun = message.dryRun === true
      this.inputAvailable = message.inputAvailable === true && !dryRun && (!status || status === 'ready')
      this.protectedFocus = !!(message.textFocus && message.textFocus.state === 'protected')
      this.computerName = message.name || (this.pairing ? this.pairing.displayName() : '电脑')
      this.connecting = false
      this.connected = true
      this.retryDelayMs = 1000
      this.status = this.inputAvailable ? '已连接' : this.statusMessage(status, dryRun)
      this.publish()
      this.startKeepAlive()
      if (helloResolve) helloResolve()
      return
    }

    if (message.type !== 'ack' || typeof message.id !== 'string') {
      this.connectionLost('电脑端协议不兼容，请更新接收器', current)
      return
    }
    const item = this.pending.get(message.id)
    if (!item) return
    this.pending.delete(message.id)
    clearTimeout(item.timer)

    if (item.type === 'ping') {
      if (typeof message.inputAvailable === 'boolean') {
        const status = typeof message.inputStatus === 'string' ? message.inputStatus : ''
        const dryRun = message.dryRun === true
        this.inputAvailable = message.inputAvailable && !dryRun && (!status || status === 'ready')
        this.status = this.inputAvailable ? '已连接' : this.statusMessage(status, dryRun)
      }
      if (message.textFocus) this.protectedFocus = message.textFocus.state === 'protected'
      this.publish()
    }

    if (message.ok === true) {
      item.resolve({ accepted: true, uncertain: false, error: '' })
      return
    }
    const error = typeof message.error === 'string' && message.error ? message.error : '操作未完成'
    const uncertain = (item.type === 'text') && !rejectedWithoutDelivery(error)
    item.resolve({ accepted: false, uncertain, error })
  }

  click(button) {
    if (button !== 'left' && button !== 'right') return
    void this.sendActionFrame({ id: randomUuid(), type: 'click', button }, false)
  }

  button(button, down) {
    if (button !== 'left' && button !== 'right') return
    void this.sendActionFrame({ id: randomUuid(), type: 'button', button, down: !!down }, false)
  }

  key(key, modifiers = []) {
    void this.sendActionFrame({ id: randomUuid(), type: 'key', key, modifiers }, false)
  }

  move(dx, dy) {
    if (!this.canControl() || !Number.isFinite(dx) || !Number.isFinite(dy)) return
    const now = Date.now()
    if (now - this.lastMoveAt > MOVE_STALE_MS) { this.queuedDX = 0; this.queuedDY = 0 }
    this.queuedDX = bound(this.queuedDX + dx * 1.4, -500, 500)
    this.queuedDY = bound(this.queuedDY + dy * 1.4, -500, 500)
    this.lastMoveAt = now
    this.scheduleMove(30)
  }

  scroll(dy) {
    if (!this.canControl() || !Number.isFinite(dy)) return
    this.queuedScroll = bound(this.queuedScroll + dy, -1200, 1200)
    this.scheduleScroll(25)
  }

  async sendText(value) {
    if (!this.canControl()) return { accepted: false, uncertain: false, error: '电脑当前无法接收输入' }
    if (!value) return { accepted: false, uncertain: false, error: '先输入要发送的文字' }
    if (value.length > MAX_TEXT_UNITS) return { accepted: false, uncertain: false, error: '文字超过 16,000 个 UTF-16 单元，请分段发送' }
    if (this.protectedFocus) return { accepted: false, uncertain: false, error: '电脑当前是安全输入框，已阻止发送' }
    return await this.sendActionFrame({ id: randomUuid(), type: 'text', text: value }, true)
  }

  async sendActionFrame(frame, textSensitive) {
    const transport = this.transport
    if (!this.connected || !transport || !transport.isOpen()) return { accepted: false, uncertain: false, error: '尚未连接电脑' }
    if (frame.type !== 'ping' && !this.inputAvailable) return { accepted: false, uncertain: false, error: '电脑当前无法接收输入' }
    if (this.pending.size >= 32) return { accepted: false, uncertain: false, error: '操作过快，请稍后' }
    const wire = JSON.stringify(frame)
    if (utf8Length(wire) > MAX_WIRE_BYTES) return { accepted: false, uncertain: false, error: '消息超过 64 KiB，请拆分发送' }

    return await new Promise((resolve) => {
      const timer = setTimeout(() => {
        const item = this.pending.get(frame.id)
        if (!item) return
        this.pending.delete(frame.id)
        item.resolve({
          accepted: false,
          uncertain: textSensitive,
          error: textSensitive ? '电脑响应超时，文字可能已输入，请先查看电脑屏幕' : '电脑响应超时'
        })
        this.connectionLost('电脑响应超时，已停止控制', this.generation)
      }, COMMAND_TIMEOUT_MS)
      this.pending.set(frame.id, { type: frame.type, timer, resolve })
      transport.sendText(wire).catch(() => {
        const item = this.pending.get(frame.id)
        if (!item) return
        this.pending.delete(frame.id)
        clearTimeout(item.timer)
        item.resolve({ accepted: false, uncertain: textSensitive, error: textSensitive ? '连接中断，文字发送结果未确认' : '连接中断' })
        this.connectionLost('电脑连接已中断', this.generation)
      })
    })
  }

  scheduleMove(delay) {
    if (this.moveTimer) return
    this.moveTimer = setTimeout(() => { this.moveTimer = null; void this.flushMove() }, delay)
  }

  async flushMove() {
    if (!this.canControl()) { this.queuedDX = 0; this.queuedDY = 0; return }
    if (Date.now() - this.lastMoveAt > MOVE_STALE_MS) { this.queuedDX = 0; this.queuedDY = 0; return }
    if (this.moveBusy || Math.abs(this.queuedDX) + Math.abs(this.queuedDY) < 0.01) return
    const dx = this.queuedDX; const dy = this.queuedDY
    this.queuedDX = 0; this.queuedDY = 0; this.moveBusy = true
    await this.sendActionFrame({ id: randomUuid(), type: 'move', dx, dy }, false)
    this.moveBusy = false
    if (Math.abs(this.queuedDX) + Math.abs(this.queuedDY) > 0.01) this.scheduleMove(20)
  }

  scheduleScroll(delay) {
    if (this.scrollTimer || this.scrollBusy) return
    this.scrollTimer = setTimeout(() => { this.scrollTimer = null; void this.flushScroll() }, delay)
  }

  async flushScroll() {
    if (!this.canControl()) { this.queuedScroll = 0; return }
    if (this.scrollBusy || Math.abs(this.queuedScroll) < 0.01) return
    const dy = this.queuedScroll
    this.queuedScroll = 0; this.scrollBusy = true
    await this.sendActionFrame({ id: randomUuid(), type: 'scroll', dx: 0, dy }, false)
    this.scrollBusy = false
    if (Math.abs(this.queuedScroll) > 0.01) this.scheduleScroll(20)
  }

  startKeepAlive() {
    this.stopKeepAlive()
    this.pingTimer = setInterval(() => {
      if (!this.connected) return
      void this.sendActionFrame({ id: randomUuid(), type: 'ping' }, false)
    }, 5000)
  }

  stopKeepAlive() {
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  closeTransport() {
    this.generation += 1
    this.stopKeepAlive()
    if (this.moveTimer) clearTimeout(this.moveTimer)
    if (this.scrollTimer) clearTimeout(this.scrollTimer)
    this.moveTimer = null; this.scrollTimer = null
    this.queuedDX = 0; this.queuedDY = 0; this.queuedScroll = 0
    const transport = this.transport
    this.transport = null
    if (transport) transport.close()
    for (const item of this.pending.values()) {
      clearTimeout(item.timer)
      item.resolve({ accepted: false, uncertain: item.type === 'text', error: item.type === 'text' ? '连接中断，文字发送结果未确认' : '连接中断' })
    }
    this.pending.clear()
    this.connecting = false
    this.connected = false
    this.inputAvailable = false
    this.protectedFocus = false
  }

  connectionLost(message, current) {
    if (current !== this.generation) return
    this.closeTransport()
    this.status = message || '电脑连接已中断'
    this.publish()
    if (this.reconnectWanted && this.autoReconnectEnabled) this.scheduleReconnect()
  }

  scheduleReconnect(delay) {
    if (!this.foreground || !this.reconnectWanted || !this.autoReconnectEnabled || !this.pairing || this.reconnectTimer) return
    const wait = typeof delay === 'number' ? delay : this.retryDelayMs
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      if (!this.foreground || !this.reconnectWanted || !this.pairing || this.connected || this.connecting) return
      this.connectInternal(this.pairing).catch(() => {})
    }, wait)
    if (typeof delay !== 'number') this.retryDelayMs = Math.min(this.retryDelayMs * 2, 15000)
  }

  clearReconnectTimer() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
  }

  shouldRetry(message) {
    return !/身份验证失败|配对密钥无效|协议不兼容|重新扫码/.test(message)
  }

  statusMessage(status, dryRun) {
    if (dryRun) return '电脑端处于测试模式，未启用输入'
    if (status === 'permission_required') return '电脑端需要输入控制权限'
    if (status === 'unavailable') return '电脑端输入控制暂不可用'
    return '已连接，但电脑当前无法接收输入'
  }
}

const hostTapClient = new HostTapClient()
module.exports = { HostTapClient, hostTapClient }
