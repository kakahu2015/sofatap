import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { HostTapPairing } from './HostTapPairing';
import { PinnedWebSocket, PinnedWebSocketListener } from './PinnedWebSocket';

const MAX_TEXT_UNITS: number = 16_000;
const MAX_WIRE_BYTES: number = 65_536;
const COMMAND_TIMEOUT_MS: number = 8_000;
const MOVE_STALE_MS: number = 120;

interface TextFocusMessage {
  state?: string;
  revision?: number;
}

interface LiveTextWireMessage {
  version?: number;
  session?: string;
  revision?: number;
  text?: string;
  selectionStart?: number;
  selectionLength?: number;
}

export interface LiveTextSnapshot {
  session: string;
  revision: number;
  text: string;
  selectionStart: number;
  selectionLength: number;
}

interface QueuedLiveText {
  text: string;
  selectionStart: number;
  selectionLength: number;
}

interface WireMessage {
  type?: string;
  id?: string;
  ok?: boolean;
  error?: string;
  version?: number;
  name?: string;
  platform?: string;
  dryRun?: boolean;
  inputAvailable?: boolean;
  inputStatus?: string;
  textFocus?: TextFocusMessage;
  liveText?: LiveTextWireMessage;
}

interface ActionFrame {
  id: string;
  type: string;
  dx?: number;
  dy?: number;
  button?: string;
  down?: boolean;
  key?: string;
  modifiers?: string[];
  text?: string;
  session?: string;
  revision?: number;
  selectionStart?: number;
  selectionLength?: number;
}

interface PendingAction {
  type: string;
  timer: number;
  resolve: (result: HostTapActionResult) => void;
}

class HostTapPinnedWebSocketListener implements PinnedWebSocketListener {
  private readonly textHandler: (message: string) => void;
  private readonly closedHandler: (message: string) => void;

  constructor(textHandler: (message: string) => void, closedHandler: (message: string) => void) {
    this.textHandler = textHandler;
    this.closedHandler = closedHandler;
  }

  onText(message: string): void {
    this.textHandler(message);
  }

  onClosed(message: string): void {
    this.closedHandler(message);
  }
}

export interface HostTapActionResult {
  accepted: boolean;
  uncertain: boolean;
  error: string;
  liveText?: LiveTextSnapshot;
}

export interface HostTapState {
  status: string;
  connecting: boolean;
  connected: boolean;
  inputAvailable: boolean;
  protectedFocus: boolean;
  computerName: string;
  liveTextSupported: boolean;
  liveTextActive: boolean;
  liveTextAvailable: boolean;
  liveTextPaused: boolean;
  autoReconnectEnabled: boolean;
}

export interface HostTapStateListener {
  onState(state: HostTapState): void;
}

function utf8Length(value: string): number {
  return new util.TextEncoder().encodeInto(value).length;
}

