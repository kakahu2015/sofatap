# Sofatap Mac 接收端

Mac 接收端让你用 iPhone 控制 Mac 的鼠标，并把手机输入框里的文字发送到 Mac 当前选中的输入框。电脑和手机通过家庭局域网连接。手机可以使用系统听写或 Typeless 等第三方键盘；Sofatap 不采集或转发麦克风音频。

当前 0.7.3 beta.1 接收端使用 AppKit / SwiftUI 原生窗口，首次正常打开后默认登记登录项，可在原生窗口中关闭。

历史验收记录：[v0.3.0-alpha 公证预览版](https://github.com/chekusu/sofatap/releases/tag/v0.3.0-alpha) 的 Apple Silicon 与 Intel 包均已完成 CHEKUSU Developer ID 签名、Apple 公证、票据装订和 Gatekeeper 验证。2026-10-11 已完成 iPhone 到 Apple Silicon Mac 的部分真实输入验收，具体范围与校验值见 [0.3.0 验收记录](verification/macos-0.3.0.md)。

品牌统一写作 **Sofatap**。已发布的 0.3.0 公证包仍保留历史文件名 `Sofa Tap Receiver.app`，其系统权限条目也可能显示历史名称；当前源码的新构建使用 `Sofatap Receiver.app`。下面安装步骤使用当前名称；升级历史版本时请认准同一个接收端，不要保留两份同时运行。

## 选择与打开

1. Mac 需要 macOS 15 或更新版本。在苹果菜单的“关于本机”查看芯片：Apple M 系列选择 `SofaTap-macOS-arm64-notarized.zip`；Intel 处理器选择 `SofaTap-macOS-x64-notarized.zip`。两份安装包分别对应一种处理器，不是 Universal 应用。包内包含运行时，不需要安装 .NET、Python 或 Node。
2. 解压 ZIP，将 **Sofatap Receiver.app** 拖到“应用程序”文件夹。先固定安装位置，再授予权限；不要直接在 ZIP 或每次变化的下载目录里运行。
3. 双击应用，按 macOS 的正常首次打开提示操作。从发布页下载带 `-notarized` 标记的公证包；如果系统仍提示无法验证、应用损坏或包含恶意软件，请核对下载来源与校验值并联系维护者。
4. 接收端会显示原生配对窗口。选择家庭网络地址，直接扫描窗口中的二维码。不打开浏览器，不需要终端窗口。

校验文件 `SofaTap-macOS-arm64-notarized.zip.sha256` 或 `SofaTap-macOS-x64-notarized.zip.sha256` 记录对应 ZIP 的 SHA256。最终 ZIP 包含接收端 `.app` 及 macOS 归档元数据；原始构建 manifest、公证回执和本地配置不随包分发。默认源码构建仍使用 ad-hoc 本地签名，不自动获得发布包的公证状态。

## 允许控制 Mac

在原生配对窗口点 **请求辅助功能授权**，按系统提示打开“系统设置 → 隐私与安全 → 辅助功能”，开启对应的接收端权限条目。如果没有弹出提示，点 **打开系统设置**；如果应用不在列表里，点添加按钮并选择“应用程序”中的 **Sofatap Receiver.app**。系统可能要求你使用密码或 Touch ID 确认。不同 macOS 版本的权限名称或位置可能略有不同，以系统提示为准。

授权对象应当是这个接收端应用。从 `.app` 正常启动后进行授权，不需要给 Terminal、dotnet 或浏览器授予控制电脑的权限。完成授权后返回原生窗口点“重新检查”；如果仍显示未获授权，先退出 Sofatap，再重新打开应用。

辅助功能权限能让应用移动鼠标、点击和输入文字，请只授予你信任的版本。官网下载版使用 `org.kakahu.hosttap` 标识；Mac App Store 版使用独立的 `com.chekusu.sofatap.receiver` 标识。开发构建重新编译后签名身份可能变化；更换版本或移动安装位置后，macOS 可能要求重新授权。若旧条目失效，在辅助功能列表移除旧条目，再添加并授权当前 `.app`。不要使用重置全部隐私权限的命令。

## 手机配对与测试

当前版本默认尝试实时编辑：支持可写 AXSelectedText/AXSelectedTextRange 的字段会把全文及选区导入手机，自动同步修改；其他字段仍用草稿发送。不同软件的字段支持情况需要分别确认，详见[数据处理与限制](TEXT-FOCUS.md)。iPhone 的独立麦克风功能使用本机识别，音频不转发。

1. 让 iPhone 与 Mac 处于同一个受信任的家庭局域网。Mac 可以用网线，iPhone 使用同一路由器的 Wi-Fi。访客网络的设备隔离可能阻止连接。
2. 如果 macOS 提示允许 Sofatap 使用本地网络或接收入站连接，根据你对这个家庭网络和应用的信任作出选择。接收端不提供网页配对端口；手机控制使用 TCP 53319，不需要路由器端口转发。
3. 打开 iPhone 上的 Sofatap，选择配对电脑并扫描 Mac 原生窗口中家庭网络地址对应的二维码。二维码包含控制凭据，不要公开截图或转发给其他人。
4. 在 Mac 打开“文本编辑”，新建文稿并点击正文，确认它是系统当前前台应用且正文中有插入光标。然后只操作手机，先测试触控板和点击，再发送 `你好 Mac 👋`。实时编辑可用时文字会自动同步；草稿模式下点“发送”才会发出文字，两者都不会自动追加提交操作。仅看到某个窗口或在自动化工具中选中它，不代表系统键盘焦点已经切到该窗口。
5. 历史 0.3.0 验收已在 TextEdit 验证普通文字发送、中文、日文、emoji、é 和换行；在其他软件中仍需分别确认。系统听写、Typeless、实体陀螺仪空鼠、拖拽和滚动尚未完成此次实机验收，可按 [验收清单](ACCEPTANCE.md) 继续检查。

手机提示已发送，只表示接收端已向系统提交输入事件，并不证明目标软件已经保存或提交。超时可能表示结果未知；先查看 Mac 上的内容，再决定是否重发。锁屏、登录界面、系统安全确认、某些密码框和拒绝模拟输入的程序不保证可用。

## 退出与更新

关闭普通接收器窗口会隐藏窗口并继续接收，点击 Dock 图标即可重新打开。要停止控制，请在原生菜单或窗口中选择 **退出 Sofatap**。原生 App 会请求接收组件停止、断开手机，并尽力释放由它按住的鼠标按钮。父窗口进程异常结束时，管道关闭也会停止接收组件。

如果窗口没有响应，可打开“活动监视器”，搜索 **SofaTap**，确认是 Sofatap Receiver.app 对应进程，选择“停止 → 退出”。优先使用正常“退出”；强制退出不能保证执行程序的输入释放逻辑。

更新前先退出接收端，再替换“应用程序”中的旧版本，重新打开并检查权限。0.7.3 beta.1 首次正常启动默认登记登录项；窗口中的“登录时自动启动”可随时关闭。若 macOS 要求确认，请进入“系统设置 → 通用 → 登录项与扩展”允许，界面会显示待批准状态。系统禁用后不会自动重新开启。自启只在用户登录后生效，不会修改防火墙或已有辅助功能权限。配对凭据保存在当前用户的本地应用数据目录，不在 `.app` 内；替换 `.app` 不会主动清除配对数据。电脑网络地址、证书或凭据改变后需要重新扫码。

## 构建与分发状态

开发者在 macOS 上分别运行 `bash scripts/build-macos.sh arm64` 和 `bash scripts/build-macos.sh x64`。构建脚本仅生成 `.app`、ZIP 与校验文件，不安装、不启动接收服务、不授予权限。现有 iPhone 图标只做 `.icns` 格式转换，后续可以随正式视觉方案一起替换。

构建阶段保留本地 `BUILD-MANIFEST.json`，记录体系结构、源代码版本、SDK、签名状态以及是否包含未提交修改；公证阶段另外保留本地回执。它们不放进最终公证 ZIP。manifest 的 `physicalInputVerified: false` 表示打包流程本身没有执行实机验收；此后完成的实测单独记录，不回写或改变安装包。Intel 包虽已公证，仍需在真实 Intel Mac 上进行输入验收。

原生界面位于 `Contents/MacOS/SofaTap`，接收组件位于 `Contents/Helpers/SofaTapHost`，通过仅继承给子进程的内存管道通信。二维码和配对密钥不写入界面日志，也不通过 HTTP 页面提供。

应用包的 `Contents/Resources` 内含项目 MIT 许可证、第三方说明和实际解析到的依赖许可证。每个发布包的实际源码版本以该次发布的构建清单为准。历史 0.3.0 包对应源码 `8e6d0cea771b1e1b7879aff94bce437cb971298a`，这不是当前版本的源码标识。重新构建后的二进制需要重新完成签名与公证，具体命令见 [公证说明](NOTARIZATION.md)。

系统行为参考：[Apple 辅助功能权限说明](https://support.apple.com/guide/mac-help/allow-accessibility-apps-to-access-your-mac-mh43185/mac)、[Apple 打开未知开发者应用说明](https://support.apple.com/guide/mac-help/open-a-mac-app-from-an-unknown-developer-mh40616/mac)、[.NET 10 支持的系统](https://github.com/dotnet/core/blob/main/release-notes/10.0/supported-os.md)。
