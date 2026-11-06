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

// One part of an upload split across partUrls
export type UploadPartResult = {
  // 1-based, as S3's CompleteMultipartUpload numbers parts
  partNumber: number
  responseCode: number
  responseBody: string
  // Lowercased header names, e.g. responseHeaders.etag
  responseHeaders: UnsafeObject
  bytes: number
}

export type UploadCompleteEvent = {
  id: string
  responseCode: number
  responseBody: string
  // Lowercased header names
  responseHeaders?: UnsafeObject
  // Set for an upload with partUrls, in part order
  parts?: UploadPartResult[]
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
    maxRedirects?: number
    /** iOS only: NSFileProtection level for the saved file. Ignored on Android. */
    iosDataProtection?: string
  }): void

  pauseTask(id: string): Promise<void>
  resumeTask(id: string): Promise<void>
  stopTask(id: string): Promise<void>
  updateTaskHeaders(id: string, headers: UnsafeObject): Promise<boolean>

  completeHandler(jobId: string): Promise<void>

  setLogsEnabled(enabled: boolean): void
  setMaxParallelDownloads(max: number): void
  setAllowsCellularAccess(allows: boolean): void
  setNotificationGroupingConfig?(config: {
    enabled: boolean
    showNotificationsEnabled: boolean
    showCompletionNotification: boolean
    showCancelAction: boolean
    mode: string
    texts: UnsafeObject
  }): void

  getExistingDownloadTasks(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesDownloaded: number
    bytesTotal: number
    errorCode?: number | null
    destination?: string | null
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
    // Upload source in parts, one URL per part (url is ignored then)
    partUrls?: string[]
    partSize?: number
    isAllowedOverRoaming: boolean
    isAllowedOverMetered: boolean
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
    // The response of an upload that finished while the app wasn't listening
    responseCode?: number | null
    responseBody?: string | null
    responseHeaders?: UnsafeObject | null
    parts?: UploadPartResult[] | null
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
