# HostTap 隐私政策 / Privacy Policy

**适用应用**：HostTap（包名 `org.kakahu.hosttap`）
**开发者**：［待填：开发者姓名 / Developer name］
**生效日期**：［待填：YYYY年MM月DD日 / Effective date］

本政策说明 HostTap（以下简称「本应用」）如何处理与您相关的信息。本应用是一款局域网内的电脑远程控制工具：手机与您本人的电脑在同一局域网内建立加密连接，由手机端模拟鼠标与键盘输入。

> **核心结论**：本应用不设服务器，不收集、不上传、不共享您的任何个人信息。所有数据仅保存在您的设备本地，或直接传输至您本人局域网内的电脑。

---

## 一、本应用不收集的信息

本应用**不会**收集以下任何信息：

- 姓名、手机号、邮箱、身份证号等个人身份信息；
- 地理位置信息；
- 通讯录、短信、通话记录；
- 设备识别码（IMEI、OAID、序列号等）；
- 应用使用行为、崩溃日志、统计数据。

本应用未集成任何第三方统计分析、广告或推送 SDK。

## 二、需要说明的系统权限

| 权限 | 用途 | 是否上传 |
|---|---|---|
| 网络访问 `ohos.permission.INTERNET` | 与您本人局域网内的电脑建立加密连接，传输控制指令 | 否。仅局域网内直连，不经任何中转服务器 |
| 获取网络信息 `ohos.permission.GET_NETWORK_INFO` | 判断当前是否处于可用网络，以便恢复与电脑的连接 | 否 |
| 系统扫码服务（无需本应用申请相机权限） | 扫描电脑端展示的配对二维码。本应用仅获得二维码中的文本内容 | 否。画面由系统处理，本应用不存储、不上传任何图像 |

## 三、本应用在本地保存的信息

为免去每次重新配对的麻烦，本应用会在**您的设备本地**保存以下内容：

| 内容 | 保存位置 | 说明 |
|---|---|---|
| 已配对电脑的名称、地址、端口、证书指纹 | 应用偏好设置（Preferences） | 最多保存 20 台电脑的配对记录 |
| 访问凭据（Bearer Token） | 系统资产存储服务（Asset Store） | 由系统加密保护，不写入普通文件 |

上述数据不会离开您的设备。您可以在应用内删除已保存的电脑，卸载本应用亦会一并清除上述本地数据。

## 四、与电脑之间的数据传输

本应用与电脑之间的连接具有以下保护措施：

- 连接建立后，先校验证书指纹，指纹不符即中止连接；
- 凭据仅在证书校验通过后发送；
- 传输内容为鼠标、键盘的控制指令，以及您在「文本输入」与「实时编辑」功能中主动提交的文字。

上述内容仅在您的手机与您的电脑之间传输，不经过任何第三方服务器。

## 五、信息的共享、转让与公开披露

本应用不共享、不转让、不公开披露您的任何信息。因本应用不收集信息，故不存在向第三方提供的情形。法律法规另有强制要求的除外。

## 六、未成年人保护

本应用为工具类软件，不面向未成年人设计，不收集任何年龄相关信息，亦不包含任何针对未成年人的内容或广告。

## 七、您的权利

- **查看与删除**：在应用内管理或删除已保存的电脑配对记录；
- **停止扫码与连接**：可随时退出系统扫码页停止扫描，或在应用内断开与电脑的连接；
- **彻底清除**：卸载本应用即可删除全部本地数据。

## 八、政策更新

若本应用功能发生变化导致信息处理方式调整，本政策将同步更新，并在本页面公示。重大变更时，我们会在应用内以显著方式提示。

## 九、联系我们

如对本政策有任何疑问、意见或投诉，可通过以下方式联系开发者：

- 联系邮箱：［待填：联系邮箱 / Contact email］

我们将在收到请求后 15 个工作日内答复。

---

本政策自上述生效日期起施行。

---
---

# HostTap Privacy Policy

**App**: HostTap (bundle name `org.kakahu.hosttap`)
**Developer**: ［TBD］
**Effective date**: ［TBD］

This policy explains how HostTap (the "App") handles information related to you. The App is a remote control tool for your computer within a local area network (LAN): your phone establishes an encrypted connection with your own computer on the same local network and emulates mouse and keyboard input.

> **Key statement**: The App has no server and does not collect, upload, or share any of your personal information. All data is kept locally on your device or transmitted directly to your own computer on your local network.

## 1. Information the App Does Not Collect

The App does **not** collect any of the following:

- Personal identity information such as name, phone number, email address, or ID number;
- Location information;
- Contacts, SMS, or call records;
- Device identifiers (IMEI, OAID, serial number, etc.);
- App usage behaviour, crash logs, or analytics data.

The App integrates no third-party analytics, advertising, or push notification SDKs.

## 2. System Permissions

| Permission | Purpose | Uploaded? |
|---|---|---|
| Network access `ohos.permission.INTERNET` | Establishes an encrypted connection with your own computer on your LAN to transmit control commands | No. Direct LAN connection only; no relay server is involved |
| Network information `ohos.permission.GET_NETWORK_INFO` | Determines whether a usable network is available so the connection to your computer can be restored | No |
| System scanner (no app camera permission request) | Scans the pairing QR code displayed on your computer. The App only receives the text content of the QR code | No. The camera feed is handled by the system; the App neither stores nor uploads any image |

## 3. Information Stored Locally

To avoid repeating the pairing process, the App stores the following **locally on your device**:

| Content | Storage location | Notes |
|---|---|---|
| Names, addresses, ports, and certificate fingerprints of paired computers | Application Preferences | Up to 20 pairing records |
| Access credential (Bearer Token) | System Asset Store | Encrypted by the system; not written to ordinary files |

This data never leaves your device. You may delete saved computers within the App; uninstalling the App also removes all such local data.

## 4. Data Transmission Between Your Phone and Computer

The connection between the App and your computer includes the following safeguards:

- After the connection is established, the certificate fingerprint is verified first; the connection is aborted if it does not match;
- Credentials are sent only after the certificate verification succeeds;
- The transmitted content consists of mouse and keyboard control commands, plus text you deliberately submit through the "text input" and "live editing" features.

This content is transmitted only between your phone and your computer, and never through any third-party server.

## 5. Sharing, Transfer, and Public Disclosure

The App does not share, transfer, or publicly disclose any of your information. Because the App collects no information, there is no disclosure to third parties. The only exception is where required by applicable law or regulation.

## 6. Protection of Minors

The App is a utility tool not designed for minors. It collects no age-related information and contains no content or advertising directed at minors.

## 7. Your Rights

- **Access and deletion**: manage or delete saved computer pairing records within the App;
- **Stop scanning or connecting**: leave the system scanning screen to stop scanning, or disconnect from your computer within the App at any time;
- **Complete erasure**: uninstalling the App removes all local data.

## 8. Policy Updates

If changes to the App's functionality alter how information is handled, this policy will be updated accordingly and published on this page. For material changes, we will notify you prominently within the App.

## 9. Contact Us

For any questions, comments, or complaints regarding this policy, please contact the developer:

- Contact email: ［TBD］

We will respond within 15 business days of receiving your request.

---

This policy takes effect on the effective date stated above.
