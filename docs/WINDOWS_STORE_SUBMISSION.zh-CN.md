# Windows 接收端 · 微软商店上架操作手册（中文）

更新于 2026-10-18。本手册对应 `artifacts/msix/packages/HostTap-Remote.msixupload`，包身份为 `org.kakahu.hosttap`（以 Partner Center 实际分配为准），可执行文件 `hosttap.exe`，同时包含 x64 与 arm64。

> 先看这一条：Windows 没有"公证"。上架商店**不需要你签名**，微软在认证通过后用自家证书重签，用户安装时也不会出现 SmartScreen 警告。你需要做的只有：把 Partner Center 分配的三个身份值交给我 → 我重新出包 → 你上传并填写商店页面 → 提交认证。

---

## 你需要做的四件事

| # | 事项 | 产出 | 谁做 |
| --- | --- | --- | --- |
| 1 | 在 Partner Center 创建产品并复制三个身份值 | `packaging/windows-store/store-identity.json` | 你 |
| 2 | 用这三个值重新构建 | `HostTap-Remote.msixupload` | 我 |
| 3 | 准备商店页面素材：截图、隐私政策 URL、支持邮箱 | 素材文件与链接 | 你（截图我可以代做） |
| 4 | 上传包 + 填页面 + 提交认证 | 认证通过并上架 | 你操作，我给逐项文案 |

---

## 第 1 步：在合作伙伴中心（Partner Center）创建产品

先明确一个容易混淆的点：**「合作伙伴中心」就是 Partner Center**，同一个网站 `partner.microsoft.com`，只是中文界面把名字翻译了。应用发布入口原来叫「Windows 与 Xbox」，现在中文界面叫 **「应用和游戏」**。

1. 打开 `https://partner.microsoft.com/dashboard`，用注册开发者账号时的那个 Microsoft 账号登录。若提示验证身份或补充资料，先完成，否则后面无法提交。
2. 左侧 **应用和游戏（Apps & Games）** → **概述（Overview）** → **新建产品（New product）** → 选 **MSIX 或 PWA 应用**。
   - 看不到「应用和游戏」时，通常是两种情况：账号还没完成 Windows 应用发布计划的注册，或者登录到了云解决方案提供商（CSP）那套合作伙伴中心。回到 `storedeveloper.microsoft.com` 用同一账号进入即可。
   - 不要选「MSI/EXE 应用」，那条路径要求你自己用受信任 CA 的证书签安装包，和当前产物不匹配。
3. 输入产品名称（建议 **HostTap Remote**，与包内显示名一致）→ **检查可用性** → **保留产品名称**。
   - 名称被占用时可以换名，但包内 `DisplayName` 不必等于商店名；对外名称由这里决定。
4. 进入产品后，页面顶部 **产品发布（Product release）** 区 → **开始提交（Start submission）**，得到一个草稿。草稿的标签页依次是：**定价和可用性、属性、年龄分级、包、商店列表、提交选项**。
5. 打开 **产品标识（Product identity）** 面板。这里显示三行，全部要一字不差地复制：
   - **包/标识/名称（Package/Identity/Name）** — 例：`org.kakahu.hosttap` 或 `12345Kakahu.HostTapRemote`
   - **包/标识/发布者（Package/Identity/Publisher）** — 形如 `CN=8B6C8A94-AAE5-4C8B-9202-A29EA42B042F`
   - **发布者显示名称（Publisher display name）** — 账号主体名称
6. 把三个值填进 `packaging/windows-store/store-identity.json`（从 `.example` 复制一份）。`version` 首次建议写 `1.0.0.0`。

```json
{
  "identityName": "<Package/Identity/Name>",
  "publisher": "<Package/Identity/Publisher>",
  "publisherDisplayName": "<Publisher display name>",
  "displayName": "HostTap Remote",
  "version": "1.0.0.0"
}
```

**关键判断：** 如果第 5 步给的名称不是 `org.kakahu.hosttap`，不要强行用 `org.kakahu.hosttap`。包名由 Partner Center 分配，本地自拟值会在上传时被识别为身份不匹配。

