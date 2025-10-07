import { TurboModuleRegistry, NativeModules } from 'react-native'
import type { TurboModule } from 'react-native'

// Try to import Nitro modules if available
let NitroModules
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires, @typescript-eslint/no-require-imports
  NitroModules = require('react-native-nitro-modules')
} catch {
  // Nitro modules not installed, will fall back to TurboModules or Bridge
  NitroModules = null
}

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

// Support Nitro Modules, New Architecture (TurboModules), and Old Architecture (Bridge)
// Fallback priority: Nitro → TurboModules → Bridge
let NativeRNBackgroundDownloader: Spec
let architectureUsed = 'Unknown'

// Try Nitro Modules first (fastest, most modern)
if (NitroModules)
  try {
    NativeRNBackgroundDownloader = NitroModules.createHybridObject('RNBackgroundDownloader')
    if (NativeRNBackgroundDownloader && typeof NativeRNBackgroundDownloader.checkForExistingDownloads === 'function') {
      architectureUsed = 'Nitro'
      console.log('[RNBackgroundDownloader] Using Nitro Modules architecture')
    } else {
      throw new Error('Nitro module does not have required methods')
    }
  } catch (error) {
    // Nitro module creation failed, will try TurboModule
    console.warn('[RNBackgroundDownloader] Nitro module not available:', error.message || error)
  }

// If Nitro failed, try TurboModule (New Architecture)
if (!NativeRNBackgroundDownloader || architectureUsed === 'Unknown')
  try {
    // New Architecture - TurboModules
    NativeRNBackgroundDownloader = TurboModuleRegistry.getEnforcing<Spec>(
      'RNBackgroundDownloader'
    )
    // Validate that the TurboModule has the expected methods
    if (!NativeRNBackgroundDownloader || typeof NativeRNBackgroundDownloader.checkForExistingDownloads !== 'function')
      throw new Error('TurboModule does not have required methods')

    architectureUsed = 'TurboModule'
    console.log('[RNBackgroundDownloader] Using TurboModule architecture')
  } catch (error) {
    // Old Architecture - Bridge or TurboModule not available
    // Fallback to traditional NativeModules access
    console.warn('[RNBackgroundDownloader] TurboModule not available, falling back to bridge:', error.message || error)
    NativeRNBackgroundDownloader = NativeModules.RNBackgroundDownloader

    // Validate bridge module
    if (!NativeRNBackgroundDownloader) { console.error('[RNBackgroundDownloader] Neither Nitro, TurboModule, nor Bridge module available') } else if (typeof NativeRNBackgroundDownloader.checkForExistingDownloads !== 'function') { console.error('[RNBackgroundDownloader] Bridge module missing required methods') } else {
      architectureUsed = 'Bridge'
      console.log('[RNBackgroundDownloader] Using Bridge architecture')
    }
  }

export default NativeRNBackgroundDownloader
export { architectureUsed }
