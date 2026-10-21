const { HostTapPairing } = require('../../lib/pairing')
const store = require('../../lib/store')
const { hostTapClient } = require('../../lib/client')

Page({
  data: {
    status: '未连接电脑',
    computerName: '',
    connecting: false,
    connected: false,
    inputAvailable: false,
    protectedFocus: false,
    scanning: false,
    pairingReady: false,
    pairingName: '',
    pairingEndpoint: '',
    draft: '',
    operationMessage: '',
    autoReconnectEnabled: true,
    savedComputers: []
  },

  onLoad() {
    this.currentPairing = null
    this.touchMode = ''
    this.lastX = 0
    this.lastY = 0
    this.lastScrollY = 0
    this.touchStartedAt = 0
    this.totalMove = 0
    hostTapClient.setListener((state) => {
      this.setData({
        status: state.status,
        computerName: state.computerName,
        connecting: state.connecting,
        connected: state.connected,
        inputAvailable: state.inputAvailable,
        protectedFocus: state.protectedFocus,
        autoReconnectEnabled: state.autoReconnectEnabled
      })
    })
    this.refreshSavedComputers()
  },

  onShow() {
    hostTapClient.setForeground(true)
  },

  onHide() {
    hostTapClient.setForeground(false)
  },

  onUnload() {
    hostTapClient.setListener(null)
  },

  refreshSavedComputers() {
    try {
      this.setData({ savedComputers: store.list() })
    } catch (_) {
      this.setData({ operationMessage: '无法读取已保存的电脑' })
    }
  },

  async scanPairingQr() {
    if (this.data.scanning || this.data.connected || this.data.connecting) return
    this.setData({ scanning: true, operationMessage: '' })
    try {
      const result = await new Promise((resolve, reject) => {
        wx.scanCode({
          onlyFromCamera: true,
          scanType: ['qrCode'],
          success: resolve,
          fail: reject
        })
      })
      if (!result || typeof result.result !== 'string' || !result.result) throw new Error('二维码中没有配对链接')
      const pairing = HostTapPairing.parse(result.result)
      this.currentPairing = pairing
      this.setData({
        pairingReady: true,
        pairingName: pairing.displayName(),
        pairingEndpoint: pairing.endpointLabel(),
        operationMessage: '已读取配对二维码，请连接或保存电脑'
      })
    } catch (error) {
      const message = error && error.errMsg && /cancel/i.test(error.errMsg)
        ? '' : (error instanceof Error ? error.message : '二维码扫描失败')
      if (message) this.setData({ operationMessage: message })
    } finally {
      this.setData({ scanning: false })
    }
  },

  async toggleConnection() {
    this.setData({ operationMessage: '' })
    if (this.data.connected || this.data.connecting) {
      await hostTapClient.disconnect()
      return
    }
    if (!this.currentPairing) {
      this.setData({ operationMessage: '请先扫描电脑接收器显示的配对二维码' })
      return
    }
    try {
      await hostTapClient.connect(this.currentPairing)
    } catch (error) {
      this.setData({ operationMessage: error instanceof Error ? error.message : '连接失败' })
    }
  },

  saveCurrentComputer() {
    if (!this.currentPairing) {
      this.setData({ operationMessage: '请先扫描电脑接收器显示的配对二维码' })
      return
    }
    try {
      const savedComputers = store.save(this.currentPairing)
      this.setData({
        savedComputers,
        operationMessage: `已保存 ${this.currentPairing.displayName()}`
      })
    } catch (error) {
      this.setData({ operationMessage: error instanceof Error ? error.message : '无法保存电脑' })
    }
  },

  async connectSavedComputer(event) {
    const id = event.currentTarget.dataset.id
    const saved = this.data.savedComputers.find((item) => item.id === id)
    if (!saved) return
    this.setData({ operationMessage: '' })
    try {
      const pairing = store.resolve(saved)
      this.currentPairing = pairing
      this.setData({
        pairingReady: true,
        pairingName: pairing.displayName(),
        pairingEndpoint: pairing.endpointLabel()
      })
      await hostTapClient.connect(pairing)
    } catch (error) {
      this.setData({ operationMessage: error instanceof Error ? error.message : '无法连接已保存的电脑' })
    }
  },

  removeSavedComputer(event) {
    const id = event.currentTarget.dataset.id
    const saved = this.data.savedComputers.find((item) => item.id === id)
    if (!saved) return
    try {
      const savedComputers = store.remove(id)
      this.setData({ savedComputers, operationMessage: `已移除 ${saved.name || saved.host}` })
    } catch (error) {
      this.setData({ operationMessage: error instanceof Error ? error.message : '无法移除已保存的电脑' })
    }
  },

  toggleAutoReconnect() {
    hostTapClient.setAutoReconnect(!this.data.autoReconnectEnabled)
  },

  onTouchStart(event) {
    if (!this.data.inputAvailable) return
    const touches = event.touches || []
    this.touchStartedAt = Date.now()
    this.totalMove = 0
    if (touches.length >= 2) {
      this.touchMode = 'scroll'
      this.lastScrollY = (touches[0].clientY + touches[1].clientY) / 2
      return
    }
    if (touches.length === 1) {
      this.touchMode = 'move'
      this.lastX = touches[0].clientX
      this.lastY = touches[0].clientY
    }
  },

  onTouchMove(event) {
    if (!this.data.inputAvailable) return
    const touches = event.touches || []
    if (touches.length >= 2) {
      const averageY = (touches[0].clientY + touches[1].clientY) / 2
      if (this.touchMode !== 'scroll') {
        this.touchMode = 'scroll'
        this.lastScrollY = averageY
        this.totalMove = 20
        return
      }
      const delta = averageY - this.lastScrollY
      this.lastScrollY = averageY
      this.totalMove += Math.abs(delta)
      hostTapClient.scroll(delta * 2.2)
      return
    }
    if (touches.length === 1) {
      const x = touches[0].clientX
      const y = touches[0].clientY
      if (this.touchMode !== 'move') {
        this.touchMode = 'move'
        this.lastX = x
        this.lastY = y
        return
      }
      const dx = x - this.lastX
      const dy = y - this.lastY
      this.lastX = x
      this.lastY = y
      this.totalMove += Math.abs(dx) + Math.abs(dy)
      hostTapClient.move(dx, dy)
    }
  },

  onTouchEnd(event) {
    if (!this.data.inputAvailable) {
      this.touchMode = ''
      return
    }
    const elapsed = Date.now() - this.touchStartedAt
    const remaining = event.touches || []
    if (this.touchMode === 'move' && remaining.length === 0 && elapsed <= 320 && this.totalMove < 12) {
      hostTapClient.click('left')
    }
    if (remaining.length === 0) this.touchMode = ''
  },

  leftClick() { hostTapClient.click('left') },
  rightClick() { hostTapClient.click('right') },
  scrollUp() { hostTapClient.scroll(180) },
  scrollDown() { hostTapClient.scroll(-180) },
  keyEnter() { hostTapClient.key('enter') },
  keyBackspace() { hostTapClient.key('backspace') },
  keyEscape() { hostTapClient.key('escape') },

  onDraftInput(event) {
    this.setData({ draft: event.detail.value || '' })
  },

  async sendDraft() {
    this.setData({ operationMessage: '' })
    const result = await hostTapClient.sendText(this.data.draft)
    if (result.accepted) {
      this.setData({ draft: '', operationMessage: '文字已提交给电脑' })
      return
    }
    if (result.uncertain) {
      this.setData({ operationMessage: `${result.error}；不要直接重发，请先查看电脑屏幕` })
      return
    }
    this.setData({ operationMessage: result.error })
  }
})
