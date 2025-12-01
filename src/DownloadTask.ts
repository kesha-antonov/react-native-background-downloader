import { NativeModules } from 'react-native'
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
} from './types'
import { config, log } from './config'
import type { Spec } from './NativeRNBackgroundDownloader'

// Try to get the native module using TurboModuleRegistry first (new architecture),
// then fall back to NativeModules (old architecture)
const isTurboModuleEnabled = global.__turboModuleProxy != null

let RNBackgroundDownloader: Spec
if (isTurboModuleEnabled)
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  RNBackgroundDownloader = require('./NativeRNBackgroundDownloader').default
else
  RNBackgroundDownloader = NativeModules.RNBackgroundDownloader

export class DownloadTask {
  id: string = ''
  metadata: Metadata = {}

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

  setDownloadParams (downloadParams: DownloadParams) {
    this.downloadParams = downloadParams
  }

  async pause (): Promise<void> {
    log('DownloadTask: pause', this.id)
    this.state = 'PAUSED'
    await RNBackgroundDownloader.pauseTask(this.id)
  }

  async resume (): Promise<void> {
    log('DownloadTask: resume', this.id)
    this.state = 'DOWNLOADING'
    this.errorCode = 0
    await RNBackgroundDownloader.resumeTask(this.id)
  }

  start () {
    if (this.state !== 'PENDING') {
      log('DownloadTask: start. Download already started, can\' start again... ', this.id)
      return
    }

    if (!this.downloadParams) {
      log('DownloadTask: start. downloadParams is missing. "setDownloadParams" wasn\'t called before "start"', this.id)
      return
    }

    // kick-off download after returning the task
    RNBackgroundDownloader.download({
      id: this.id,
      metadata: JSON.stringify(this.metadata),
      progressInterval: config.progressInterval,
      progressMinBytes: config.progressMinBytes,
      ...this.downloadParams,
    })
  }

  async stop (): Promise<void> {
    log('DownloadTask: stop', this.id)

    this.state = 'STOPPED'
    await RNBackgroundDownloader.stopTask(this.id)
  }

  tryParseJson (metadata?: string | Metadata): Metadata | null {
    try {
      if (typeof metadata === 'string')
        return JSON.parse(metadata) as Metadata

      return metadata ?? null
    } catch (e) {
      log('DownloadTask tryParseJson', e)
      return null
    }
  }
}
