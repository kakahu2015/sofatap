import { HostTapPairing } from '../model/HostTapPairing';
import { HostTapActionResult, HostTapClient, HostTapState, HostTapStateListener } from '../model/HostTapClient';

@Entry
@Component
struct Index {
  @State private pairingLink: string = '';
  @State private status: string = '未连接电脑';
  @State private computerName: string = '';
  @State private connecting: boolean = false;
  @State private connected: boolean = false;
  @State private inputAvailable: boolean = false;
  @State private protectedFocus: boolean = false;
  @State private draft: string = '';
  @State private operationMessage: string = '';

  private readonly client: HostTapClient = new HostTapClient();
  private lastPanX: number = 0;
  private lastPanY: number = 0;

  aboutToAppear(): void {
    const listener: HostTapStateListener = {
      onState: (state: HostTapState): void => {
        this.status = state.status;
        this.computerName = state.computerName;
        this.connecting = state.connecting;
        this.connected = state.connected;
        this.inputAvailable = state.inputAvailable;
        this.protectedFocus = state.protectedFocus;
      }
    };
    this.client.setListener(listener);
  }

  aboutToDisappear(): void {
    void this.client.disconnect();
  }

  private async toggleConnection(): Promise<void> {
    this.operationMessage = '';
    if (this.connected || this.connecting) {
      await this.client.disconnect();
      return;
    }
    try {
      const pairing: HostTapPairing = HostTapPairing.parse(this.pairingLink);
      await this.client.connect(pairing);
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '连接失败';
    }
  }

  private async sendDraft(): Promise<void> {
    this.operationMessage = '';
    const result: HostTapActionResult = await this.client.sendText(this.draft);
    if (result.accepted) {
      this.draft = '';
      this.operationMessage = '文字已提交给电脑';
      return;
    }
    if (result.uncertain) {
      this.operationMessage = `${result.error}；不要直接重发，请先查看电脑屏幕`;
      return;
    }
    this.operationMessage = result.error;
  }

  build() {
    Scroll() {
      Column({ space: 14 }) {
        Row() {
          Column({ space: 2 }) {
            Text('HostTap')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
            Text('HarmonyOS MVP')
              .fontSize(13)
              .fontColor('#667085')
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Column({ space: 2 }) {
            Text(this.connected ? (this.computerName || '电脑') : '未连接')
              .fontSize(14)
              .fontWeight(FontWeight.Medium)
              .maxLines(1)
            Text(this.status)
              .fontSize(12)
              .fontColor(this.inputAvailable ? '#16803C' : '#667085')
              .maxLines(2)
          }
          .alignItems(HorizontalAlign.End)
        }
        .width('100%')

        Column({ space: 8 }) {
          Text('配对')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .width('100%')
          TextInput({
            text: this.pairingLink,
            placeholder: '粘贴电脑端 sofatap://pair?... 配对链接'
          })
            .width('100%')
            .height(48)
            .enabled(!this.connected && !this.connecting)
            .onChange((value: string) => {
              this.pairingLink = value;
            })
          Button(this.connecting ? '连接中…' : (this.connected ? '断开连接' : '连接电脑'))
            .width('100%')
            .height(44)
            .enabled(!this.connecting)
            .onClick(() => {
              void this.toggleConnection();
            })
          Text('第一版先用“复制配对链接 → 手机粘贴”验证完整控制链路；二维码扫描后续补。')
            .fontSize(12)
            .fontColor('#667085')
            .width('100%')
        }
        .width('100%')
        .padding(14)
        .backgroundColor('#F7F8FA')
        .borderRadius(14)

        Column({ space: 10 }) {
          Text(this.inputAvailable ? '触控区 · 单指滑动移动鼠标' : '触控区 · 连接并就绪后可用')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
          Text('在下面区域滑动')
            .fontSize(13)
            .fontColor('#667085')
        }
        .width('100%')
        .height(260)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(this.inputAvailable ? '#E9EEF8' : '#F0F1F3')
        .borderRadius(18)
        .gesture(
          PanGesture({ fingers: 1, direction: PanDirection.All, distance: 1 })
            .onActionStart(() => {
              this.lastPanX = 0;
              this.lastPanY = 0;
            })
            .onActionUpdate((event: GestureEvent) => {
              const dx: number = event.offsetX - this.lastPanX;
              const dy: number = event.offsetY - this.lastPanY;
              this.lastPanX = event.offsetX;
              this.lastPanY = event.offsetY;
              this.client.move(dx, dy);
            })
            .onActionEnd(() => {
              this.lastPanX = 0;
              this.lastPanY = 0;
            })
        )

        Row({ space: 8 }) {
          Button('左键')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.click('left'))
          Button('右键')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.click('right'))
          Button('上滚')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.scroll(-120))
          Button('下滚')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.scroll(120))
        }
        .width('100%')

        Row({ space: 8 }) {
          Button('Enter')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.key('enter'))
          Button('Backspace')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.key('backspace'))
          Button('Esc')
            .layoutWeight(1)
            .enabled(this.inputAvailable)
            .onClick(() => this.client.key('escape'))
        }
        .width('100%')

        Column({ space: 8 }) {
          Row() {
            Text('文字发送')
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
            if (this.protectedFocus) {
              Text('安全输入框')
                .fontSize(12)
                .fontColor('#B42318')
            }
          }
          .width('100%')

          TextArea({
            text: this.draft,
            placeholder: '先在手机上确认文字，再一次性发送到当前电脑输入框'
          })
            .width('100%')
            .height(110)
            .enabled(this.inputAvailable && !this.protectedFocus)
            .onChange((value: string) => {
              this.draft = value;
            })

          Button('发送文字')
            .width('100%')
            .height(44)
            .enabled(this.inputAvailable && !this.protectedFocus && this.draft.length > 0)
            .onClick(() => {
              void this.sendDraft();
            })
        }
        .width('100%')
        .padding(14)
        .backgroundColor('#F7F8FA')
        .borderRadius(14)

        if (this.operationMessage.length > 0) {
          Text(this.operationMessage)
            .fontSize(13)
            .fontColor('#B42318')
            .width('100%')
        }

        Text('本地局域网直连 · 配对凭据不会发送到云端')
          .fontSize(12)
          .fontColor('#98A2B3')
          .margin({ top: 4, bottom: 18 })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 18 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}
