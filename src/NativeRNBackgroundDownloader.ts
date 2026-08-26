import type { TurboModule } from 'react-native'
import { TurboModuleRegistry } from 'react-native'
import type { EventEmitter, UnsafeObject as CodegenUnsafeObject } from 'react-native/Libraries/Types/CodegenTypes'

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
  metadata?: string
}

export type DownloadFailedEvent = {
  id: string
  error: string
  errorCode: number
  metadata?: string
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
  metadata?: string
}

export type UploadFailedEvent = {
  id: string
  error: string
  errorCode: number
  metadata?: string
}

export interface Spec extends TurboModule {
  // Constants exported to JavaScript
  getConstants(): {
    documents: string
    TaskRunning: number
    TaskSuspended: number
    TaskCanceling: number
    TaskCompleted: number
    isLoggingEnabled: boolean
  }

  // Methods
  download(options: {
    id: string
    url: string
    destination: string
    headers?: UnsafeObject
    metadata?: string
    expectedSha256?: string
  }): void

  pauseTask(id: string): Promise<void>
  resumeTask(id: string): Promise<void>
  stopTask(id: string): Promise<void>
  setRuntimeReady(family: string): Promise<Array<{ name: string, key: string, payload: CodegenUnsafeObject }>>
  acknowledgeRuntimeEvents(keys: string[]): void

  getExistingDownloadTasks(): Promise<Array<{
    id: string
    metadata: string
    state: number
    bytesDownloaded: number
    bytesTotal: number
    errorCode?: number | null
    destination?: string | null
  }>>

  upload(options: {
    id: string
    url: string
    source: string
    method: string
    headers?: UnsafeObject
    metadata?: string
    fieldName?: string
    mimeType?: string
    parameters?: UnsafeObject
  }): void

  pauseUploadTask(id: string): Promise<void>
  resumeUploadTask(id: string): Promise<void>
  stopUploadTask(id: string): Promise<void>

  getExistingUploadTasks(): Promise<Array<{
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

  readonly onUploadBegin: EventEmitter<UploadBeginEvent>
  readonly onUploadProgress: EventEmitter<UploadProgressEvent[]>
  readonly onUploadComplete: EventEmitter<UploadCompleteEvent>
  readonly onUploadFailed: EventEmitter<UploadFailedEvent>
}

export default TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader')
