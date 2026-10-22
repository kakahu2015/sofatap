# Sofatap 实机验收

## LG webOS：实体电视验收待完成

LG webOS remains an experimental manual-pairing preview. No physical TV model is qualified yet. Before claiming support, record the model and firmware, confirm on-TV authorization and reconnection, then check pointer movement, clicks, scrolling, Back and Home in representative apps. See the [webOS preview verification record](verification/webos-pointer-preview.md).

## 历史桌面验收清单

适用于 0.7.0 开发源码（iPhone build 10）的实时编辑及既有单屏交互。公开下载仍是 Windows/Mac 0.3.0、Linux 0.4.0，不能用于验收新增功能。以下是验收标准清单，不表示全部通过。[0.7.0 真机到 Windows 记录](verification/realtime-input-0.7.0.md)已确认 RichEdit 导入、追加、键盘删除、全选及清空，实时语音与其他项目仍待完成。[0.6.0 记录](verification/2026-10-13-iphone-input.md)另有手势、麦克风与明确发送结果。模拟器和编译不能代替真机。共享 Mac 必须先按 Yomado 虚拟屏流程验证显示器与输入隔离，保持用户的物理显示布局和当前工作不受影响。

先在测试桌面的文本编辑器里测试，再进入浏览器和日常软件。Mac 使用“文本编辑”，Windows 使用记事本，Linux 使用测试环境中的文本编辑器。测试不使用真实密码或私人内容。

| 项目 | 操作 | 通过标准 |
| --- | --- | --- |
| 旧版本升级 | 关闭旧接收端并禁用旧快捷方式，安装新版本重新扫码 | 新版 `sofatap://` 链接连接成功，旧进程未占用端口 |
| 默认界面 | 打开 App、收起草稿区，检查不同屏幕尺寸 | logo、扫码、底部麦克风与左侧键盘均在大圆角触控区域内；区域内没有说明文字或独立鼠标指针图形（logo 自带的光标保留）；入口不被裁切 |
| 入口与状态 | 分别点 logo、扫码、键盘和麦克风；用 VoiceOver 读取各入口 | 状态／帮助页显示连接详情；入口标签明确；录音状态可辨识；点入口不会同时发出鼠标点击或拖动 |
| 配对 | 从区域内扫码入口扫描电脑二维码 | 状态页显示正确电脑名称与已连接状态 |
| 指针 | 慢速、快速滑动触控板 | 电脑鼠标随手指移动，没有松手后继续漂移 |
| 点击 | 单击、单指连续双击 | 分别触发一次左键和一次右键；双击不会先触发左键 |
| 拖动与滚动 | 静止按住 300 ms 再移动，松手；双指滑动 | 拖动和滚动正确，松开／中断／断连后没有鼠标键卡住 |
| 触控特效 | 滑动、单击、双击、静止按住 300 ms 后拖动、松手与取消；连续操作后停止 | 整个背景点阵缓慢流动，触摸使附近点阵局部畸变；单击／双击涟漪和拖动反馈可区分；松手后点阵恢复、回到缓慢流动；后台／断连停止动画，减弱动态效果保持静态点阵；不会产生额外鼠标动作或冒充桌面成功回执 |
| 减弱动态效果 | 开启系统「减弱动态效果」，重复上述手势 | 使用简化反馈，手势含义和输入结果不变 |
| 中文 | `你好，Windows。今天想看一部电影。` | 逐字一致、不自动搜索/发送 |
| Unicode | `Hello 日本語 👋 café` 和多行文本 | 中文、日文、重音符、emoji、换行保持 |
| 已有文字实时编辑 | 电脑预置非敏感中日英、emoji、多行与混合格式；手机退格、局部替换、全选删除 | 手机导入完整文字及选区；差异准确同步，周围格式保持；不改错字段；不需要发送 |
| 实时隐私 | 打开支持字段的实时编辑，再关闭连接并检查自建测试数据 | 后台焦点无字段内容；实时内容仅进入配对手机内存和连接内去重缓存，不写入保存草稿或日志 |
| 麦克风 | 点底部麦克风、授权、听写并停止；测试识别修正、拒绝／中断／不支持的语言 | 实时模式中间结果及修正同步到实际字段，停止不回退；回退模式仍需发送；无音频上传、不转云端、后台无录音 |
| 系统听写 | 在同页键盘里用系统听写或第三方输入法 | IME 不被刷新打断，组合候选确认后同步；不会重复输入旧文字；回退模式只编辑草稿 |
| Typeless | 切到 Typeless，测试需要／不需要跳 App 两种情况 | 未切后台时按相应模式编辑；切后台后实时会话暂停，重新打开前不自动重放；回退草稿仍可恢复 |
| 发送结果未知 | 实时同步或回退发送时断开 Wi-Fi | 实时模式暂停并保留内存结果供检查；回退模式保留草稿；均不自动重试 |
| 防重复 | 重复同一实时编辑 ID、发送旧 revision；回退模式连点发送 | 相同 ID 不重复写入；旧 revision 使会话失效；结果不确定时不重放 |
| 焦点 | 点击支持字段、浏览器、自绘控件、只读／密码测试框；录入过程中移动电脑选区 | 后台只有元数据检测；打开实时编辑才读取当前支持字段全文及选区；冲突暂停，不写入另一字段；受保护字段不自动打开或开始实时编辑；其他目标保留回退输入 |
| 权限边界 | 测试管理员软件/系统确认界面 | 无法注入时可识别限制，不误报已成功保存 |
| 恢复 | 锁屏手机后解锁、重启电脑接收端 | 连接可恢复，实时编辑需明确重开；回退草稿保留；不自动注入旧操作 |

