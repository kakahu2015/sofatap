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
}

interface ActionFrame {
  id: string;
  type: string;
  dx?: number;
  dy?: number;
  button?: string;
  key?: string;
  modifiers?: string[];
  text?: string;
}

interface PendingAction {
  type: string;
  timer: number;
  resolve: (result: HostTapActionResult) => void;
}

export interface HostTapActionResult {
  accepted: boolean;
  uncertain: boolean;
  error: string;
}

export interface HostTapState {
  status: string;
  connecting: boolean;
  connected: boolean;
  inputAvailable: boolean;
  protectedFocus: boolean;
  computerName: string;
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

  private connecting: boolean = false;
  private connected: boolean = false;
  private inputAvailable: boolean = false;
  private protectedFocus: boolean = false;
  private computerName: string = '';
  private status: string = '未连接电脑';

  private queuedDX: number = 0;
  private queuedDY: number = 0;
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
    await this.disconnectInternal(false);
    const current: number = ++this.generation;
    this.pairing = target;
    this.computerName = target.displayName();
    this.connecting = true;
    this.connected = false;
    this.inputAvailable = false;
    this.protectedFocus = false;
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
    const wsListener: PinnedWebSocketListener = {
      onText: (message: string): void => {
        if (current !== this.generation) {
          return;
        }
        this.receive(message, current, helloResolve, helloReject);
      },
      onClosed: (message: string): void => {
        if (current !== this.generation) {
          return;
        }
        helloReject?.(new Error(message));
        this.connectionLost(message, current);
      }
    };

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
      throw new Error(message);
    }
  }

  async disconnect(): Promise<void> {
    await this.disconnectInternal(true);
  }

  click(button: string): void {
    if (button !== 'left' && button !== 'right') {
      return;
    }
    const frame: ActionFrame = { id: randomUuid(), type: 'click', button: button };
    void this.sendActionFrame(frame, false);
  }

  key(key: string, modifiers: string[] = []): void {
    const frame: ActionFrame = { id: randomUuid(), type: 'key', key: key, modifiers: modifiers };
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
    const frame: ActionFrame = { id: randomUuid(), type: 'text', text: value };
    return await this.sendActionFrame(frame, true);
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
      this.protectedFocus = message.textFocus?.state === 'protected';
      this.computerName = name.length > 0 ? name : (this.pairing?.displayName() ?? '电脑');
      this.connecting = false;
      this.connected = true;
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
        this.protectedFocus = message.textFocus.state === 'protected';
      }
      this.publish();
    }

    if (message.ok === true) {
      item.resolve({ accepted: true, uncertain: false, error: '' });
      return;
    }
    const error: string = typeof message.error === 'string' && message.error.length > 0 ? message.error : '操作未完成';
    const uncertain: boolean = item.type === 'text' && !rejectedWithoutDelivery(error);
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
      this.queuedScroll = 0;
      return;
    }
    if (this.scrollBusy || Math.abs(this.queuedScroll) < 0.01) {
      return;
    }
    const dy: number = this.queuedScroll;
    this.queuedScroll = 0;
    this.scrollBusy = true;
    const frame: ActionFrame = { id: randomUuid(), type: 'scroll', dx: 0, dy: dy };
    await this.sendActionFrame(frame, false);
    this.scrollBusy = false;
    if (Math.abs(this.queuedScroll) > 0.01) {
      this.scheduleScroll(20);
    }
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
    }, 5_000);
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
    this.queuedScroll = 0;
    this.moveBusy = false;
    this.scrollBusy = false;

    this.pending.forEach((item: PendingAction) => {
      clearTimeout(item.timer);
      item.resolve({ accepted: false, uncertain: item.type === 'text', error: '连接中断，发送结果未确认' });
    });
    this.pending.clear();

    const old: PinnedWebSocket | undefined = this.transport;
    this.transport = undefined;
    if (old !== undefined) {
      await old.close(false);
    }

    this.connecting = false;
    this.connected = false;
    this.inputAvailable = false;
    this.protectedFocus = false;
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
    this.status = message;
    this.pending.forEach((item: PendingAction) => {
      clearTimeout(item.timer);
      item.resolve({ accepted: false, uncertain: item.type === 'text', error: '连接中断，发送结果未确认' });
    });
    this.pending.clear();
    this.publish();
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
      computerName: this.computerName
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
