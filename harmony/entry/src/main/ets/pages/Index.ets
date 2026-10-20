import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { HostTapPairing } from '../model/HostTapPairing';
import { HostTapActionResult, HostTapClient, HostTapState, HostTapStateListener, LiveTextSnapshot, hostTapClient } from '../model/HostTapClient';
import { PairingStore, SavedComputer } from '../model/PairingStore';

class IndexHostTapStateListener implements HostTapStateListener {
  private readonly stateHandler: (state: HostTapState) => void;

  constructor(stateHandler: (state: HostTapState) => void) {
    this.stateHandler = stateHandler;
  }

  onState(state: HostTapState): void {
    this.stateHandler(state);
  }
}

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
  @State private liveTextDraft: string = '';
  @State private liveSelectionStart: number = 0;
  @State private liveSelectionLength: number = 0;
  @State private operationMessage: string = '';
  @State private savedComputers: SavedComputer[] = [];
  @State private liveTextSupported: boolean = false;
  @State private liveTextActive: boolean = false;
  @State private liveTextAvailable: boolean = false;
  @State private liveTextPaused: boolean = false;
  @State private autoReconnectEnabled: boolean = true;
  @State private dragStarted: boolean = false;

  private readonly client: HostTapClient = hostTapClient;
  private appContext?: common.UIAbilityContext;
  private lastPanX: number = 0;
  private lastPanY: number = 0;
  private lastScrollY: number = 0;

  aboutToAppear(): void {
    this.appContext = getContext(this) as common.UIAbilityContext;
    const listener: HostTapStateListener = new IndexHostTapStateListener((state: HostTapState): void => {
        this.status = state.status;
        this.computerName = state.computerName;
        this.connecting = state.connecting;
        this.connected = state.connected;
        this.inputAvailable = state.inputAvailable;
        this.protectedFocus = state.protectedFocus;
        this.liveTextSupported = state.liveTextSupported;
        this.liveTextActive = state.liveTextActive;
        this.liveTextAvailable = state.liveTextAvailable;
        this.liveTextPaused = state.liveTextPaused;
        this.autoReconnectEnabled = state.autoReconnectEnabled;
      });
    this.client.setListener(listener);
    this.client.setForeground(true);
    void this.refreshSavedComputers();
  }

  private async refreshSavedComputers(): Promise<void> {
    const context: common.UIAbilityContext | undefined = this.appContext;
    if (context === undefined) {
      return;
    }
    try {
      this.savedComputers = await PairingStore.list(context);
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '无法读取已保存的电脑';
    }
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

  private async scanPairingQr(): Promise<void> {
    this.operationMessage = '';
    const context: common.UIAbilityContext | undefined = this.appContext;
    if (context === undefined) {
      this.operationMessage = '扫码页面尚未准备好';
      return;
    }
    try {
      const permission = await abilityAccessCtrl.createAtManager()
        .requestPermissionsFromUser(context, ['ohos.permission.CAMERA']);
      if (permission.authResults.length === 0 || permission.authResults[0] !== 0) {
        this.operationMessage = '需要相机权限才能扫描电脑配对二维码';
        return;
      }
      const options: scanBarcode.ScanOptions = {
        scanTypes: [scanCore.ScanType.QR_CODE],
        enableAlbum: false
      };
      const result: scanBarcode.ScanResult = await new Promise<scanBarcode.ScanResult>((resolve, reject) => {
        scanBarcode.startScanForResult(context, options, (error: BusinessError, scanResult: scanBarcode.ScanResult): void => {
          if (error) {
            reject(error);
            return;
          }
          resolve(scanResult);
        });
      });
      if (typeof result.originalValue !== 'string' || result.originalValue.length === 0) {
        this.operationMessage = '二维码中没有配对链接';
        return;
      }
      HostTapPairing.parse(result.originalValue);
      this.pairingLink = result.originalValue;
      this.operationMessage = '已读取配对二维码，请连接或保存电脑';
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '二维码扫描失败';
    }
  }

  private async saveCurrentComputer(): Promise<void> {
    this.operationMessage = '';
    const context: common.UIAbilityContext | undefined = this.appContext;
    if (context === undefined) {
      this.operationMessage = '设备存储尚未准备好';
      return;
    }
    try {
      const pairing: HostTapPairing = HostTapPairing.parse(this.pairingLink);
      this.savedComputers = await PairingStore.save(context, pairing);
      this.operationMessage = `已保存 ${pairing.displayName()}`;
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '无法保存电脑';
    }
  }

  private async connectSavedComputer(computer: SavedComputer): Promise<void> {
    this.operationMessage = '';
    const context: common.UIAbilityContext | undefined = this.appContext;
    if (context === undefined) {
      this.operationMessage = '设备存储尚未准备好';
      return;
    }
    try {
      const pairing: HostTapPairing = await PairingStore.resolve(context, computer);
      this.pairingLink = pairing.link;
      await this.client.connect(pairing);
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '无法连接已保存的电脑';
    }
  }

  private async removeSavedComputer(computer: SavedComputer): Promise<void> {
    const context: common.UIAbilityContext | undefined = this.appContext;
    if (context === undefined) {
      return;
    }
    try {
      this.savedComputers = await PairingStore.remove(context, computer.id);
      this.operationMessage = `已移除 ${computer.name}`;
    } catch (error) {
      this.operationMessage = error instanceof Error ? error.message : '无法移除已保存的电脑';
    }
  }

  private async toggleLiveText(): Promise<void> {
    this.operationMessage = '';
    if (this.liveTextActive) {
      await this.client.endLiveText();
      return;
    }
    const result: HostTapActionResult = await this.client.beginLiveText();
    const snapshot: LiveTextSnapshot | undefined = result.liveText;
    if (!result.accepted || snapshot === undefined) {
      this.operationMessage = result.error;
      return;
    }
    this.liveTextDraft = snapshot.text;
    this.liveSelectionStart = snapshot.selectionStart;
    this.liveSelectionLength = snapshot.selectionLength;
  }

  private onLiveTextSelection(start: number, end: number): void {
    this.liveSelectionStart = Math.min(start, end);
    this.liveSelectionLength = Math.abs(end - start);
  }

  private onLiveTextChange(value: string): void {
    this.liveTextDraft = value;
    this.client.syncLiveText(value, this.liveSelectionStart, this.liveSelectionLength);
  }

  private toggleAutoReconnect(): void {
    this.client.setAutoReconnect(!this.autoReconnectEnabled);
  }

  build() {
    Scroll() {
      Column({ space: 14 }) {
        Row() {
          Column({ space: 2 }) {
            Text('HostTap')
              .fontSize(28)
              .fontWeight(FontWeight.Bold)
            Text('HarmonyOS')
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
          Button('拍照扫描电脑二维码')
            .width('100%')
            .height(48)
            .enabled(!this.connected && !this.connecting)
            .onClick(() => {
              void this.scanPairingQr();
            })
          Button(this.connecting ? '连接中…' : (this.connected ? '断开连接' : '连接电脑'))
            .width('100%')
            .height(44)
            .enabled(!this.connecting && (this.connected || this.pairingLink.length > 0))
            .onClick(() => {
              void this.toggleConnection();
            })
          Row({ space: 8 }) {
            Button('保存电脑')
              .layoutWeight(1)
              .enabled(!this.connected && !this.connecting && this.pairingLink.length > 0)
              .onClick(() => {
                void this.saveCurrentComputer();
              })
            Button(this.autoReconnectEnabled ? '自动重连：开' : '自动重连：关')
              .layoutWeight(1)
              .onClick(() => this.toggleAutoReconnect())
          }
          .width('100%')
          if (this.savedComputers.length > 0) {
            Text('已保存的电脑')
              .fontSize(14)
              .fontWeight(FontWeight.Medium)
              .width('100%')
            ForEach(this.savedComputers, (computer: SavedComputer) => {
              Row({ space: 8 }) {
                Column({ space: 2 }) {
                  Text(computer.name || computer.host)
                    .fontSize(14)
                    .maxLines(1)
                  Text(`${computer.host.indexOf(':') >= 0 ? `[${computer.host}]` : computer.host}:${computer.port}`)
                    .fontSize(11)
                    .fontColor('#667085')
                    .maxLines(1)
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                Button('连接')
                  .enabled(!this.connecting)
                  .onClick(() => {
                    void this.connectSavedComputer(computer);
                  })
                Button('删除')
                  .onClick(() => {
                    void this.removeSavedComputer(computer);
                  })
              }
              .width('100%')
              .padding({ top: 6, bottom: 6 })
            }, (computer: SavedComputer) => computer.id)
          }
          Text(this.pairingLink.length > 0 ? '已扫描到配对码；凭据仅在本机处理。' : '请用相机扫描电脑端显示的配对二维码。')
            .fontSize(12)
            .fontColor('#667085')
            .width('100%')
        }
        .width('100%')
        .padding(14)
        .backgroundColor('#F7F8FA')
        .borderRadius(14)

        Column({ space: 10 }) {
          Text(this.inputAvailable ? '触控区 · 单指移动 · 双指滚动 · 双指轻点右键' : '触控区 · 连接并就绪后可用')
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
        .gesture(
          PanGesture({ fingers: 2, direction: PanDirection.All, distance: 2 })
            .onActionStart(() => {
              this.lastScrollY = 0;
            })
            .onActionUpdate((event: GestureEvent) => {
              const deltaY: number = event.offsetY - this.lastScrollY;
              this.lastScrollY = event.offsetY;
              this.client.scrollXY(0, -deltaY * 1.4);
            })
            .onActionEnd(() => {
              this.lastScrollY = 0;
            })
        )
        .gesture(
          TapGesture({ count: 1, fingers: 2 })
            .onAction(() => this.client.click('right'))
        )
        .gesture(
          LongPressGesture({ duration: 500 })
            .onAction(() => {
              if (this.inputAvailable) {
                this.dragStarted = true;
                this.client.button('left', true);
              }
            })
            .onActionEnd(() => {
              if (this.dragStarted) {
                this.client.button('left', false);
                this.dragStarted = false;
              }
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

          if (this.liveTextSupported || this.liveTextPaused) {
            if (this.liveTextSupported) {
              Button(this.liveTextActive ? '结束实时编辑' :
                (this.liveTextPaused ? '放弃手机草稿并重读电脑' : '实时编辑当前输入框'))
                .width('100%')
                .height(44)
                .enabled(this.liveTextActive || this.liveTextAvailable)
                .onClick(() => {
                  void this.toggleLiveText();
                })
            }
            if (this.liveTextActive || this.liveTextPaused) {
              Text(this.liveTextPaused
                ? '实时编辑已暂停；手机草稿仅保留在本机，请先核对电脑文字，不会自动重发。'
                : '实时编辑 · 内容来自电脑当前输入框，仅保留在本机内存')
                .fontSize(12)
                .fontColor('#667085')
                .width('100%')
              TextArea({
                text: this.liveTextDraft,
                placeholder: '电脑当前输入框为空'
              })
                .width('100%')
                .height(150)
                .enabled(this.liveTextActive && this.inputAvailable)
                .onTextSelectionChange((start: number, end: number) => {
                  this.onLiveTextSelection(start, end);
                })
                .onChange((value: string) => {
                  this.onLiveTextChange(value);
                })
            }
          }
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
