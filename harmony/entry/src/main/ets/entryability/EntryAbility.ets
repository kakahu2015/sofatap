import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hostTapClient } from '../model/HostTapClient';

export default class EntryAbility extends UIAbility {
  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (error: BusinessError) => {
      if (error.code) {
        console.error(`HostTap failed to load main page: ${error.code} ${error.message}`);
      }
    });
  }

  onForeground(): void {
    hostTapClient.setForeground(true);
  }

  onBackground(): void {
    hostTapClient.setForeground(false);
  }
}
