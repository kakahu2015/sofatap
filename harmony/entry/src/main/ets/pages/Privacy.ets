import { router } from '@kit.ArkUI';

interface PolicySection {
  title: string;
  lines: string[];
}

const ZH_TITLE: string = '隐私政策';
const ZH_UPDATED: string = '适用应用：HostTap（org.kakahu.hosttap）';

const ZH_SECTIONS: PolicySection[] = [
  {
    title: '核心结论',
    lines: [
      '本应用不设服务器，不收集、不上传、不共享您的任何个人信息。',
      '所有数据仅保存在您的设备本地，或直接传输至您本人局域网内的电脑。'
    ]
  },
  {
    title: '一、本应用不收集的信息',
    lines: [
      '姓名、手机号、邮箱、身份证号等个人身份信息；',
      '地理位置信息；',
      '通讯录、短信、通话记录；',
      '设备识别码（IMEI、OAID、序列号等）；',
      '应用使用行为、崩溃日志、统计数据。',
      '本应用未集成任何第三方统计分析、广告或推送 SDK。'
    ]
  },
  {
    title: '二、系统权限与扫码说明',
    lines: [
      '网络访问（ohos.permission.INTERNET）：与您本人局域网内的电脑建立加密连接，传输控制指令。仅局域网内直连，不经任何中转服务器。',
      '获取网络信息（ohos.permission.GET_NETWORK_INFO）：判断当前是否处于可用网络，以便恢复与电脑的连接。',
      '系统扫码：使用系统默认扫码界面扫描电脑端展示的配对二维码，无需本应用申请相机权限。本应用仅获得二维码中的文本内容，不存储、不上传相机画面。',
      '以上功能均不用于上传或分析您的信息。'
    ]
  },
  {
    title: '三、本应用在本地保存的信息',
    lines: [
      '已配对电脑的名称、地址、端口与证书指纹，保存在应用偏好设置中，最多 20 条；',
      '访问凭据（Bearer Token），保存在系统资产存储服务（Asset Store）中，由系统加密保护；',
      '您对隐私政策的确认状态与政策版本号。',
      '上述数据不会离开您的设备。您可在应用内删除已保存的电脑，卸载本应用亦会一并清除。'
    ]
  },
  {
    title: '四、与电脑之间的数据传输',
    lines: [
      '连接建立后先校验证书指纹，指纹不符即中止连接；',
      '凭据仅在证书校验通过后发送；',
      '传输内容为鼠标、键盘控制指令，以及您在「文字发送」与「实时编辑」中主动提交的文字。',
      '上述内容仅在您的手机与您的电脑之间传输，不经过任何第三方服务器。'
    ]
  },
  {
    title: '五、信息的共享、转让与公开披露',
    lines: [
      '本应用不共享、不转让、不公开披露您的任何信息。',
      '因本应用不收集信息，故不存在向第三方提供的情形。法律法规另有强制要求的除外。'
    ]
  },
  {
    title: '六、未成年人保护',
    lines: [
      '本应用为工具类软件，不面向未成年人设计，不收集任何年龄相关信息，亦不包含任何针对未成年人的内容或广告。'
    ]
  },
  {
    title: '七、您的权利',
    lines: [
      '查看与删除：在应用内管理或删除已保存的电脑配对记录；',
      '停止扫码：可随时退出系统扫码界面停止扫描；',
      '彻底清除：卸载本应用即可删除全部本地数据。'
    ]
  },
  {
    title: '八、政策更新与联系方式',
    lines: [
      '若功能变化导致信息处理方式调整，本政策将同步更新并在页面公示；重大变更时会在应用内提示。',
      '如有疑问、意见或投诉，可通过应用市场页面的开发者联系方式与我们联系，我们将在 15 个工作日内答复。'
    ]
  }
];

const EN_TITLE: string = 'Privacy Policy';
const EN_UPDATED: string = 'App: HostTap (org.kakahu.hosttap)';

