// UnsafeObject is used for dynamic key-value objects that codegen doesn't support
export type UnsafeObject = { [key: string]: string }

export type Headers = Record<string, string | null>

// External log callback for capturing logs in parent app
export type LogCallback = (tag: string, message: string, ...args: unknown[]) => void

/**
 * Configuration for notification text with pluralization support.
 * Use {count} placeholder for the number of downloads.
 */
export interface NotificationTexts {
  /** Title for individual download notification (default: "Download") */
  downloadTitle?: string
  /** Text shown when download is starting (default: "Starting download...") */
  downloadStarting?: string
  /** Text pattern for download progress. Use {progress} for percentage (default: "Downloading... {progress}%") */
  downloadProgress?: string
  /** Text shown when download is paused (default: "Paused") */
  downloadPaused?: string
  /** Text shown when download is finished (default: "Download complete") */
  downloadFinished?: string
  /** Title for group summary notification (default: "Downloads") */
  groupTitle?: string
  /** Text pattern for group summary. Use {count} for number of downloads.
   * Can be a string or function for pluralization (default: "{count} download(s) in progress") */
  groupText?: string | ((count: number) => string)
}

/**
 * Mode for notification display when grouping is enabled.
 * - 'individual': Show all notifications (default, current behavior)
 * - 'summaryOnly': Show only summary notification, minimize individual ones
 */
export type NotificationGroupingMode = 'individual' | 'summaryOnly'

/**
 * Configuration for notifications grouping.
 */
export interface NotificationsGroupingConfig {
  /** Enable notification grouping (default: false) */
  enabled: boolean
  /** Mode for notification display (default: 'individual') */
  mode?: NotificationGroupingMode
  /** Custom notification texts with optional pluralization */
  texts?: NotificationTexts
}

export interface Config {
  headers?: Headers
  progressInterval?: number
  progressMinBytes?: number
  isLogsEnabled?: boolean
  logCallback?: LogCallback
  maxParallelDownloads?: number
  allowsCellularAccess?: boolean
  /** Show download notifications (Android only, default: false). When false, creates minimal silent notifications (UIDT requires a notification) */
  showNotificationsEnabled?: boolean
  /** Configuration for notifications grouping on Android */
  notificationsGrouping?: NotificationsGroupingConfig
}

export type SetConfig = (config: Partial<Config>) => void

export type BeginHandlerParams = {
  expectedBytes: number
  headers: Headers
}

export type BeginHandler = (params: BeginHandlerParams) => void

export type ProgressHandlerParams = {
  bytesDownloaded: number
  bytesTotal: number
}

export type ProgressHandler = (params: ProgressHandlerParams) => void

export type DoneHandlerParams = {
  location: string
  bytesDownloaded: number
  bytesTotal: number
}

export type DoneHandler = (params: DoneHandlerParams) => void

export interface ErrorHandlerParams {
  error: string
  errorCode: number
}

export type ErrorHandler = (params: ErrorHandlerParams) => void

export type Metadata = Record<string, unknown>

export interface TaskInfoNative {
  id: string
  metadata: Metadata

  bytesDownloaded: number
  bytesTotal: number
  errorCode: number
  state: number
  destination?: string | null
}

export interface TaskInfo {
  id: string
  metadata?: object
}

export type DownloadTaskState =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED'

export type DownloadParams = {
  url: string
  destination: string
  headers?: Headers
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
  maxRedirects?: number
  /** Group ID for notification grouping (only used when grouping is enabled) */
  groupId?: string
  /** Group name displayed in notification (only used when grouping is enabled) */
  groupName?: string
}

export interface DownloadTask {
  id: string
  state: DownloadTaskState
  metadata: Metadata
  errorCode: number
  bytesDownloaded: number
  bytesTotal: number
  downloadParams?: DownloadParams

  begin: (handler: BeginHandler) => DownloadTask
  progress: (handler: ProgressHandler) => DownloadTask
  done: (handler: DoneHandler) => DownloadTask
  error: (handler: ErrorHandler) => DownloadTask

  beginHandler?: BeginHandler
  progressHandler?: ProgressHandler
  doneHandler?: DoneHandler
  errorHandler?: ErrorHandler

  setDownloadParams: (params: DownloadParams) => void
  start: () => void
  pause: () => Promise<void>
  resume: () => Promise<void>
  stop: () => Promise<void>

  tryParseJson: (metadata?: string | Metadata) => Metadata | null
}

export type getExistingDownloadTasks = () => Promise<DownloadTask[]>

export interface DownloadOption {
  id: string
  url: string
  destination: string
  headers?: Headers | undefined
  metadata?: object
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
}

export type Download = (options: DownloadOption) => DownloadTask
export type CompleteHandler = (id: string) => void

export interface Directories {
  documents: string
}

// Upload-specific types
export type UploadBeginHandlerParams = {
  expectedBytes: number
}

export type UploadBeginHandler = (params: UploadBeginHandlerParams) => void

export type UploadProgressHandlerParams = {
  bytesUploaded: number
  bytesTotal: number
}

export type UploadProgressHandler = (params: UploadProgressHandlerParams) => void

export type UploadDoneHandlerParams = {
  responseCode: number
  responseBody: string
  bytesUploaded: number
  bytesTotal: number
}

export type UploadDoneHandler = (params: UploadDoneHandlerParams) => void

export interface UploadErrorHandlerParams {
  error: string
  errorCode: number
}

export type UploadErrorHandler = (params: UploadErrorHandlerParams) => void

export type UploadTaskState =
  | 'PENDING'
  | 'UPLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED'

export interface UploadTaskInfoNative {
  id: string
  metadata: Metadata

  bytesUploaded: number
  bytesTotal: number
  errorCode: number
  state: number
}

export interface UploadTaskInfo {
  id: string
  metadata?: object
}

export type UploadParams = {
  url: string
  source: string
  method?: 'POST' | 'PUT' | 'PATCH'
  headers?: Headers
  fieldName?: string
  mimeType?: string
  parameters?: Record<string, string>
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
}

export interface UploadTask {
  id: string
  state: UploadTaskState
  metadata: Metadata
  errorCode: number
  bytesUploaded: number
  bytesTotal: number
  uploadParams?: UploadParams

  begin: (handler: UploadBeginHandler) => UploadTask
  progress: (handler: UploadProgressHandler) => UploadTask
  done: (handler: UploadDoneHandler) => UploadTask
  error: (handler: UploadErrorHandler) => UploadTask

  beginHandler?: UploadBeginHandler
  progressHandler?: UploadProgressHandler
  doneHandler?: UploadDoneHandler
  errorHandler?: UploadErrorHandler

  setUploadParams: (params: UploadParams) => void
  start: () => void
  pause: () => Promise<void>
  resume: () => Promise<void>
  stop: () => Promise<void>

  tryParseJson: (metadata?: string | Metadata) => Metadata | null
}

export type getExistingUploadTasks = () => Promise<UploadTask[]>

export interface UploadOption {
  id: string
  url: string
  source: string
  method?: 'POST' | 'PUT' | 'PATCH'
  headers?: Headers | undefined
  metadata?: object
  fieldName?: string
  mimeType?: string
  parameters?: Record<string, string>
  isAllowedOverRoaming?: boolean
  isAllowedOverMetered?: boolean
}

export type Upload = (options: UploadOption) => UploadTask
export type CompleteUploadHandler = (id: string) => void