## 第 2 步：重新构建（由我执行）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-msix.ps1
```

脚本会读取上面的 JSON，覆盖包清单中的 Identity 三个字段；仍可用环境变量临时覆盖。构建完成后，控制台不应再出现 publisher 占位告警，并输出四个产物的 SHA256。

## 第 3 步：上传包

1. 提交草稿 → **包（Packages）** → **上传包** → 选择 `artifacts/msix/packages/HostTap-Remote.msixupload`。
2. 等待校验完成，确认页面列出 **x64** 与 **arm64** 两个条目，目标设备家族为 **Windows 桌面**。若只显示一个架构，说明 bundle 里只有单包，停止提交并回到构建步骤。
3. 校验若报「包标识与产品标识不匹配」，说明 JSON 里的 `identityName` 或 `publisher` 抄错了，逐字符核对，尤其是 GUID 的大小写与连字符。
4. 校验若报「版本号必须高于已发布版本」，改 `version` 后重构建；首次发布不会触发此提示。

## 第 4 步：填写商店页面

按提交草稿的标签页顺序逐项完成，缺一项都不能提交。

| 标签页 | 需要填什么 | 本项目的取值 |
| --- | --- | --- |
| 定价和可用性（Pricing and availability） | 市场、受众、价格、发布时间 | 免费；受众选公开；市场按需勾选；首次建议手动发布，便于控制上线时间 |
| 属性（Properties） | 类别、子类别 | 类别 **实用工具与工具（Utilities & tools）**；子类别 **远程控制 / 系统工具** |
| 属性 | 系统需求 | Windows 10 1809 及以上；需要与接收端同一局域网的 iPhone 及配套 App |
| 属性 | 支持联系信息 | 支持邮箱（必填）、可选网站 |
| 属性 | 隐私策略 URL | **必填**，因为应用访问网络。把 `PRIVACY.md` 发布到公网（仓库 Pages、官网或任意静态托管），填入完整 https 链接 |
| 年龄分级（Age ratings） | 完成问卷 | 远程输入类应用如实声明「用户交互、可控制其他设备」，不涉及暴力、色情、赌博 |
| 包（Packages） | 上传 `.msixupload`，核对架构 | 见第 3 步 |
| 商店列表（Store listings） | 描述、截图、搜索词 | 文案见 `packaging/windows-store/listing-zh-CN.md` 与 `listing-en-US.md` |
| 提交选项（Submission options） | 认证备注 | 粘贴 `packaging/windows-store/reviewer-notes.txt` 的内容 |
| 提交选项 → 限制功能（Restricted capabilities） | **runFullTrust 的书面理由，必填** | 粘贴 `packaging/windows-store/restricted-capability.txt` 的内容；不填会被退回 |

截图硬性要求：**至少 1 张，1366×768 或更大**，PNG/JPG，单语言最多 10 张。当前仓库只有概念图，没有真实窗口截图，需要补拍；需要我直接启动接收端窗口截屏也可以，说一声即可。

## 第 5 步：提交认证

1. 全部标签页显示绿色对勾后，回到 **应用概述**，点 **提交以进行认证（Submit for certification）**。
2. 流程：包预检 → 自动检测（崩溃、启动、恶意行为）→ 人工政策审核 → 微软签名 → 发布。首次通常 1–3 个工作日，更新一般更快。
3. 审核期间状态在 **认证状态** 页可见；被退回会给出具体错误码与说明，按第 7 节处理。

## 第 6 步：上架之后

- 更新走同一条路径：改版本号 → 重新构建 → 上传新的 `.msixupload` → 提交。商店负责分发，打包版内的自动更新器已被编译关闭（`SOFATAP_MSIX`）。
- 用户从商店安装后配对凭据写在包容器内（`%LOCALAPPDATA%\Packages\org.kakahu.hosttap_*\LocalCache\Local\SofaTap`），与官网免安装版不共享，切换发行版需要重新扫码配对。

## 第 7 步：常见退回原因与处理

| 报错/现象 | 原因 | 处理 |
| --- | --- | --- |
| 包标识与产品标识不匹配 | `identityName` / `publisher` 抄错或用了本地占位 | 从 **产品标识** 面板重新复制，重构建 |
| 版本号必须大于当前已发布版本 | 重复提交同一版本 | 提升 `version`，四段都必须递增有效 |
| 缺少隐私政策 | 应用访问网络但未填 URL | 发布 `PRIVACY.md` 并填链接 |
| 审核反馈「无法验证核心功能」 | 审核员没有 iPhone，无法完成配对 | 认证备注说明哪些功能无需手机即可验证（见 reviewer notes） |
| 启动即退出 | 打包版尝试写安装目录或依赖缺失 | 用 `Add-AppxPackage -Register` 本地复现，检查事件查看器 |
| 声明 runFullTrust 未说明 | 受限能力缺少理由 | 在认证备注中说明启动子进程与本地服务的必要性 |
| 图标/截图不合规 | 尺寸或内容不符 | 截图为 1366×768 以上真实界面，不得只放 Logo |

## 需要你提供给我的清单

1. `identityName`、`publisher`、`publisherDisplayName` 三个值（或直接填好 JSON 告诉我）。
2. 最终商店名称是否用 **HostTap Remote**。
3. 隐私政策可公开访问的 URL。
4. 支持邮箱。
5. 是否要我代拍真实窗口截图（我会启动一次接收端并截屏，随后关闭）。

拿到第 1 项我就能出最终可提交包；第 3、4 项只影响页面填写，不影响构建。
