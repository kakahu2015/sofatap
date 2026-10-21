const { hostTapClient } = require('./lib/client')

App({
  onShow() {
    hostTapClient.setForeground(true)
  },
  onHide() {
    hostTapClient.setForeground(false)
  }
})
