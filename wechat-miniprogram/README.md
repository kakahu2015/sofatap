# HostTap WeChat Mini Program

HostTap 的微信小程序控制端。它沿用 HarmonyOS 控制端的配对、保存设备、前台重连、Live Text 和 Protocol v1 行为，但使用微信原生 TCP Socket 连接局域网内的 HostTap Receiver。

## 当前功能

- 仅允许通过摄像头扫描电脑端 HostTap 配对二维码；不提供相册选图或手工粘贴配对链接。
- 严格解析现有 `sofatap://pair?...` 二维码，继续使用同一个随机配对 token。
- 最多保存 20 台电脑。
- 小程序前台自动重连；进入后台主动暂停连接，返回前台后按设置恢复。
- 单指移动鼠标、轻点左键、双指滚动。
- 左键、右键、上下滚动、Enter、Backspace、Esc。
- 手机输入文字后明确发送到电脑；超时或连接中断时保留“结果不确定”语义，不自动重发文字。
- Live Text 实时文字编辑：`textBegin` / `textSync` / `textEnd`，120ms 合并同步，并验证 revision 与电脑端回读结果。
- Windows/macOS/Linux 仍复用 Receiver 的 `Protocol.Parse`、`ActionSession` 和现有输入后端。

## 局域网传输

原生客户端继续使用 `WSS + TLS leaf fingerprint pinning`，没有变化。

微信小程序使用单独的安全 TCP endpoint：

- WSS 控制端口默认 `53319`。
- 小程序 TCP 端口固定从二维码里的控制端口推导为 `controlPort + 1`，默认 `53320`；当控制端口为 `65535` 时回退到 `53320`。
- 二维码里的 32-byte 随机 token 不在 TCP 中明文发送。
- 客户端/服务端先通过 HMAC-SHA256 challenge-response 相互证明持有 token。
- 会话密钥由 HKDF-SHA256 派生，方向分离。
- 后续帧使用 AES-256-CBC + HMAC-SHA256（Encrypt-then-MAC），并验证严格递增的 32-bit 序号。
- Mini Program 和原生 WSS 客户端共享 Receiver 的单控制器锁，同一时刻仍只允许一个控制端。

## 在微信开发者工具中打开

1. 进入本目录：

   ```bash
   cd wechat-miniprogram
   npm install
   ```

2. 在微信开发者工具中导入 `wechat-miniprogram` 目录。
3. 将 `project.config.json` 里的 `touristappid` 换成实际的小程序 AppID，或在开发者工具中选择对应 AppID。
4. 在开发者工具执行 **工具 → 构建 npm**，生成 `miniprogram_npm`。
5. 使用真机预览/调试。TCP 控制需要手机与电脑位于可互访的同一局域网。
6. 电脑端必须运行包含 `MiniProgramServer` 的 Receiver；旧版 Receiver 只有 53319/WSS，微信小程序无法连接。

## 配对流程

1. 在电脑 HostTap 中打开现有配对二维码。
2. 在小程序点击 **扫描电脑二维码**。
3. 微信只打开摄像头扫码；扫描结果经过严格校验。
4. 点击 **连接电脑**，或先 **保存电脑**。
5. 建立安全 TCP 会话后，Receiver 返回现有 Protocol v1 `hello`，之后所有鼠标、按键、普通文字和 Live Text 操作都继续走 Protocol v1 action/ack。

## Live Text

当 Receiver 报告 `liveText.version = 1` 且电脑当前焦点是普通可编辑文本框时，小程序会显示 **实时文字** 区域。开始后先读取电脑当前文字和选区，手机侧修改会按 120ms 合并发送；每次同步都要求 Receiver 返回相同 session、预期 revision、文字和选区。以下情况会停止实时编辑：

- 电脑焦点 revision 改变；
- 焦点变成 unknown 或 protected；
- 电脑回读与手机内容不一致；
- `textSync` 超时或连接中断导致结果不确定；
- 用户执行点击、滚动、按键或普通文字发送等会改变编辑上下文的操作。

## 存储说明

HarmonyOS 版把 token 放在 Asset Store；微信小程序没有等价的 HostTap 专用硬件密钥存储接口，因此当前版本把已保存电脑的配对记录放在该小程序自己的本地 storage 沙箱中。页面响应式数据只包含设备名称、地址、端口和 ID，不包含 token 或 fingerprint。

## 当前范围

这一版覆盖 HostTap 当前主要控制链路：摄像头配对、保存电脑、自动重连、鼠标/滚动/按键、普通文字发送和 Live Text。语音、AI 等非核心扩展没有加入首版小程序。
