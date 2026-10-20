import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';

const PREFERENCES_NAME: string = 'hosttap_privacy';
const KEY_AGREED: string = 'agreed';
const KEY_VERSION: string = 'version';

/**
 * 隐私政策的版本号。
 * 政策内容发生实质变化时递增此值，已同意过的用户会重新看到确认弹窗。
 */
export const PRIVACY_VERSION: string = '1';

async function getPreferences(context: common.UIAbilityContext): Promise<preferences.Preferences> {
  return await preferences.getPreferences(context, { name: PREFERENCES_NAME });
}

/**
 * 记录用户对隐私政策的确认状态。
 * 仅保存「是否同意」与「同意的政策版本」，不涉及任何个人信息。
 */
export class PrivacyStore {
  static async isAgreed(context: common.UIAbilityContext): Promise<boolean> {
    try {
      const prefs: preferences.Preferences = await getPreferences(context);
      const agreed: boolean = (await prefs.get(KEY_AGREED, false)) as boolean;
      const version: string = (await prefs.get(KEY_VERSION, '')) as string;
      return agreed === true && version === PRIVACY_VERSION;
    } catch (error) {
      return false;
    }
  }

  static async setAgreed(context: common.UIAbilityContext): Promise<void> {
    const prefs: preferences.Preferences = await getPreferences(context);
    await prefs.put(KEY_AGREED, true);
    await prefs.put(KEY_VERSION, PRIVACY_VERSION);
    await prefs.flush();
  }
}
