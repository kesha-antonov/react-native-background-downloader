import type { TurboModule } from 'react-native'
import { TurboModuleRegistry } from 'react-native'

// UnsafeObject is used for dynamic key-value objects that codegen doesn't support
type UnsafeObject = { [key: string]: string }

export interface Spec extends TurboModule {
  // Constants exported to JavaScript
  getConstants(): {
    documents: string
    TaskRunning: number
    TaskSuspended: number
    TaskCanceling: number
    TaskCompleted: number
    isMMKVAvailable?: boolean
    storageType?: string
  }

  // Methods
  download(options: {
    id: string
    url: string
    destination: string
    headers?: UnsafeObject
    metadata?: string
    progressInterval?: number
    progressMinBytes?: number
    isAllowedOverRoaming: boolean
    isAllowedOverMetered: boolean
    isNotificationVisible: boolean
    notificationTitle?: string
    maxRedirects?: number
  }): void

  pauseTask(id: string): void
  resumeTask(id: string): void
  stopTask(id: string): void

  completeHandler(jobId: string): Promise<void>

  getExistingDownloadTasks(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesDownloaded: number
    bytesTotal: number
    errorCode?: number | null
  }>>

  // Event emitter methods
  addListener(eventName: string): void
  removeListeners(count: number): void
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader')
