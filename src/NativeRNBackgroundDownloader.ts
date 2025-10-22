import type { TurboModule } from 'react-native'

// eslint-disable-next-line @typescript-eslint/no-var-requires, @typescript-eslint/no-require-imports
const NitroModules = require('react-native-nitro-modules')

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

// Use Nitro Modules exclusively
const NativeRNBackgroundDownloader: Spec = NitroModules.createHybridObject('RNBackgroundDownloader')

export default NativeRNBackgroundDownloader
