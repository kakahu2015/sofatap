import { util } from '@kit.ArkTS';

const MAX_LINK_BYTES: number = 2048;
const MAX_NAME_BYTES: number = 256;
const MAX_NAME_SCALARS: number = 80;

function utf8Length(value: string): number {
  return new util.TextEncoder().encodeInto(value).length;
}

function scalarCount(value: string): number {
  let count: number = 0;
  for (let i: number = 0; i < value.length; i++) {
    const unit: number = value.charCodeAt(i);
    if (unit >= 0xD800 && unit <= 0xDBFF && i + 1 < value.length) {
      const next: number = value.charCodeAt(i + 1);
      if (next >= 0xDC00 && next <= 0xDFFF) {
        i++;
      }
    }
    count++;
  }
  return count;
}

function containsUnsafeNameCodePoint(value: string): boolean {
  for (let i: number = 0; i < value.length; i++) {
    const unit: number = value.charCodeAt(i);
    if (unit <= 0x001F || (unit >= 0x007F && unit <= 0x009F) ||
      unit === 0x00AD || unit === 0x061C || unit === 0x070F || unit === 0x180E ||
      (unit >= 0x200B && unit <= 0x200F) || (unit >= 0x202A && unit <= 0x202E) ||
      (unit >= 0x2060 && unit <= 0x206F) || unit === 0xFEFF) {
      return true;
    }
  }
  return false;
}

function decodeQueryPart(value: string): string {
  try {
    // decodeURIComponent deliberately leaves '+' unchanged. Protocol v1 does not use form decoding.
    return decodeURIComponent(value);
  } catch (_) {
    throw new Error('配对链接包含无效转义');
  }
}

function validIpv4(value: string): boolean {
  const parts: string[] = value.split('.');
  if (parts.length !== 4) {
    return false;
  }
  for (const part of parts) {
    if (!/^[0-9]{1,3}$/.test(part)) {
      return false;
    }
    if (part.length > 1 && part.charAt(0) === '0') {
      return false;
    }
    const n: number = Number(part);
    if (!Number.isInteger(n) || n < 0 || n > 255) {
      return false;
    }
  }
  return true;
}

function validDnsName(value: string): boolean {
  if (value.length === 0 || value.length > 253 || !/^[A-Za-z0-9.-]+$/.test(value)) {
    return false;
  }
  const labels: string[] = value.split('.');
  for (const label of labels) {
    if (label.length === 0 || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
      !/^[A-Za-z0-9-]+$/.test(label)) {
      return false;
    }
  }
  return true;
}

function validHost(value: string): boolean {
  // Desktop receivers currently advertise IPv4. DNS names are also accepted for manual links.
  // IPv6 is intentionally deferred in the HarmonyOS MVP to keep the transport parser small.
  return validIpv4(value) || validDnsName(value);
}

function allowedField(key: string): boolean {
  return key === 'host' || key === 'port' || key === 'token' || key === 'fingerprint' ||
    key === 'platform' || key === 'name';
}

export class HostTapPairing {
  readonly host: string;
  readonly port: number;
  readonly token: string;
  readonly fingerprint: string;
  readonly platform: string;
  readonly name: string;
  readonly link: string;

  constructor(host: string, port: number, token: string, fingerprint: string,
    platform: string, name: string, link: string) {
    this.host = host;
    this.port = port;
    this.token = token;
    this.fingerprint = fingerprint;
    this.platform = platform;
    this.name = name;
    this.link = link;
  }

  static parse(raw: string): HostTapPairing {
    const trimmed: string = raw.trim();
    if (trimmed.length === 0 || utf8Length(trimmed) > MAX_LINK_BYTES) {
      throw new Error('配对链接无效');
    }

    const prefix: string = 'sofatap://pair?';
    if (!trimmed.toLowerCase().startsWith(prefix) || trimmed.indexOf('#') >= 0) {
      throw new Error('请粘贴电脑接收器提供的配对链接');
    }

    const query: string = trimmed.substring(prefix.length);
    if (query.length === 0 || query.indexOf('/') >= 0) {
      throw new Error('配对链接无效');
    }

    const parts: string[] = query.split('&');
    if (parts.length < 4 || parts.length > 6) {
      throw new Error('配对字段不完整');
    }

    const fields: Map<string, string> = new Map<string, string>();
    for (const part of parts) {
      const equals: number = part.indexOf('=');
      if (equals <= 0) {
        throw new Error('配对字段无效');
      }
      const key: string = decodeQueryPart(part.substring(0, equals));
      const value: string = decodeQueryPart(part.substring(equals + 1));
      if (!allowedField(key) || fields.has(key)) {
        throw new Error('配对字段重复或未知');
      }
      fields.set(key, value);
    }

    const host: string = fields.get('host') ?? '';
    const portText: string = fields.get('port') ?? '';
    const token: string = fields.get('token') ?? '';
    const fingerprint: string = fields.get('fingerprint') ?? '';

    if (!validHost(host) || !/^[0-9]{1,5}$/.test(portText)) {
      throw new Error('主机地址或端口无效');
    }
    const port: number = Number(portText);
    if (!Number.isInteger(port) || port < 1 || port > 65535 || token.length < 32 || token.length > 256 ||
      !/^[A-Za-z0-9_-]+$/.test(token) || !/^[0-9A-Fa-f]{64}$/.test(fingerprint)) {
      throw new Error('配对凭据无效');
    }

    const platform: string = fields.get('platform') ?? '';
    if (platform.length > 0 && platform !== 'windows' && platform !== 'macos' && platform !== 'linux') {
      throw new Error('电脑平台字段无效');
    }

    let name: string = fields.get('name') ?? '';
    if (name.length > 0) {
      name = name.trim();
      if (name.length === 0 || containsUnsafeNameCodePoint(name) || scalarCount(name) > MAX_NAME_SCALARS ||
        utf8Length(name) > MAX_NAME_BYTES) {
        throw new Error('电脑名称字段无效');
      }
    }

    return new HostTapPairing(host.toLowerCase(), port, token, fingerprint.toLowerCase(), platform, name, trimmed);
  }

  endpointLabel(): string {
    return `${this.host}:${this.port}`;
  }

  displayName(): string {
    return this.name.length > 0 ? this.name : this.host;
  }
}