const EN_SECTIONS: PolicySection[] = [
  {
    title: 'Key statement',
    lines: [
      'The App has no server and does not collect, upload, or share any of your personal information.',
      'All data is kept locally on your device or transmitted directly to your own computer on your local network.'
    ]
  },
  {
    title: '1. Information the App Does Not Collect',
    lines: [
      'Personal identity information such as name, phone number, email address, or ID number;',
      'Location information;',
      'Contacts, SMS, or call records;',
      'Device identifiers (IMEI, OAID, serial number, etc.);',
      'App usage behaviour, crash logs, or analytics data.',
      'The App integrates no third-party analytics, advertising, or push notification SDKs.'
    ]
  },
  {
    title: '2. System Permissions and Scanning',
    lines: [
      'Network access (ohos.permission.INTERNET): establishes an encrypted connection with your own computer on your LAN to transmit control commands. Direct LAN connection only; no relay server is involved.',
      'Network information (ohos.permission.GET_NETWORK_INFO): determines whether a usable network is available so the connection can be restored.',
      'System scanning: scans the pairing QR code shown on your computer using the system scanning screen, without the App requesting camera permission. The App only receives the text content of the code and neither stores nor uploads the camera feed.',
      'None of these features is used to upload or analyse your information.'
    ]
  },
  {
    title: '3. Information Stored Locally',
    lines: [
      'Names, addresses, ports, and certificate fingerprints of paired computers, kept in preferences, up to 20 records;',
      'Access credential (Bearer Token), kept in the system Asset Store and encrypted by the system;',
      'Your acknowledgement of this policy and the policy version.',
      'This data never leaves your device. You may delete saved computers in the App; uninstalling removes all of it.'
    ]
  },
  {
    title: '4. Data Transmission',
    lines: [
      'The certificate fingerprint is verified first; the connection is aborted if it does not match;',
      'Credentials are sent only after verification succeeds;',
      'Transmitted content consists of mouse and keyboard commands, plus text you deliberately submit.',
      'This content travels only between your phone and your computer, never through a third-party server.'
    ]
  },
  {
    title: '5. Sharing and Disclosure',
    lines: [
      'The App does not share, transfer, or publicly disclose any of your information.',
      'Because no information is collected, there is no disclosure to third parties, except where required by law.'
    ]
  },
  {
    title: '6. Protection of Minors',
    lines: [
      'The App is a utility tool not designed for minors. It collects no age-related information and contains no content or advertising directed at minors.'
    ]
  },
  {
    title: '7. Your Rights',
    lines: [
      'Access and deletion: manage or delete saved computer records within the App;',
      'Stop scanning: leave the system scanning screen at any time;',
      'Complete erasure: uninstalling the App removes all local data.'
    ]
  },
  {
    title: '8. Updates and Contact',
    lines: [
      'If functionality changes alter how information is handled, this policy will be updated and published here.',
      'For questions or complaints, contact the developer via the store listing. We respond within 15 business days.'
    ]
  }
];

@Entry
@Component
struct Privacy {
  @State private english: boolean = false;

  private sections(): PolicySection[] {
    return this.english ? EN_SECTIONS : ZH_SECTIONS;
  }

  build() {
    Column() {
      Row({ space: 10 }) {
        Text('‹')
          .fontSize(26)
          .fontColor('#1A6DD4')
          .onClick(() => {
            router.back();
          })
        Text(this.english ? EN_TITLE : ZH_TITLE)
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .layoutWeight(1)
        Text(this.english ? '中文' : 'English')
          .fontSize(13)
          .fontColor('#1A6DD4')
          .onClick(() => {
            this.english = !this.english;
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })

      Scroll() {
        Column({ space: 16 }) {
          Text(this.english ? EN_UPDATED : ZH_UPDATED)
            .fontSize(12)
            .fontColor('#98A2B3')
            .width('100%')

          ForEach(this.sections(), (section: PolicySection) => {
            Column({ space: 6 }) {
              Text(section.title)
                .fontSize(15)
                .fontWeight(FontWeight.Medium)
                .width('100%')
              ForEach(section.lines, (line: string) => {
                Text(line)
                  .fontSize(13)
                  .lineHeight(21)
                  .fontColor('#475467')
                  .width('100%')
              }, (line: string) => line)
            }
            .width('100%')
          }, (section: PolicySection) => section.title)

          Text(this.english
            ? 'This policy is effective upon publication.'
            : '本政策自发布之日起施行。')
            .fontSize(12)
            .fontColor('#98A2B3')
            .width('100%')
            .margin({ top: 4, bottom: 24 })
        }
        .width('100%')
        .padding({ left: 16, right: 16 })
      }
      .layoutWeight(1)
      .scrollBar(BarState.Auto)
      .edgeEffect(EdgeEffect.Spring, { alwaysEnabled: true })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}