function bound(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function splitsSurrogate(value: string, index: number): boolean {
  if (index <= 0 || index >= value.length) {
    return false;
  }
  const previous: number = value.charCodeAt(index - 1);
  const current: number = value.charCodeAt(index);
  return previous >= 0xD800 && previous <= 0xDBFF && current >= 0xDC00 && current <= 0xDFFF;
}

function randomUuid(): string {
  const bytes: Uint8Array = cryptoFramework.createRandom().generateRandomSync(16).data;
  bytes[6] = (bytes[6] & 0x0F) | 0x40;
  bytes[8] = (bytes[8] & 0x3F) | 0x80;
  let raw: string = '';
  for (let i: number = 0; i < bytes.length; i++) {
    raw += bytes[i].toString(16).padStart(2, '0');
  }
  return `${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}`;
}

function rejectedWithoutDelivery(error: string): boolean {
  return error.startsWith('input_failed:') || error.startsWith('input_busy:') ||
    error.startsWith('invalid_') || error.startsWith('unsupported_') ||
    error.startsWith('live_text_unsupported:') || error.startsWith('live_text_conflict:') ||
    error.startsWith('live_text_changed:');
}

export class HostTapClient {
  private listener?: HostTapStateListener;
  private transport?: PinnedWebSocket;
  private pairing?: HostTapPairing;
  private pending: Map<string, PendingAction> = new Map<string, PendingAction>();
  private generation: number = 0;
  private pingTimer: number = -1;
  private reconnectTimer: number = -1;
  private retryDelayMs: number = 1_000;
  private foreground: boolean = true;
  private autoReconnectEnabled: boolean = true;
  private reconnectWanted: boolean = false;
  private focusMetadataSupported: boolean = false;

  private connecting: boolean = false;
  private connected: boolean = false;
  private inputAvailable: boolean = false;
  private protectedFocus: boolean = false;
  private computerName: string = '';
  private status: string = '未连接电脑';
  private textFocusState: string = 'unknown';
  private focusRevision: number = -1;
  private liveTextSupported: boolean = false;
  private liveTextPaused: boolean = false;
  private liveTextSession: string = '';
  private liveTextRevision: number = 0;
  private queuedLiveText?: QueuedLiveText;
  private liveSyncTimer: number = -1;
  private liveSyncBusy: boolean = false;
  private liveTextEnding: boolean = false;

  private queuedDX: number = 0;
  private queuedDY: number = 0;
  private queuedScrollX: number = 0;
  private queuedScroll: number = 0;
  private lastMoveAt: number = 0;
  private moveBusy: boolean = false;
  private scrollBusy: boolean = false;
  private moveTimer: number = -1;
  private scrollTimer: number = -1;

  setListener(listener: HostTapStateListener): void {
    this.listener = listener;
    this.publish();
  }

  isConnected(): boolean {
    return this.connected;
  }

  canControl(): boolean {
    return this.connected && this.inputAvailable;
  }

  async connect(target: HostTapPairing): Promise<void> {
    this.reconnectWanted = true;
    this.retryDelayMs = 1_000;
    this.clearReconnectTimer();
    await this.connectInternal(target);
  }

  async disconnect(): Promise<void> {
    this.reconnectWanted = false;
    this.clearReconnectTimer();
    await this.disconnectInternal(true);
  }

  setAutoReconnect(enabled: boolean): void {
    this.autoReconnectEnabled = enabled;
    if (!enabled) {
      this.reconnectWanted = false;
      this.clearReconnectTimer();
    } else if (this.connected && this.pairing !== undefined) {
      this.reconnectWanted = true;
    } else if (this.pairing !== undefined && this.status !== '未连接电脑' && this.foreground) {
      this.reconnectWanted = true;
      this.scheduleReconnect(0);
    }
    this.publish();
  }

  setForeground(active: boolean): void {
    this.foreground = active;
    if (!active) {
      this.clearReconnectTimer();
      if (this.connected || this.connecting) {
        void this.disconnectInternal(false).then(() => {
          this.status = '应用进入后台，连接已暂停';
          this.publish();
        });
      }
      return;
    }
    if (this.reconnectWanted && this.autoReconnectEnabled && this.pairing !== undefined && !this.connected) {
      this.scheduleReconnect(0);
    }
  }

  private async connectInternal(target: HostTapPairing): Promise<void> {
    await this.disconnectInternal(false);
    const current: number = ++this.generation;
    this.pairing = target;
    this.computerName = target.displayName();
    this.connecting = true;
    this.connected = false;
    this.inputAvailable = false;
    this.protectedFocus = false;
    this.textFocusState = 'unknown';
    this.focusRevision = -1;
    this.focusMetadataSupported = false;
    this.liveTextSupported = false;
    this.clearLiveTextSession();
    this.status = '正在验证电脑证书…';
    this.publish();

    let helloResolve: (() => void) | undefined;
    let helloReject: ((reason: Error) => void) | undefined;
    const hello: Promise<void> = new Promise<void>((resolve, reject) => {
      helloResolve = resolve;
      helloReject = (reason: Error) => reject(reason);
    });

    const transport: PinnedWebSocket = new PinnedWebSocket();
    this.transport = transport;
    const wsListener: PinnedWebSocketListener = new HostTapPinnedWebSocketListener(
      (message: string): void => {
        if (current !== this.generation) {
          return;
        }
        this.receive(message, current, helloResolve, helloReject);
      },
      (message: string): void => {
        if (current !== this.generation) {
          return;
        }
        helloReject?.(new Error(message));
        this.connectionLost(message, current);
      }
    );

    try {
      await transport.connect(target, wsListener);
      this.status = '加密连接已建立，正在读取电脑状态…';
      this.publish();
      await this.withTimeout(hello, 10_000, '电脑没有返回有效状态，请更新电脑端接收器');
    } catch (error) {
      if (current !== this.generation) {
        return;
      }
      const message: string = error instanceof Error ? error.message : '无法连接电脑';
      await this.disconnectInternal(false);
      this.status = message;
      this.publish();
      if (this.shouldRetry(message)) {
        this.scheduleReconnect();
      }
      throw new Error(message);
    }
  }

  click(button: string): void {
    if (button !== 'left' && button !== 'right') {
      return;
    }
    this.invalidateLiveText();
    const frame: ActionFrame = { id: randomUuid(), type: 'click', button: button };
    void this.sendActionFrame(frame, false);
  }

  key(key: string, modifiers: string[] = []): void {
    this.invalidateLiveText();
    const frame: ActionFrame = { id: randomUuid(), type: 'key', key: key, modifiers: modifiers };
    void this.sendActionFrame(frame, false);
  }

  button(button: string, down: boolean): void {
    if (button !== 'left' && button !== 'right') {
      return;
    }
    if (down) {
      this.invalidateLiveText();
    }
    const frame: ActionFrame = { id: randomUuid(), type: 'button', button: button, down: down };
    void this.sendActionFrame(frame, false);
  }

  move(dx: number, dy: number): void {
    if (!this.canControl() || !Number.isFinite(dx) || !Number.isFinite(dy)) {
      return;
    }
    const now: number = Date.now();
    if (now - this.lastMoveAt > MOVE_STALE_MS) {
      this.queuedDX = 0;
      this.queuedDY = 0;
    }
    this.queuedDX = bound(this.queuedDX + dx * 1.4, -500, 500);
    this.queuedDY = bound(this.queuedDY + dy * 1.4, -500, 500);
    this.lastMoveAt = now;
    this.scheduleMove(30);
  }

  scroll(dy: number): void {
    if (!this.canControl() || !Number.isFinite(dy)) {
      return;
    }
    this.invalidateLiveText();
    this.queuedScroll = bound(this.queuedScroll + dy, -1200, 1200);
    this.scheduleScroll(25);
  }

  scrollXY(dx: number, dy: number): void {
    if (!this.canControl() || !Number.isFinite(dx) || !Number.isFinite(dy)) {
      return;
    }
    this.invalidateLiveText();
    this.queuedScrollX = bound(this.queuedScrollX + dx, -1200, 1200);
    this.queuedScroll = bound(this.queuedScroll + dy, -1200, 1200);
    this.scheduleScroll(25);
  }

  async sendText(value: string): Promise<HostTapActionResult> {
    if (!this.canControl()) {
      return { accepted: false, uncertain: false, error: '电脑当前无法接收输入' };
    }
    if (value.length === 0) {
      return { accepted: false, uncertain: false, error: '先输入要发送的文字' };
    }
    if (value.length > MAX_TEXT_UNITS) {
      return { accepted: false, uncertain: false, error: '文字超过 16,000 个 UTF-16 单元，请分段发送' };
    }
    if (this.protectedFocus) {
      return { accepted: false, uncertain: false, error: '电脑当前是安全输入框，已阻止发送' };
    }
    this.invalidateLiveText();
    const frame: ActionFrame = { id: randomUuid(), type: 'text', text: value };
    return await this.sendActionFrame(frame, true);
  }

  async beginLiveText(): Promise<HostTapActionResult> {
    if (!this.liveTextSupported) {
      return { accepted: false, uncertain: false, error: '这台电脑不支持实时文字编辑' };
    }
    if (this.textFocusState !== 'editable' || this.protectedFocus) {
      return { accepted: false, uncertain: false, error: '请先在电脑上选中可编辑的普通文字输入框' };
    }
    if (!this.canControl()) {
      return { accepted: false, uncertain: false, error: '电脑当前无法接收输入' };
    }
    this.clearLiveTextSession();
    const frame: ActionFrame = { id: randomUuid(), type: 'textBegin' };
    const result: HostTapActionResult = await this.sendActionFrame(frame, false);
    if (!result.accepted) {
      return result;
    }
    const snapshot: LiveTextSnapshot | undefined = result.liveText;
    if (snapshot === undefined) {
      return { accepted: false, uncertain: false, error: '电脑没有返回实时文字状态' };
    }
    this.liveTextSession = snapshot.session;
    this.liveTextRevision = snapshot.revision;
    this.liveTextEnding = false;
    this.liveTextPaused = false;
    this.publish();
    return { accepted: true, uncertain: false, error: '', liveText: snapshot };
  }

  syncLiveText(value: string, selectionStart: number, selectionLength: number): void {
    if (this.liveTextSession.length === 0 || this.liveTextEnding || !this.canControl() || this.protectedFocus ||
      value.length > MAX_TEXT_UNITS || !Number.isInteger(selectionStart) || !Number.isInteger(selectionLength) ||
      selectionStart < 0 || selectionLength < 0 || selectionStart + selectionLength > value.length ||
      splitsSurrogate(value, selectionStart) || splitsSurrogate(value, selectionStart + selectionLength)) {
      return;
    }
    this.queuedLiveText = { text: value, selectionStart: selectionStart, selectionLength: selectionLength };
    if (this.liveSyncTimer >= 0) {
      clearTimeout(this.liveSyncTimer);
    }
    this.liveSyncTimer = setTimeout(() => {
      this.liveSyncTimer = -1;
      void this.flushLiveText();
    }, 120);
  }

  async endLiveText(): Promise<void> {
    if (this.liveTextEnding) {
      return;
    }
    const session: string = this.liveTextSession;
    if (session.length === 0) {
      this.clearLiveTextSession();
      this.publish();
      return;
    }
    this.liveTextEnding = true;
    if (this.liveSyncTimer >= 0) {
      clearTimeout(this.liveSyncTimer);
      this.liveSyncTimer = -1;
    }
    const deadline: number = Date.now() + COMMAND_TIMEOUT_MS + 1_000;
    while (this.liveTextSession === session && Date.now() < deadline) {
      if (!this.liveSyncBusy && this.queuedLiveText !== undefined) {
        await this.flushLiveText();
        continue;
      }
      if (!this.liveSyncBusy && this.queuedLiveText === undefined) {
        break;
      }
      await new Promise<void>((resolve) => {
        setTimeout(() => resolve(), 20);
      });
    }
    if (this.liveTextSession !== session) {
      return;
    }
    if (this.liveSyncTimer >= 0) {
      clearTimeout(this.liveSyncTimer);
      this.liveSyncTimer = -1;
    }
    this.queuedLiveText = undefined;
    this.liveTextSession = '';
    this.publish();
    const frame: ActionFrame = { id: randomUuid(), type: 'textEnd', session: session };
    await this.sendActionFrame(frame, false);
    this.liveTextRevision = 0;
    this.liveTextEnding = false;
  }

  private receive(raw: string, current: number, helloResolve?: () => void,
    helloReject?: (reason: Error) => void): void {
    if (current !== this.generation || utf8Length(raw) > MAX_WIRE_BYTES) {
      helloReject?.(new Error('电脑返回的数据无效'));
      this.connectionLost('电脑返回的数据无效，已断开连接', current);
      return;
    }

    let message: WireMessage;
    try {
      message = JSON.parse(raw) as WireMessage;
    } catch (_) {
      helloReject?.(new Error('电脑返回的数据不是有效 JSON'));
      this.connectionLost('电脑返回的数据无效，已断开连接', current);
      return;
    }

    if (message.type === 'hello') {
      if (!this.connecting || message.version !== 1 || typeof message.name !== 'string' ||
        typeof message.inputAvailable !== 'boolean') {
        helloReject?.(new Error('电脑端协议不兼容，请更新接收器'));
        this.connectionLost('电脑端协议不兼容，请更新接收器', current);
        return;
      }
      const name: string = message.name ?? '';
      if (utf8Length(name) > 256) {
        helloReject?.(new Error('电脑名称无效'));
        this.connectionLost('电脑名称无效', current);
        return;
      }
      const status: string = message.inputStatus ?? '';
      const dryRun: boolean = message.dryRun === true;
      this.inputAvailable = message.inputAvailable === true && !dryRun && (status.length === 0 || status === 'ready');
      this.textFocusState = this.validFocusState(message.textFocus?.state) ? message.textFocus?.state ?? 'unknown' : 'unknown';
      this.protectedFocus = this.textFocusState === 'protected';
      this.focusMetadataSupported = message.textFocus !== undefined && Number.isInteger(message.textFocus.revision) &&
        (message.textFocus.revision ?? -1) >= 0;
      this.focusRevision = this.focusMetadataSupported ? message.textFocus?.revision ?? -1 : -1;
      this.liveTextSupported = message.liveText?.version === 1;
      this.computerName = name.length > 0 ? name : (this.pairing?.displayName() ?? '电脑');
      this.connecting = false;
      this.connected = true;
      this.retryDelayMs = 1_000;
      this.status = this.inputAvailable ? '已连接' : this.statusMessage(status, dryRun);
      this.publish();
      this.startKeepAlive();
      helloResolve?.();
      return;
    }

    if (message.type !== 'ack' || typeof message.id !== 'string') {
      this.connectionLost('电脑端协议不兼容，请更新接收器', current);
      return;
    }

    const item: PendingAction | undefined = this.pending.get(message.id);
    if (item === undefined) {
      return;
    }
    this.pending.delete(message.id);
    clearTimeout(item.timer);

    if (item.type === 'ping') {
      if (typeof message.inputAvailable === 'boolean') {
        const status: string = message.inputStatus ?? '';
        const dryRun: boolean = message.dryRun === true;
        this.inputAvailable = message.inputAvailable && !dryRun && (status.length === 0 || status === 'ready');
        this.status = this.inputAvailable ? '已连接' : this.statusMessage(status, dryRun);
      }
      if (message.textFocus !== undefined) {
        const nextState: string = this.validFocusState(message.textFocus.state)
          ? message.textFocus.state ?? 'unknown' : 'unknown';
        const nextRevision: number = Number.isInteger(message.textFocus.revision) && (message.textFocus.revision ?? -1) >= 0
          ? message.textFocus.revision ?? -1 : -1;
        if (this.liveTextSession.length > 0 && this.focusMetadataSupported &&
          (nextRevision < 0 || nextRevision !== this.focusRevision || nextState === 'unknown' || nextState === 'protected')) {
          this.invalidateLiveText();
        }
        this.focusMetadataSupported = nextRevision >= 0;
        this.focusRevision = nextRevision;
        this.textFocusState = nextState;
        this.protectedFocus = nextState === 'protected';
      } else if (this.focusMetadataSupported) {
        if (this.liveTextSession.length > 0) {
          this.invalidateLiveText();
        }
        this.focusMetadataSupported = false;
        this.focusRevision = -1;
        this.textFocusState = 'unknown';
        this.protectedFocus = false;
      }
      this.publish();
    }

    if (message.ok === true) {
      const snapshot: LiveTextSnapshot | undefined = this.readLiveTextSnapshot(message.liveText);
      item.resolve({ accepted: true, uncertain: false, error: '', liveText: snapshot });
      return;
    }
    const error: string = typeof message.error === 'string' && message.error.length > 0 ? message.error : '操作未完成';
    const uncertain: boolean = (item.type === 'text' || item.type === 'textSync') && !rejectedWithoutDelivery(error);
    item.resolve({ accepted: false, uncertain: uncertain, error: error });
  }

  private async sendActionFrame(frame: ActionFrame, textSensitive: boolean): Promise<HostTapActionResult> {
    const transport: PinnedWebSocket | undefined = this.transport;
    if (!this.connected || transport === undefined || !transport.isOpen()) {
      return { accepted: false, uncertain: false, error: '尚未连接电脑' };
    }
    if (frame.type !== 'ping' && !this.inputAvailable) {
      return { accepted: false, uncertain: false, error: '电脑当前无法接收输入' };
    }
    if (this.pending.size >= 32) {
      return { accepted: false, uncertain: false, error: '操作过快，请稍后' };
    }

    const wire: string = JSON.stringify(frame);
    if (utf8Length(wire) > MAX_WIRE_BYTES) {
      return { accepted: false, uncertain: false, error: '消息超过 64 KiB，请拆分发送' };
    }

    return await new Promise<HostTapActionResult>((resolve) => {
      const timer: number = setTimeout(() => {
        const item: PendingAction | undefined = this.pending.get(frame.id);
        if (item === undefined) {
          return;
        }
        this.pending.delete(frame.id);
        item.resolve({
          accepted: false,
          uncertain: textSensitive,
          error: textSensitive ? '电脑响应超时，文字可能已输入，请先查看电脑屏幕' : '电脑响应超时'
        });
        const current: number = this.generation;
        this.connectionLost('电脑响应超时，已停止控制', current);
      }, COMMAND_TIMEOUT_MS);
      this.pending.set(frame.id, { type: frame.type, timer: timer, resolve: resolve });
      transport.sendText(wire).catch(() => {
        const item: PendingAction | undefined = this.pending.get(frame.id);
        if (item === undefined) {
          return;
        }
        this.pending.delete(frame.id);
        clearTimeout(item.timer);
        item.resolve({
          accepted: false,
          uncertain: textSensitive,
          error: textSensitive ? '连接中断，文字发送结果未确认' : '连接中断'
        });
        this.connectionLost('电脑连接已中断', this.generation);
      });
    });
  }

  private scheduleMove(delay: number): void {
    if (this.moveTimer >= 0) {
      return;
    }
    this.moveTimer = setTimeout(() => {
      this.moveTimer = -1;
      void this.flushMove();
    }, delay);
  }

  private async flushMove(): Promise<void> {
    if (!this.canControl()) {
      this.queuedDX = 0;
      this.queuedDY = 0;
      return;
    }
    if (Date.now() - this.lastMoveAt > MOVE_STALE_MS) {
      this.queuedDX = 0;
      this.queuedDY = 0;
      return;
    }
    if (this.moveBusy || Math.abs(this.queuedDX) + Math.abs(this.queuedDY) < 0.01) {
      return;
    }
    const dx: number = this.queuedDX;
    const dy: number = this.queuedDY;
    this.queuedDX = 0;
    this.queuedDY = 0;
    this.moveBusy = true;
    const frame: ActionFrame = { id: randomUuid(), type: 'move', dx: dx, dy: dy };
    await this.sendActionFrame(frame, false);
    this.moveBusy = false;
    if (Math.abs(this.queuedDX) + Math.abs(this.queuedDY) > 0.01) {
      this.scheduleMove(20);
    }
  }

  private scheduleScroll(delay: number): void {
    if (this.scrollTimer >= 0 || this.scrollBusy) {
      return;
    }
    this.scrollTimer = setTimeout(() => {
      this.scrollTimer = -1;
      void this.flushScroll();
    }, delay);
  }

  private async flushScroll(): Promise<void> {
    if (!this.canControl()) {
      this.queuedScrollX = 0;
      this.queuedScroll = 0;
      return;
    }
    if (this.scrollBusy || Math.abs(this.queuedScrollX) + Math.abs(this.queuedScroll) < 0.01) {
      return;
    }
    const dx: number = this.queuedScrollX;
    const dy: number = this.queuedScroll;
    this.queuedScrollX = 0;
    this.queuedScroll = 0;
    this.scrollBusy = true;
    const frame: ActionFrame = { id: randomUuid(), type: 'scroll', dx: dx, dy: dy };
    await this.sendActionFrame(frame, false);
    this.scrollBusy = false;
    if (Math.abs(this.queuedScrollX) + Math.abs(this.queuedScroll) > 0.01) {
      this.scheduleScroll(20);
    }
  }

  private async flushLiveText(): Promise<void> {
    if (this.liveSyncBusy || this.liveTextSession.length === 0 || !this.canControl()) {
      return;
    }
    const queued: QueuedLiveText | undefined = this.queuedLiveText;
    if (queued === undefined) {
      return;
    }
    this.queuedLiveText = undefined;
    const session: string = this.liveTextSession;
    const revision: number = this.liveTextRevision + 1;
    this.liveSyncBusy = true;
    const frame: ActionFrame = {
      id: randomUuid(),
      type: 'textSync',
      session: session,
      revision: revision,
      text: queued.text,
      selectionStart: queued.selectionStart,
      selectionLength: queued.selectionLength
    };
    const result: HostTapActionResult = await this.sendActionFrame(frame, true);
    this.liveSyncBusy = false;
    if (this.liveTextSession !== session) {
      if (this.queuedLiveText !== undefined && this.liveTextSession.length > 0 && this.liveSyncTimer < 0) {
        this.liveSyncTimer = setTimeout(() => {
          this.liveSyncTimer = -1;
          void this.flushLiveText();
        }, 0);
      }
      return;
    }
    if (!result.accepted) {
      const error: string = result.uncertain
        ? `${result.error}；实时编辑已停止，请检查电脑上的文字`
        : `实时编辑已停止：${result.error}`;
      this.liveTextPaused = true;
      this.clearLiveTextSession();
      this.sendLiveTextEnd(session);
      this.status = error;
      this.publish();
      return;
    }
    const snapshot: LiveTextSnapshot | undefined = result.liveText;
    if (snapshot === undefined || snapshot.session !== session || snapshot.revision !== revision ||
      snapshot.text !== queued.text || snapshot.selectionStart !== queued.selectionStart ||
      snapshot.selectionLength !== queued.selectionLength) {
      this.liveTextPaused = true;
      this.clearLiveTextSession();
      this.sendLiveTextEnd(session);
      this.status = '电脑文字回读与手机内容不一致，实时编辑已停止';
      this.publish();
      return;
    }
    this.liveTextRevision = snapshot.revision;
    if (this.queuedLiveText !== undefined && this.liveSyncTimer < 0) {
      this.liveSyncTimer = setTimeout(() => {
        this.liveSyncTimer = -1;
        void this.flushLiveText();
      }, 0);
    }
  }

  private validFocusState(value?: string): boolean {
    return value === 'editable' || value === 'other' || value === 'protected' || value === 'unknown';
  }

  private readLiveTextSnapshot(value?: LiveTextWireMessage): LiveTextSnapshot | undefined {
    if (value === undefined || typeof value.session !== 'string' ||
      !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value.session) ||
      typeof value.revision !== 'number' || !Number.isInteger(value.revision) || value.revision < 0 ||
      typeof value.text !== 'string' || value.text.length > MAX_TEXT_UNITS ||
      typeof value.selectionStart !== 'number' || !Number.isInteger(value.selectionStart) ||
      typeof value.selectionLength !== 'number' || !Number.isInteger(value.selectionLength) ||
      value.selectionStart < 0 || value.selectionLength < 0 ||
      value.selectionStart + value.selectionLength > value.text.length ||
      splitsSurrogate(value.text, value.selectionStart) ||
      splitsSurrogate(value.text, value.selectionStart + value.selectionLength)) {
      return undefined;
    }
    return {
      session: value.session,
      revision: value.revision,
      text: value.text,
      selectionStart: value.selectionStart,
      selectionLength: value.selectionLength
    };
  }

  private clearLiveTextSession(): void {
    if (this.liveSyncTimer >= 0) {
      clearTimeout(this.liveSyncTimer);
      this.liveSyncTimer = -1;
    }
    this.queuedLiveText = undefined;
    this.liveTextSession = '';
    this.liveTextRevision = 0;
    this.liveTextEnding = false;
  }

  private invalidateLiveText(): void {
    if (this.liveTextSession.length > 0) {
      const session: string = this.liveTextSession;
      if (this.liveSyncBusy || this.queuedLiveText !== undefined) {
        this.liveTextPaused = true;
      }
      this.clearLiveTextSession();
      this.publish();
      this.sendLiveTextEnd(session);
    }
  }

  private sendLiveTextEnd(session: string): void {
    if (session.length === 0) {
      return;
    }
    const frame: ActionFrame = { id: randomUuid(), type: 'textEnd', session: session };
    void this.sendActionFrame(frame, false);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer >= 0) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = -1;
    }
  }

  private shouldRetry(message: string): boolean {
    if (!this.reconnectWanted || !this.autoReconnectEnabled || !this.foreground || this.pairing === undefined) {
      return false;
    }
    return message.indexOf('证书') < 0 && message.indexOf('认证失败') < 0 &&
      message.indexOf('协议') < 0 && message.indexOf('不兼容') < 0 &&
      message.indexOf('握手') < 0 && message.indexOf('WebSocket') < 0 &&
      message.indexOf('返回了') < 0 && message.indexOf('返回的') < 0 &&
      message.indexOf('配对凭据') < 0 && message.indexOf('电脑名称无效') < 0;
  }

  private scheduleReconnect(delayOverride?: number): void {
    if (!this.shouldRetry(this.status)) {
      return;
    }
    this.clearReconnectTimer();
    const delay: number = delayOverride ?? this.retryDelayMs;
    if (delayOverride === undefined) {
      this.retryDelayMs = Math.min(30_000, this.retryDelayMs * 2);
    }
    if (delay > 0) {
      this.status = `连接中断，将在 ${Math.ceil(delay / 1_000)} 秒后自动重连`;
      this.publish();
    }
    const target: HostTapPairing | undefined = this.pairing;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = -1;
      if (target !== undefined && this.shouldRetry(this.status)) {
        void this.connectInternal(target).catch(() => {});
      }
    }, delay);
  }

  private startKeepAlive(): void {
    this.stopKeepAlive();
    this.pingTimer = setInterval(() => {
      if (!this.connected) {
        return;
      }
      let outstanding: boolean = false;
      this.pending.forEach((item: PendingAction) => {
        if (item.type === 'ping') {
          outstanding = true;
        }
      });
      if (!outstanding) {
        const frame: ActionFrame = { id: randomUuid(), type: 'ping' };
        void this.sendActionFrame(frame, false);
      }
    }, this.focusMetadataSupported ? 750 : 5_000);
  }

  private stopKeepAlive(): void {
    if (this.pingTimer >= 0) {
      clearInterval(this.pingTimer);
      this.pingTimer = -1;
    }
  }

  private async disconnectInternal(publishDisconnected: boolean): Promise<void> {
    this.generation++;
    this.stopKeepAlive();
    if (this.moveTimer >= 0) {
      clearTimeout(this.moveTimer);
      this.moveTimer = -1;
    }
    if (this.scrollTimer >= 0) {
      clearTimeout(this.scrollTimer);
      this.scrollTimer = -1;
    }
    this.queuedDX = 0;
    this.queuedDY = 0;
    this.queuedScrollX = 0;
    this.queuedScroll = 0;
    this.moveBusy = false;
    this.scrollBusy = false;

    this.pending.forEach((item: PendingAction) => {
      clearTimeout(item.timer);
      item.resolve({ accepted: false, uncertain: item.type === 'text' || item.type === 'textSync', error: '连接中断，发送结果未确认' });
    });
    this.pending.clear();
    if (this.liveTextSession.length > 0) {
      this.liveTextPaused = true;
    }
    this.clearLiveTextSession();

    const old: PinnedWebSocket | undefined = this.transport;
    this.transport = undefined;
    if (old !== undefined) {
      await old.close(false);
    }

    this.connecting = false;
    this.connected = false;
    this.inputAvailable = false;
    this.protectedFocus = false;
    this.textFocusState = 'unknown';
    this.focusRevision = -1;
    this.focusMetadataSupported = false;
    this.liveTextSupported = false;
    if (publishDisconnected) {
      this.status = '未连接电脑';
      this.publish();
    }
  }

  private connectionLost(message: string, current: number): void {
    if (current !== this.generation) {
      return;
    }
    this.stopKeepAlive();
    this.connecting = false;
    this.connected = false;
    this.inputAvailable = false;
    this.protectedFocus = false;
    this.textFocusState = 'unknown';
    this.focusRevision = -1;
    this.focusMetadataSupported = false;
    this.liveTextSupported = false;
    this.status = message;
    if (this.liveTextSession.length > 0) {
      this.liveTextPaused = true;
    }
    this.clearLiveTextSession();
    this.pending.forEach((item: PendingAction) => {
      clearTimeout(item.timer);
      item.resolve({ accepted: false, uncertain: item.type === 'text' || item.type === 'textSync', error: '连接中断，发送结果未确认' });
    });
    this.pending.clear();
    this.publish();
    if (this.shouldRetry(message)) {
      this.scheduleReconnect();
    }
  }

  private statusMessage(status: string, dryRun: boolean): string {
    if (dryRun) {
      return '已连接 · 电脑处于演示模式';
    }
    if (status === 'accessibility_required' || status === 'permission_required') {
      return '已连接 · 电脑需要输入权限';
    }
    if (status === 'permission_pending') {
      return '已连接 · 等待电脑授权';
    }
    if (status === 'permission_denied') {
      return '已连接 · 电脑输入权限被拒绝';
    }
    if (status === 'desktop_unavailable') {
      return '已连接 · 当前桌面不可用';
    }
    if (status === 'dependency_missing') {
      return '已连接 · 电脑缺少输入依赖';
    }
    return '已连接 · 电脑暂时无法接收输入';
  }

  private publish(): void {
    this.listener?.onState({
      status: this.status,
      connecting: this.connecting,
      connected: this.connected,
      inputAvailable: this.inputAvailable,
      protectedFocus: this.protectedFocus,
      computerName: this.computerName,
      liveTextSupported: this.liveTextSupported,
      liveTextActive: this.liveTextSession.length > 0,
      liveTextAvailable: this.liveTextSupported && this.textFocusState === 'editable' && this.inputAvailable,
      liveTextPaused: this.liveTextPaused,
      autoReconnectEnabled: this.autoReconnectEnabled
    });
  }

  private async withTimeout(task: Promise<void>, milliseconds: number, message: string): Promise<void> {
    let timer: number = -1;
    const timeout: Promise<void> = new Promise<void>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error(message)), milliseconds);
    });
    try {
      await Promise.race([task, timeout]);
    } finally {
      if (timer >= 0) {
        clearTimeout(timer);
      }
    }
  }
}

export const hostTapClient: HostTapClient = new HostTapClient();
