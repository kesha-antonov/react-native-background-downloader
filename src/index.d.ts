// Type definitions for @kesha-antonov/react-native-background-downloader 2.6
// Project: https://github.com/kesha-antonov/react-native-background-downloader
// Definitions by: Philip Su <https://github.com/fivecar>,
//                 Adam Hunter <https://github.com/adamrhunter>,
//                 Junseong Park <https://github.com/Kweiza>
// Definitions: https://github.com/DefinitelyTyped/DefinitelyTyped

export interface DownloadHeaders {
  [key: string]: string | null
}

export interface Config {
  headers: DownloadHeaders
  progressInterval: number
  progressMinBytes: number
  isLogsEnabled: boolean
}

type SetConfig = (config: Partial<Config>) => void

export interface BeginHandlerObject {
  expectedBytes: number
  headers: { [key: string]: string }
}
export type BeginHandler = ({
  expectedBytes,
  headers,
}: BeginHandlerObject) => void

export interface ProgressHandlerObject {
  bytesDownloaded: number
  bytesTotal: number
}
export type ProgressHandler = ({
  bytesDownloaded,
  bytesTotal,
}: ProgressHandlerObject) => void

export interface DoneHandlerObject {
  bytesDownloaded: number
  bytesTotal: number
}
export type DoneHandler = ({
  bytesDownloaded,
  bytesTotal,
}: DoneHandlerObject) => void

export interface ErrorHandlerObject {
  error: string
  errorCode: number
}
export type ErrorHandler = ({
  error,
  errorCode,
}: ErrorHandlerObject) => void

export interface TaskInfoObject {
  id: string
  metadata: object | string

  bytesDownloaded?: number
  bytesTotal?: number

  beginHandler?: BeginHandler
  progressHandler?: ProgressHandler
  doneHandler?: DoneHandler
  errorHandler?: ErrorHandler
}
export type TaskInfo = TaskInfoObject

export type DownloadTaskState =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED'

export type UploadTaskState =
  | 'PENDING'
  | 'UPLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED'

export interface DownloadTask {
  constructor: (taskInfo: TaskInfo) => DownloadTask

  id: string
  state: DownloadTaskState
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  metadata: Record<string, any>
  bytesDownloaded: number
  bytesTotal: number

  begin: (handler: BeginHandler) => DownloadTask
  progress: (handler: ProgressHandler) => DownloadTask
  done: (handler: DoneHandler) => DownloadTask
  error: (handler: ErrorHandler) => DownloadTask

  _beginHandler: BeginHandler
  _progressHandler: ProgressHandler
  _doneHandler: DoneHandler
  _errorHandler: ErrorHandler

  pause: () => void
  resume: () => void
  stop: () => void
}

export interface UploadTask {
  constructor: (taskInfo: TaskInfo) => UploadTask

  id: string
  state: UploadTaskState
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  metadata: Record<string, any>
  bytesUploaded: number
  bytesTotal: number

  begin: (handler: BeginHandler) => UploadTask
  progress: (handler: ProgressHandler) => UploadTask
  done: (handler: DoneHandler) => UploadTask
  error: (handler: ErrorHandler) => UploadTask

  _beginHandler: BeginHandler
  _progressHandler: ProgressHandler
  _doneHandler: DoneHandler
  _errorHandler: ErrorHandler

  pause: () => void
  resume: () => void
  stop: () => void
}

export type CheckForExistingDownloads = () => Promise<DownloadTask[]>
export type CheckForExistingUploads = () => Promise<UploadTask[]>
export type EnsureDownloadsAreRunning = () => Promise<void>
export type EnsureUploadsAreRunning = () => Promise<void>

export interface DownloadOptions {
  id: string
  url: string
  destination: string
  headers?: DownloadHeaders | undefined
  metadata?: object
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
  isNotificationVisible?: boolean
  notificationTitle?: string
}

export interface UploadOptions {
  id: string
  url: string
  source: string
  headers?: DownloadHeaders | undefined
  metadata?: object
  method?: 'POST' | 'PUT' | 'PATCH'
  fieldName?: string
  mimeType?: string
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
  isNotificationVisible?: boolean
  notificationTitle?: string
}

export type Download = (options: DownloadOptions) => DownloadTask
export type Upload = (options: UploadOptions) => UploadTask
export type CompleteHandler = (id: string) => void

export interface Directories {
  documents: string
}

export const setConfig: SetConfig
export const checkForExistingDownloads: CheckForExistingDownloads
export const checkForExistingUploads: CheckForExistingUploads
export const ensureDownloadsAreRunning: EnsureDownloadsAreRunning
export const ensureUploadsAreRunning: EnsureUploadsAreRunning
export const download: Download
export const upload: Upload
export const completeHandler: CompleteHandler
export const directories: Directories

export interface RNBackgroundDownloader {
  setConfig: SetConfig
  checkForExistingDownloads: CheckForExistingDownloads
  checkForExistingUploads: CheckForExistingUploads
  ensureDownloadsAreRunning: EnsureDownloadsAreRunning
  ensureUploadsAreRunning: EnsureUploadsAreRunning
  download: Download
  upload: Upload
  completeHandler: CompleteHandler
  directories: Directories
}

declare const RNBackgroundDownloader: RNBackgroundDownloader
export default RNBackgroundDownloader
