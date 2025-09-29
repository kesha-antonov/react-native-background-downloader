import { TurboModuleRegistry, type TurboModule } from 'react-native';
import type { DownloadTask } from './index.d';

export interface Spec extends TurboModule {
  checkForExistingDownloads: () => Promise<DownloadTask[]>;
  completeHandler: (id: string) => void;
  download: (options: {
    id: string;
    url: string;
    destination: string;
    headers?: {
      [key: string]: unknown;
    };
    metadata?: string;
    progressInterval?: number;
    progressMinBytes?: number;
    isAllowedOverRoaming?: boolean;
    isAllowedOverMetered?: boolean;
    isNotificationVisible?: boolean;
    notificationTitle?: string;
  }) => void;
  pauseTask?: (configId: string) => void;
  resumeTask?: (configId: string) => void;
  stopTask?: (configId: string) => void;
  addListener?: (eventName: string) => void;
  removeListeners?: (count: number) => void;

  getConstants: () => {
    documents: string;
    TaskRunning: number;
    TaskSuspended: number;
    TaskCanceling: number;
    TaskCompleted: number;
    isMMKVAvailable: boolean;
    storageType: string;
  };
}

const module = TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader');
export const Constants = module?.getConstants();
export default module;
