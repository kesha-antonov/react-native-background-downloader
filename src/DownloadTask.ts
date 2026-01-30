import {
  TaskInfo,
  DownloadTask as DownloadTaskType,
  BeginHandler,
  ProgressHandler,
  DoneHandler,
  ErrorHandler,
  BeginHandlerParams,
  ProgressHandlerParams,
  DoneHandlerParams,
  ErrorHandlerParams,
  TaskInfoNative,
  DownloadParams,
  DownloadTaskState,
  Metadata,
  UnsafeObject,
  Headers,
} from './types'
import { config, log } from './config'

// Import shared native module getter to avoid duplicating TurboModule lookup
// This is lazily imported to avoid circular dependency issues at module load time
let getNativeModuleImpl: (() => import('./NativeRNBackgroundDownloader').Spec) | null = null

function getNativeModule (): import('./NativeRNBackgroundDownloader').Spec {
  if (!getNativeModuleImpl)
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    getNativeModuleImpl = require('./index').getNativeModule

  return getNativeModuleImpl!()
}

export class DownloadTask {
  id: string = ''
  metadata: Metadata = {}
  destination?: string

  state: DownloadTaskState = 'PENDING'
  errorCode: number = 0
  bytesDownloaded: number = 0
  bytesTotal: number = 0

  downloadParams?: DownloadParams

  beginHandler?: BeginHandler
  progressHandler?: ProgressHandler
  doneHandler?: DoneHandler
  errorHandler?: ErrorHandler

  constructor (taskParams: TaskInfo | TaskInfoNative, originalTask?: DownloadTaskType) {
    this.id = taskParams.id

    if ((taskParams as TaskInfoNative).bytesDownloaded)
      this.bytesDownloaded = (taskParams as TaskInfoNative).bytesDownloaded

    if ((taskParams as TaskInfoNative).bytesTotal)
      this.bytesTotal = (taskParams as TaskInfoNative).bytesTotal

    if ((taskParams as TaskInfoNative).destination)
      this.destination = (taskParams as TaskInfoNative).destination ?? undefined

    this.metadata = this.tryParseJson(taskParams.metadata) ?? {}

    if (originalTask) {
      this.beginHandler = originalTask.beginHandler
      this.progressHandler = originalTask.progressHandler
      this.doneHandler = originalTask.doneHandler
      this.errorHandler = originalTask.errorHandler
    }
  }

  // event listeners setters

  begin (handler: BeginHandler) {
    if (typeof handler !== 'function')
      throw new Error('begin handler must be a function')

    this.beginHandler = handler
    return this
  }

  progress (handler: ProgressHandler) {
    if (typeof handler !== 'function')
      throw new Error('progress handler must be a function')

    this.progressHandler = handler
    return this
  }

  done (handler: DoneHandler) {
    if (typeof handler !== 'function')
      throw new Error('done handler must be a function')

    this.doneHandler = handler
    return this
  }

  error (handler: ErrorHandler) {
    if (typeof handler !== 'function')
      throw new Error('error handler must be a function')

    this.errorHandler = handler
    return this
  }

  // event listeners

  onBegin (params: BeginHandlerParams) {
    this.state = 'DOWNLOADING'
    this.bytesTotal = params.expectedBytes
    this.beginHandler?.(params)
  }

  onProgress (params: ProgressHandlerParams) {
    this.bytesDownloaded = params.bytesDownloaded
    this.bytesTotal = params.bytesTotal
    this.progressHandler?.(params)
  }

  onDone (params: DoneHandlerParams) {
    this.state = 'DONE'
    this.bytesDownloaded = params.bytesDownloaded
    this.bytesTotal = params.bytesTotal
    this.doneHandler?.(params)
  }

  onError (params: ErrorHandlerParams) {
    this.state = 'FAILED'
    this.errorHandler?.(params)
  }

  // methods

  /**
   * Update download parameters.
   * If the task is paused, this will also update headers in the native layer.
   * If the task is in-progress or completed, only the local JS object is updated.
   *
   * @param downloadParams - The new download parameters
   * @returns Promise<boolean> - true if native headers were updated, false otherwise
   */
  async setDownloadParams (downloadParams: DownloadParams): Promise<boolean> {
    this.downloadParams = downloadParams

    // If task is paused, update headers in native layer
    if (this.state === 'PAUSED' && downloadParams.headers) {
      const headers = this.headersToUnsafeObject(downloadParams.headers)
      if (headers) {
        log('DownloadTask: setDownloadParams updating native headers', this.id)
        return getNativeModule().updateTaskHeaders(this.id, headers)
      }
    }

    return false
  }

  async pause (): Promise<void> {
    log('DownloadTask: pause', this.id)
    this.state = 'PAUSED'
    await getNativeModule().pauseTask(this.id)
  }

  async resume (): Promise<void> {
    log('DownloadTask: resume', this.id)
    this.state = 'DOWNLOADING'
    this.errorCode = 0
    await getNativeModule().resumeTask(this.id)
  }

  start () {
    if (this.state !== 'PENDING') {
      log('DownloadTask: start. Download already started, can\' start again... ', this.id)
      this.errorHandler?.({ error: 'Download already started', errorCode: -1 })
      return
    }

    if (!this.downloadParams) {
      log('DownloadTask: start. downloadParams is missing. "setDownloadParams" wasn\'t called before "start"', this.id)
      this.errorHandler?.({ error: 'downloadParams is missing. setDownloadParams must be called before start', errorCode: -2 })
      return
    }

    this.state = 'DOWNLOADING'

    // kick-off download after returning the task
    getNativeModule().download({
      id: this.id,
      metadata: JSON.stringify(this.metadata),
      progressInterval: config.progressInterval,
      progressMinBytes: config.progressMinBytes,
      ...this.downloadParams,
      headers: this.headersToUnsafeObject(this.downloadParams.headers),
      isAllowedOverRoaming: this.downloadParams.isAllowedOverRoaming ?? false,
      isAllowedOverMetered: this.downloadParams.isAllowedOverMetered ?? false,
    })
  }

  async stop (): Promise<void> {
    log('DownloadTask: stop', this.id)

    this.state = 'STOPPED'
    await getNativeModule().stopTask(this.id)
  }

  tryParseJson (metadata?: string | Metadata | object): Metadata | null {
    try {
      if (typeof metadata === 'string')
        return JSON.parse(metadata) as Metadata

      return (metadata as Metadata) ?? null
    } catch (e) {
      log('DownloadTask tryParseJson', e)
      return null
    }
  }

  private headersToUnsafeObject (headers: Headers | undefined): UnsafeObject | undefined {
    if (!headers) return undefined

    // Filter out null values from headers to match native UnsafeObject type
    return Object.keys(headers)
      .reduce<UnsafeObject>((mapped, key) => {
        if (headers[key])
          mapped[key] = headers[key]

        return mapped
      }, {})
  }
}
