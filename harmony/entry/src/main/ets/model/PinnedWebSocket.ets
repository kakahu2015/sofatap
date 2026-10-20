import { socket } from '@kit.NetworkKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { cert } from '@kit.DeviceCertificateKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { HostTapPairing } from './HostTapPairing';

const WS_GUID: string = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_FRAME_BYTES: number = 65_536;
const MAX_HANDSHAKE_BYTES: number = 16_384;

export interface PinnedWebSocketListener {
  onText(message: string): void;
  onClosed(message: string): void;
}

function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array {
  const result: Uint8Array = new Uint8Array(a.length + b.length);
  result.set(a, 0);
  result.set(b, a.length);
  return result;
}

function hex(bytes: Uint8Array): string {
  let result: string = '';
  for (let i: number = 0; i < bytes.length; i++) {
    result += bytes[i].toString(16).padStart(2, '0');
  }
  return result;
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff: number = 0;
  for (let i: number = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function sha256(data: Uint8Array): Uint8Array {
  const md = cryptoFramework.createMd('SHA256');
  md.updateSync({ data: data });
  return md.digestSync().data;
}

function sha1ForWebSocket(data: Uint8Array): Uint8Array {
  // RFC 6455 requires SHA-1 for Sec-WebSocket-Accept. It is not used as a trust primitive here;
  // server identity is authenticated separately by the SHA-256 leaf certificate fingerprint.
  const md = cryptoFramework.createMd('SHA1');
  md.updateSync({ data: data });
  return md.digestSync().data;
}

function derFromCertificate(raw: cert.EncodingBlob): Uint8Array {
  if (raw.encodingFormat === cert.EncodingFormat.FORMAT_DER) {
    return raw.data;
  }
  if (raw.encodingFormat !== cert.EncodingFormat.FORMAT_PEM) {
    throw new Error('电脑返回了不支持的证书格式');
  }
  const text: string = new util.TextDecoder().decodeToString(raw.data);
  const body: string = text
    .replace('-----BEGIN CERTIFICATE-----', '')
    .replace('-----END CERTIFICATE-----', '')
    .replace(/\s/g, '');
  if (body.length === 0) {
    throw new Error('电脑证书为空');
  }
  return new util.Base64Helper().decodeSync(body);
}

function findHeaderEnd(bytes: Uint8Array): number {
  for (let i: number = 0; i + 3 < bytes.length; i++) {
    if (bytes[i] === 13 && bytes[i + 1] === 10 && bytes[i + 2] === 13 && bytes[i + 3] === 10) {
      return i + 4;
    }
  }
  return -1;
}

function exactArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy: Uint8Array = new Uint8Array(bytes.length);
  copy.set(bytes);
  return copy.buffer;
}

export class PinnedWebSocket {
  private tls?: socket.TLSSocket;
  private listener?: PinnedWebSocketListener;
  private receiveBuffer: Uint8Array = new Uint8Array(0);
  private handshakeComplete: boolean = false;
  private handshakeKey: string = '';
  private handshakeResolve?: () => void;
  private handshakeReject?: (reason: Error) => void;
  private closed: boolean = false;
  private fragmentedOpcode: number = 0;
  private fragmentedPayload: Uint8Array = new Uint8Array(0);
  private sendQueue: Promise<void> = Promise.resolve();

  async connect(pairing: HostTapPairing, listener: PinnedWebSocketListener): Promise<void> {
    await this.close(false);
    this.listener = listener;
    this.receiveBuffer = new Uint8Array(0);
    this.fragmentedOpcode = 0;
    this.fragmentedPayload = new Uint8Array(0);
    this.handshakeComplete = false;
    this.closed = false;

    const tls: socket.TLSSocket = socket.constructTLSSocketInstance();
    this.tls = tls;

    tls.on('message', (info: socket.SocketMessageInfo) => {
      this.handleIncoming(new Uint8Array(info.message));
    });
    tls.on('error', (error: BusinessError) => {
      this.fail(`安全连接失败 (${error.code})`);
    });
    tls.on('close', () => {
      this.fail('电脑连接已关闭');
    });

    try {
      await tls.bind({ address: '0.0.0.0', port: 0 });
      const options: socket.TLSConnectOptions = {
        address: { address: pairing.host, port: pairing.port },
        secureOptions: {},
        // No credential is sent before the scanned SHA-256 pin is checked below.
        skipRemoteValidation: true
      };
      await tls.connect(options);

      const remote: socket.X509CertRawData = await tls.getRemoteCertificate();
      const der: Uint8Array = derFromCertificate(remote);
      const actualFingerprint: string = hex(sha256(der));
      if (!constantTimeEqual(actualFingerprint, pairing.fingerprint)) {
        throw new Error('电脑证书指纹与配对链接不一致');
      }

      const randomKey: Uint8Array = cryptoFramework.createRandom().generateRandomSync(16).data;
      this.handshakeKey = new util.Base64Helper().encodeToStringSync(randomKey);
      const hostHeader: string = pairing.port === 443 ? pairing.host : `${pairing.host}:${pairing.port}`;
      const request: string =
        `GET /control HTTP/1.1\r\n` +
        `Host: ${hostHeader}\r\n` +
        `Upgrade: websocket\r\n` +
        `Connection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${this.handshakeKey}\r\n` +
        `Sec-WebSocket-Version: 13\r\n` +
        `Authorization: Bearer ${pairing.token}\r\n` +
        `\r\n`;

      const handshake: Promise<void> = new Promise<void>((resolve, reject) => {
        this.handshakeResolve = resolve;
        this.handshakeReject = (reason: Error) => reject(reason);
      });
      await tls.send(request);
      await this.withTimeout(handshake, 10_000, '电脑没有完成 WebSocket 握手');
    } catch (error) {
      const message: string = error instanceof Error ? error.message : '无法连接电脑';
      await this.close(false);
      throw new Error(message);
    }
  }

  isOpen(): boolean {
    return this.handshakeComplete && !this.closed && this.tls !== undefined;
  }

  async sendText(message: string): Promise<void> {
    if (!this.isOpen()) {
      throw new Error('尚未连接电脑');
    }
    const payload: Uint8Array = new util.TextEncoder().encodeInto(message);
    if (payload.length > MAX_FRAME_BYTES) {
      throw new Error('消息超过传输限制');
    }
    await this.sendFrame(0x1, payload);
  }

  async close(notify: boolean = true): Promise<void> {
    const old: socket.TLSSocket | undefined = this.tls;
    const wasOpen: boolean = this.isOpen();
    this.tls = undefined;
    this.closed = true;
    this.handshakeComplete = false;
    this.handshakeResolve = undefined;
    this.handshakeReject = undefined;
    this.receiveBuffer = new Uint8Array(0);
    this.fragmentedOpcode = 0;
    this.fragmentedPayload = new Uint8Array(0);
    if (old !== undefined) {
      try {
        if (wasOpen) {
          await this.sendFrameOn(old, 0x8, new Uint8Array(0));
        }
      } catch (_) {
      }
      try {
        await old.close();
      } catch (_) {
      }
    }
    if (notify && wasOpen) {
      this.listener?.onClosed('已断开连接');
    }
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

  private handleIncoming(chunk: Uint8Array): void {
    if (this.closed) {
      return;
    }
    this.receiveBuffer = concatBytes(this.receiveBuffer, chunk);
    if (!this.handshakeComplete) {
      if (this.receiveBuffer.length > MAX_HANDSHAKE_BYTES) {
        this.fail('电脑返回的握手数据过大');
        return;
      }
      const end: number = findHeaderEnd(this.receiveBuffer);
      if (end < 0) {
        return;
      }
      const headerBytes: Uint8Array = this.receiveBuffer.slice(0, end);
      const remainder: Uint8Array = this.receiveBuffer.slice(end);
      this.receiveBuffer = remainder;
      try {
        this.verifyHandshake(new util.TextDecoder().decodeToString(headerBytes));
        this.handshakeComplete = true;
        const resolve: (() => void) | undefined = this.handshakeResolve;
        this.handshakeResolve = undefined;
        this.handshakeReject = undefined;
        resolve?.();
      } catch (error) {
        const message: string = error instanceof Error ? error.message : 'WebSocket 握手无效';
        this.rejectHandshake(message);
        return;
      }
    }
    this.parseFrames();
  }

  private verifyHandshake(header: string): void {
    const lines: string[] = header.split('\r\n');
    if (lines.length === 0 || !/^HTTP\/1\.[01] 101(?: |$)/.test(lines[0])) {
      throw new Error('电脑拒绝了控制连接');
    }
    const headers: Map<string, string> = new Map<string, string>();
    for (let i: number = 1; i < lines.length; i++) {
      if (lines[i].length === 0) {
        continue;
      }
      const colon: number = lines[i].indexOf(':');
      if (colon <= 0) {
        throw new Error('电脑返回的 WebSocket 头无效');
      }
      const key: string = lines[i].substring(0, colon).trim().toLowerCase();
      const value: string = lines[i].substring(colon + 1).trim();
      if (headers.has(key)) {
        throw new Error('电脑返回了重复的 WebSocket 头');
      }
      headers.set(key, value);
    }

    const upgrade: string = (headers.get('upgrade') ?? '').toLowerCase();
    const connection: string = (headers.get('connection') ?? '').toLowerCase();
    const actualAccept: string = headers.get('sec-websocket-accept') ?? '';
    const acceptInput: Uint8Array = new util.TextEncoder().encodeInto(this.handshakeKey + WS_GUID);
    const expectedAccept: string = new util.Base64Helper().encodeToStringSync(sha1ForWebSocket(acceptInput));
    if (upgrade !== 'websocket' || connection.split(',').map((item: string) => item.trim()).indexOf('upgrade') < 0 ||
      actualAccept !== expectedAccept) {
      throw new Error('电脑返回的 WebSocket 握手校验失败');
    }
  }

  private parseFrames(): void {
    while (true) {
      if (this.receiveBuffer.length < 2) {
        return;
      }
      const first: number = this.receiveBuffer[0];
      const second: number = this.receiveBuffer[1];
      const fin: boolean = (first & 0x80) !== 0;
      const opcode: number = first & 0x0F;
      if ((first & 0x70) !== 0 || (second & 0x80) !== 0) {
        this.fail('电脑返回了不支持的 WebSocket 帧');
        return;
      }

      let offset: number = 2;
      let length: number = second & 0x7F;
      if (length === 126) {
        if (this.receiveBuffer.length < 4) {
          return;
        }
        length = (this.receiveBuffer[2] << 8) | this.receiveBuffer[3];
        offset = 4;
      } else if (length === 127) {
        if (this.receiveBuffer.length < 10) {
          return;
        }
        for (let i: number = 2; i < 6; i++) {
          if (this.receiveBuffer[i] !== 0) {
            this.fail('电脑返回的 WebSocket 帧过大');
            return;
          }
        }
        length = this.receiveBuffer[6] * 0x1000000 + this.receiveBuffer[7] * 0x10000 +
          this.receiveBuffer[8] * 0x100 + this.receiveBuffer[9];
        offset = 10;
      }

      if (length > MAX_FRAME_BYTES || this.receiveBuffer.length < offset + length) {
        if (length > MAX_FRAME_BYTES) {
          this.fail('电脑返回的 WebSocket 帧超过 64 KiB');
        }
        return;
      }

      const payload: Uint8Array = this.receiveBuffer.slice(offset, offset + length);
      this.receiveBuffer = this.receiveBuffer.slice(offset + length);

      if (opcode >= 0x8) {
        if (!fin || payload.length > 125) {
          this.fail('电脑返回了无效控制帧');
          return;
        }
        if (opcode === 0x8) {
          this.fail('电脑连接已关闭');
          return;
        }
        if (opcode === 0x9) {
          void this.sendFrame(0xA, payload);
          continue;
        }
        if (opcode === 0xA) {
          continue;
        }
        this.fail('电脑返回了未知控制帧');
        return;
      }

      if (opcode === 0x1) {
        if (this.fragmentedOpcode !== 0) {
          this.fail('电脑返回了重叠的分片消息');
          return;
        }
        if (fin) {
          this.listener?.onText(new util.TextDecoder().decodeToString(payload));
        } else {
          this.fragmentedOpcode = opcode;
          this.fragmentedPayload = payload;
        }
        continue;
      }

      if (opcode === 0x0) {
        if (this.fragmentedOpcode !== 0x1) {
          this.fail('电脑返回了孤立的续帧');
          return;
        }
        this.fragmentedPayload = concatBytes(this.fragmentedPayload, payload);
        if (this.fragmentedPayload.length > MAX_FRAME_BYTES) {
          this.fail('电脑返回的分片消息超过 64 KiB');
          return;
        }
        if (fin) {
          const complete: Uint8Array = this.fragmentedPayload;
          this.fragmentedOpcode = 0;
          this.fragmentedPayload = new Uint8Array(0);
          this.listener?.onText(new util.TextDecoder().decodeToString(complete));
        }
        continue;
      }

      this.fail('电脑返回了不支持的二进制消息');
      return;
    }
  }

  private async sendFrame(opcode: number, payload: Uint8Array): Promise<void> {
    const active: socket.TLSSocket | undefined = this.tls;
    if (active === undefined || this.closed) {
      throw new Error('连接已关闭');
    }
    const task: Promise<void> = this.sendQueue.then(async () => {
      await this.sendFrameOn(active, opcode, payload);
    });
    this.sendQueue = task.catch(() => {});
    await task;
  }

  private async sendFrameOn(active: socket.TLSSocket, opcode: number, payload: Uint8Array): Promise<void> {
    const mask: Uint8Array = cryptoFramework.createRandom().generateRandomSync(4).data;
    let headerLength: number = 2 + 4;
    if (payload.length >= 126 && payload.length <= 0xFFFF) {
      headerLength += 2;
    } else if (payload.length > 0xFFFF) {
      headerLength += 8;
    }
    const frame: Uint8Array = new Uint8Array(headerLength + payload.length);
    frame[0] = 0x80 | (opcode & 0x0F);
    let offset: number = 2;
    if (payload.length < 126) {
      frame[1] = 0x80 | payload.length;
    } else if (payload.length <= 0xFFFF) {
      frame[1] = 0x80 | 126;
      frame[2] = (payload.length >>> 8) & 0xFF;
      frame[3] = payload.length & 0xFF;
      offset = 4;
    } else {
      frame[1] = 0x80 | 127;
      for (let i: number = 0; i < 4; i++) {
        frame[2 + i] = 0;
      }
      frame[6] = (payload.length >>> 24) & 0xFF;
      frame[7] = (payload.length >>> 16) & 0xFF;
      frame[8] = (payload.length >>> 8) & 0xFF;
      frame[9] = payload.length & 0xFF;
      offset = 10;
    }
    frame.set(mask, offset);
    offset += 4;
    for (let i: number = 0; i < payload.length; i++) {
      frame[offset + i] = payload[i] ^ mask[i & 3];
    }
    await active.send(exactArrayBuffer(frame));
  }

  private rejectHandshake(message: string): void {
    const reject: ((reason: Error) => void) | undefined = this.handshakeReject;
    this.handshakeResolve = undefined;
    this.handshakeReject = undefined;
    reject?.(new Error(message));
    this.fail(message);
  }

  private fail(message: string): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.handshakeComplete = false;
    const reject: ((reason: Error) => void) | undefined = this.handshakeReject;
    this.handshakeResolve = undefined;
    this.handshakeReject = undefined;
    reject?.(new Error(message));
    const old: socket.TLSSocket | undefined = this.tls;
    this.tls = undefined;
    if (old !== undefined) {
      void old.close().catch(() => {});
    }
    this.listener?.onClosed(message);
  }
}
