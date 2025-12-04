// UnsafeObject is used for dynamic key-value objects that codegen doesn't support
export type UnsafeObject = { [key: string]: string }

export type Headers = Record<string, string | null>

export interface Config {
  headers?: Headers
  progressInterval?: number
  progressMinBytes?: number
  isLogsEnabled?: boolean
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
  isNotificationVisible?: boolean
  notificationTitle?: string
  maxRedirects?: number
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
  isNotificationVisible?: boolean
  notificationTitle?: string
}

export type Download = (options: DownloadOption) => DownloadTask
export type CompleteHandler = (id: string) => void

export interface Directories {
  documents: string
}
