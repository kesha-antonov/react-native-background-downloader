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
}

// Support both New Architecture (TurboModules) and Old Architecture (Bridge)
// Try TurboModule first, fall back to NativeModules
let NativeRNBackgroundDownloader: Spec
try {
  // New Architecture - TurboModules
  NativeRNBackgroundDownloader = TurboModuleRegistry.getEnforcing<Spec>(
    'RNBackgroundDownloader'
  )
} catch {
  // Old Architecture - Bridge
  // Fallback to traditional NativeModules access
  NativeRNBackgroundDownloader = NativeModules.RNBackgroundDownloader
}

export default NativeRNBackgroundDownloader
