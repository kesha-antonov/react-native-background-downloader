import { TurboModuleRegistry, NativeModules } from 'react-native'
import type { TurboModule } from 'react-native'
// import type { DownloadTask } from './index.d'

type DownloadTask = {
  id: string
  // state: DownloadTaskState
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  metadata: {
    [key: string]: unknown
  }
  bytesDownloaded: number
  bytesTotal: number
  state:
    | 'PENDING'
    | 'DOWNLOADING'
    | 'PAUSED'
    | 'DONE'
    | 'FAILED'
    | 'STOPPED'

  // begin: (handler: BeginHandler) => DownloadTask
  // progress: (handler: ProgressHandler) => DownloadTask
  // done: (handler: DoneHandler) => DownloadTask
  // error: (handler: ErrorHandler) => DownloadTask

  // _beginHandler: BeginHandler
  // _progressHandler: ProgressHandler
  // _doneHandler: DoneHandler
  // _errorHandler: ErrorHandler

  // pause(): void
  // resume: () => void
  // stop: () => void
}

export interface Spec extends TurboModule {
  checkForExistingDownloads: () => Promise<DownloadTask[]>
  completeHandler: (id: string) => void
  download: (options: {
    id: string
    url: string
    destination: string
    headers?: {
      [key: string]: unknown
    }
    metadata?: string
    progressInterval?: number
    progressMinBytes?: number
    isAllowedOverRoaming?: boolean
    isAllowedOverMetered?: boolean
    isNotificationVisible?: boolean
    notificationTitle?: string
  }) => DownloadTask
  pauseTask?: (configId: string) => void
  resumeTask?: (configId: string) => void
  stopTask?: (configId: string) => void
  addListener?: (eventName: string) => void
  removeListeners?: (count: number) => void
}

// Support both New Architecture (TurboModules) and Old Architecture (Bridge)
// Try TurboModule first, fall back to NativeModules
let NativeRNBackgroundDownloader: Spec
try {
  // New Architecture - TurboModules
  NativeRNBackgroundDownloader = TurboModuleRegistry.getEnforcing<Spec>(
    'RNBackgroundDownloader'
  )
  // Validate that the TurboModule has the expected methods
  if (!NativeRNBackgroundDownloader || typeof NativeRNBackgroundDownloader.checkForExistingDownloads !== 'function')
    throw new Error('TurboModule does not have required methods')
} catch (error) {
  // Old Architecture - Bridge or TurboModule not available
  // Fallback to traditional NativeModules access
  console.warn('[RNBackgroundDownloader] TurboModule not available, falling back to bridge:', error.message || error)
  NativeRNBackgroundDownloader = NativeModules.RNBackgroundDownloader

  // Validate bridge module
  if (!NativeRNBackgroundDownloader)
    console.error('[RNBackgroundDownloader] Neither TurboModule nor Bridge module available')
  else if (typeof NativeRNBackgroundDownloader.checkForExistingDownloads !== 'function')
    console.error('[RNBackgroundDownloader] Bridge module missing required methods')
}

export default NativeRNBackgroundDownloader