真实设备测试结果记录在 `artifacts/` 的验证报告中；未执行项目必须标记为未验证。离线模拟器截图与视频仅展示界面和本地特效，不能据此宣称实体手机帧率、触控延迟或电脑输入已通过。

2026-10-12 已用常驻测试 iPhone 完成[真机到隔离测试接收器的验证](verification/iphone-isolated-0.6.0.md)。该测试使用 Yomado 虚拟屏上的只读画布，验证真实配对、手势与文字协议；没有向 macOS 输入系统注入事件，不能替代上表中的真实桌面输入、实际焦点检测、语音转写或相机扫码验收。

## Mac 接收端补充验收

以下项目用于 macOS 接收端，不表示已经通过。分别记录 Apple Silicon 与 Intel Mac 的型号、系统版本、构建 SHA、签名状态和 iPhone 版本。通用的指针、拖动、Unicode、听写、Typeless、断连、防重复和结果未知项目同样适用；使用“文本编辑”替代记事本。

| 项目 | 操作 | 通过标准 |
| --- | --- | --- |
| 架构与安装 | 选择对应芯片的 ZIP，解压后将 `.app` 放入“应用程序” | 不依赖外部 .NET/Python；包中单一 Mach-O 架构与标示一致 |
| 初次打开 | 从 Finder 打开 `.app`；记录系统安全或本地网络提示 | 正常打开原生配对窗口；未修改 Gatekeeper、防火墙或系统隐私策略 |
| 未获授权 | 保持 Sofatap 未获辅助功能授权，再尝试控制 | 原生窗口和手机明确显示不可输入；没有把 Terminal/dotnet/browser 当作授权对象 |
| 明确授权 | 用户通过原生窗口入口，在系统设置授权 Sofatap Receiver.app | 授权仅由用户完成；刷新或重启后能控制普通桌面 |
| 授权撤销 | 连接中手动撤销 Sofatap 辅助功能权限 | 后续操作明确失败或显示不可用，不误报已写入目标软件 |
| 控制与 Unicode | 在文本编辑测试移动、左右键、拖动、双向滚动及 `Hello 日本語 👋 café` | 方向正确、文字一致；无剪贴板副作用，断连释放程序按住的按钮 |
| 实时与回退文本 | 在支持 AXSelectedText 的测试字段编辑、删除、多行；再换不支持的字段 | 实时编辑不需发送，已有文字与选区同步、周围格式保留；不支持时只修改草稿并点发送；不追加提交操作 |
| 多显示器 | 主副屏位于不同方向或使用不同缩放，跨屏移动和拖动 | 指针坐标正确，可触达显示器边缘，没有跳跃或持续漂移 |
| 锁屏与切换用户 | 锁定 Mac 或切换用户，再解锁返回 | 安全界面不会被绕过；恢复后需要正常连接，不回放旧操作 |
| 正常退出 | 原生窗口点“退出 Sofatap”，检查手机与端口 | 连接断开，控制端口停止监听，已按住按钮被释放 |
| 关闭原生窗口 | 点击窗口关闭按钮，再检查手机连接和后台进程 | 后台停止，手机断连；重新打开应用可恢复配对 |
| 移动与升级 | 退出后换新 `.app`，重新打开并检查权限 | 配对数据按设计保留；若本地签名变化导致权限失效，提示重新授权 |
| 普通用户分发 | 在干净的另一台 Mac 下载正式构建并打开 | Developer ID 签名及公证可验证；安装、授权和退出全流程不需要终端 |

