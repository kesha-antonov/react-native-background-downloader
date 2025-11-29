import type { TurboModule } from 'react-native'
import { TurboModuleRegistry } from 'react-native'
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypes'

// UnsafeObject is used for dynamic key-value objects that codegen doesn't support
type UnsafeObject = { [key: string]: string }

// Event payload types for codegen
export type DownloadBeginEvent = {
  id: string
  expectedBytes: number
  headers: UnsafeObject
}

export type DownloadProgressEvent = {
  id: string
  bytesDownloaded: number
  bytesTotal: number
}

export type DownloadCompleteEvent = {
  id: string
  bytesDownloaded: number
  bytesTotal: number
}

export type DownloadFailedEvent = {
  id: string
  error: string
  errorCode: number
}

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

  pauseTask(id: string): Promise<void>
  resumeTask(id: string): Promise<void>
  stopTask(id: string): Promise<void>

  completeHandler(jobId: string): Promise<void>

  getExistingDownloadTasks(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesDownloaded: number
    bytesTotal: number
    errorCode?: number | null
  }>>

  // Event emitters (new architecture)
  readonly onDownloadBegin: EventEmitter<DownloadBeginEvent>
  readonly onDownloadProgress: EventEmitter<DownloadProgressEvent[]>
  readonly onDownloadComplete: EventEmitter<DownloadCompleteEvent>
  readonly onDownloadFailed: EventEmitter<DownloadFailedEvent>
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader')
