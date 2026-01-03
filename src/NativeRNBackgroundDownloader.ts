import type { TurboModule } from 'react-native'
import { TurboModuleRegistry } from 'react-native'
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypes'

import type { UnsafeObject } from './types'

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
  location: string
  bytesDownloaded: number
  bytesTotal: number
}

export type DownloadFailedEvent = {
  id: string
  error: string
  errorCode: number
}

// Upload event payload types for codegen
export type UploadBeginEvent = {
  id: string
  expectedBytes: number
}

export type UploadProgressEvent = {
  id: string
  bytesUploaded: number
  bytesTotal: number
}

export type UploadCompleteEvent = {
  id: string
  responseCode: number
  responseBody: string
  bytesUploaded: number
  bytesTotal: number
}

export type UploadFailedEvent = {
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

  setLogsEnabled(enabled: boolean): void

  getExistingDownloadTasks(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesDownloaded: number
    bytesTotal: number
    errorCode?: number | null
  }>>

  // Upload methods (optional for backward compatibility until native implementation is complete)
  upload?(options: {
    id: string
    url: string
    source: string
    method: string
    headers?: UnsafeObject
    metadata?: string
    progressInterval?: number
    progressMinBytes?: number
    fieldName?: string
    mimeType?: string
    parameters?: UnsafeObject
    isAllowedOverRoaming: boolean
    isAllowedOverMetered: boolean
    isNotificationVisible: boolean
    notificationTitle?: string
  }): void

  pauseUploadTask?(id: string): Promise<void>
  resumeUploadTask?(id: string): Promise<void>
  stopUploadTask?(id: string): Promise<void>

  getExistingUploadTasks?(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesUploaded: number
    bytesTotal: number
    errorCode?: number | null
  }>>

  // Event emitters (new architecture)
  readonly onDownloadBegin: EventEmitter<DownloadBeginEvent>
  readonly onDownloadProgress: EventEmitter<DownloadProgressEvent[]>
  readonly onDownloadComplete: EventEmitter<DownloadCompleteEvent>
  readonly onDownloadFailed: EventEmitter<DownloadFailedEvent>

  // Upload event emitters (new architecture) - optional for backward compatibility
  readonly onUploadBegin?: EventEmitter<UploadBeginEvent>
  readonly onUploadProgress?: EventEmitter<UploadProgressEvent[]>
  readonly onUploadComplete?: EventEmitter<UploadCompleteEvent>
  readonly onUploadFailed?: EventEmitter<UploadFailedEvent>
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader')