ad-hoc 本地签名与交叉编译成功不能替代正式分发或真实 Intel Mac/iPhone 联合测试。测试报告应单独列出构建验证、传输演练、辅助功能授权、物理输入和未执行项目。

## Linux：0.5.0 新交互及既有输入的设备验收

Run the native GTK receiver in a logged-in test desktop. Test X11 and each intended
Wayland compositor separately. On Wayland, deny authorization once, then approve
from the receiver's native button; verify controls remain disabled until approved,
and become unavailable when the session is revoked. Confirm native QR scanning,
TLS connection, multilingual/emoji text in the intended applications, pointer
movement, single/double taps, 300 ms hold-to-drag, two-finger scrolling, disconnect cleanup,
lock/unlock and receiver shutdown. Check that clipboard content is unchanged.

CI uses its own Xvfb and private mock D-Bus service. This tests X11/GTK mechanics
and portal wire behavior; it does not substitute for a physical iPhone, a real
Wayland permission dialog or compositor-level Unicode compatibility.

For 0.5.0 focus detection, test supported native and browser fields with the
optional AT-SPI bridge installed. Initial detection waits for a focus event.
Verify writable, read-only, protected and unsupported targets; then remove or
disable the helper in the isolated test package and confirm manual keyboard input
still works. Portal permission alone does not establish AT-SPI availability.
Microphone permissions, local recognition, Typeless and automatic keyboard
behavior still require the physical iPhone; the historical 0.4.0 receiver checks
do not cover these features.


## Windows：原生 PIN 锁屏输入（待验收）

产品方向只使用用户已有的 Windows Hello PIN，不索取 Microsoft 账户密码。自动输入和手机手动输入都依赖相同的安全桌面输入通道。详见 [验证范围](FACE-ID-UNLOCK.md)。

| 项目 | 操作与通过标准 |
| --- | --- |
| 只读前提 | 记录 Windows 构建、物理控制台锁定状态和进程权限；不记录账户标识或凭据 |
| 一次性鼠标探针 | 已锁定控制台的 Winlogon 桌面上，小幅移动且观察到坐标改变；无人移动时恢复，检查退出和清理；事先关闭屏幕键盘（悬停可能输入），禁止点击、键盘或截屏 |
| iPhone 鼠标 | 通过已配对且受限的桥接控制真实锁屏指针；断线、换会话或退出锁屏时停止旧操作 |
| 手动 PIN | 用户用手机鼠标点击 Windows 登录界面的屏幕键盘，或使用另行验证的手机安全键盘；原生 PIN 接受并进入同一电视控制台；不保存输入内容或可还原 PIN 的点击轨迹 |
| 可选保存 | 用户明确选择后，仅把 PIN 保存到目标绑定且受 Face ID 保护的本机钥匙串 |
| Face ID 取消或变化 | 不发送任何 PIN；身份或面容登记变化后重新启用，不把旧账户密码当作 PIN |
| 部分输入或断网 | 不自动重发，不把 SendInput 成功当作解锁成功；保留用户手动恢复路径 |
| 重启首次登录 | 独立验收 Windows 和网络就绪后的登录；锁屏恢复成功不能代替此项，不涉及 BitLocker／UEFI |
| 恢复 | 原生登录方式和安全策略始终保持；诊断服务退出并移除自身，不影响接收器及用户桌面 |

禁止在真实 PIN 框里注入测试数字；某些配置输入足够位数会自动提交。真实 PIN 只由用户输入，不进入 Codex、截图、测试脚本或日志。每阶段记录源码 SHA 与实际证据，尚未完成的阶段保持待验收。
