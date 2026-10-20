import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { HostTapPairing } from './HostTapPairing';

const PREFERENCES_NAME: string = 'hosttap_saved_computers';
const PREFERENCES_KEY: string = 'computers';
const MAX_SAVED_COMPUTERS: number = 20;

export interface SavedComputer {
  id: string;
  host: string;
  port: number;
  fingerprint: string;
  platform: string;
  name: string;
}

function bytes(value: string): Uint8Array {
  return new util.TextEncoder().encodeInto(value);
}

function text(value: Uint8Array): string {
  return new util.TextDecoder().decodeToString(value);
}

function hex(value: Uint8Array): string {
  let result: string = '';
  for (let i: number = 0; i < value.length; i++) {
    result += value[i].toString(16).padStart(2, '0');
  }
  return result;
}

function identity(pairing: HostTapPairing): string {
  const input: Uint8Array = bytes(`${pairing.host.toLowerCase()}|${pairing.port}|${pairing.fingerprint}`);
  const digest = cryptoFramework.createMd('SHA256');
  digest.updateSync({ data: input });
  return hex(digest.digestSync().data);
}

function alias(id: string): string {
  return `hosttap-pairing-${id}`;
}

function assetQuery(aliasValue: string, returnType?: number): asset.AssetMap {
  const query: asset.AssetMap = new Map();
  query.set(asset.Tag.ALIAS, bytes(aliasValue));
  if (returnType !== undefined) {
    query.set(asset.Tag.RETURN_TYPE, returnType);
  }
  return query;
}

async function getPreferences(context: common.UIAbilityContext): Promise<preferences.Preferences> {
  return await preferences.getPreferences(context, { name: PREFERENCES_NAME });
}

async function readSaved(context: common.UIAbilityContext): Promise<SavedComputer[]> {
  const prefs: preferences.Preferences = await getPreferences(context);
  const stored: string = (await prefs.get(PREFERENCES_KEY, '[]')) as string;
  if (typeof stored !== 'string' || stored.length > 32_768) {
    return [];
  }
  try {
    const decoded: SavedComputer[] = JSON.parse(stored) as SavedComputer[];
    if (!Array.isArray(decoded)) {
      return [];
    }
    const valid: SavedComputer[] = [];
    for (const item of decoded) {
      if (typeof item.id === 'string' && /^[0-9a-f]{64}$/.test(item.id) &&
        typeof item.host === 'string' && typeof item.port === 'number' &&
        typeof item.fingerprint === 'string' && typeof item.platform === 'string' &&
        typeof item.name === 'string') {
        valid.push(item);
        if (valid.length >= MAX_SAVED_COMPUTERS) {
          break;
        }
      }
    }
    return valid;
  } catch (_) {
    return [];
  }
}

async function writeSaved(context: common.UIAbilityContext, computers: SavedComputer[]): Promise<void> {
  const prefs: preferences.Preferences = await getPreferences(context);
  await prefs.put(PREFERENCES_KEY, JSON.stringify(computers.slice(0, MAX_SAVED_COMPUTERS)));
  await prefs.flush();
}

export class PairingStore {
  static async list(context: common.UIAbilityContext): Promise<SavedComputer[]> {
    return await readSaved(context);
  }

  static async save(context: common.UIAbilityContext, pairing: HostTapPairing): Promise<SavedComputer[]> {
    const id: string = identity(pairing);
    const current: SavedComputer[] = await readSaved(context);
    const exists: boolean = current.some((item: SavedComputer) => item.id === id);
    if (!exists && current.length >= MAX_SAVED_COMPUTERS) {
      throw new Error(`最多保存 ${MAX_SAVED_COMPUTERS} 台电脑，请先删除一个旧设备`);
    }

    const secretAlias: string = alias(id);
    const lookup: asset.AssetMap = assetQuery(secretAlias, asset.ReturnType.ATTRIBUTES);
    const matches: Array<asset.AssetMap> = await asset.query(lookup);
    const secretUpdate: asset.AssetMap = new Map();
    secretUpdate.set(asset.Tag.SECRET, bytes(pairing.token));
    if (matches.length > 0) {
      await asset.update(assetQuery(secretAlias), secretUpdate);
    } else {
      const attributes: asset.AssetMap = new Map();
      attributes.set(asset.Tag.ALIAS, bytes(secretAlias));
      attributes.set(asset.Tag.SECRET, bytes(pairing.token));
      attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
      await asset.add(attributes);
    }

    const computer: SavedComputer = {
      id: id,
      host: pairing.host,
      port: pairing.port,
      fingerprint: pairing.fingerprint,
      platform: pairing.platform,
      name: pairing.displayName()
    };
    const next: SavedComputer[] = current.filter((item: SavedComputer) => item.id !== id);
    next.unshift(computer);
    await writeSaved(context, next);
    return next;
  }

  static async resolve(context: common.UIAbilityContext, computer: SavedComputer): Promise<HostTapPairing> {
    const query: asset.AssetMap = assetQuery(alias(computer.id), asset.ReturnType.ALL);
    const results: Array<asset.AssetMap> = await asset.query(query);
    if (results.length === 0) {
      throw new Error('这台电脑的安全配对凭据不可用，请重新扫码配对');
    }
    const secret: Object | undefined = results[0].get(asset.Tag.SECRET);
    if (!(secret instanceof Uint8Array)) {
      throw new Error('这台电脑的安全配对凭据无效，请重新扫码配对');
    }
    return HostTapPairing.fromFields(computer.host, computer.port, text(secret),
      computer.fingerprint, computer.platform, computer.name);
  }

  static async remove(context: common.UIAbilityContext, id: string): Promise<SavedComputer[]> {
    if (!/^[0-9a-f]{64}$/.test(id)) {
      throw new Error('保存设备标识无效');
    }
    const secretAlias: string = alias(id);
    const matches: Array<asset.AssetMap> = await asset.query(assetQuery(secretAlias, asset.ReturnType.ATTRIBUTES));
    if (matches.length > 0) {
      await asset.remove(assetQuery(secretAlias));
    }
    const current: SavedComputer[] = await readSaved(context);
    await writeSaved(context, current.filter((item: SavedComputer) => item.id !== id));
    return await readSaved(context);
  }
}
