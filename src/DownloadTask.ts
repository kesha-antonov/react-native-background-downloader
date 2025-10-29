import { NativeModules } from 'react-native'
import type {
  TaskInfo,
  BeginHandler,
  ProgressHandler,
  DoneHandler,
  ErrorHandler,
  StateHandler,
  BeginHandlerObject,
  ProgressHandlerObject,
  DoneHandlerObject,
  ErrorHandlerObject,
  DownloadTaskState,
} from './types'

const { RNBackgroundDownloader } = NativeModules

function validateHandler(handler: unknown) {
  const type = typeof handler

  if (type !== 'function')
    throw new TypeError(`[RNBackgroundDownloader] expected argument to be a function, got: ${type}`)
}

export default class DownloadTask {
  id = ''
  private _state: DownloadTaskState = 'PENDING'
  errorCode = 0
  metadata = {}

  bytesDownloaded = 0
  bytesTotal = 0

  beginHandler?: BeginHandler
  progressHandler?: ProgressHandler
  doneHandler?: DoneHandler
  errorHandler?: ErrorHandler
  stateHandler?: StateHandler

  get state(): DownloadTaskState {
    return this._state
  }

  set state(newState: DownloadTaskState) {
    if (this._state !== newState) {
      const oldState = this._state
      this._state = newState
      if (this.stateHandler)
        this.stateHandler({ oldState, newState })
    }
  }

  constructor(taskInfo: TaskInfo, originalTask?: TaskInfo) {
    this.id = taskInfo.id
    this.bytesDownloaded = taskInfo.bytesDownloaded ?? 0
    this.bytesTotal = taskInfo.bytesTotal ?? 0

    const metadata = this.tryParseJson(taskInfo.metadata)
    if (metadata)
      this.metadata = metadata

    if (originalTask) {
      this.beginHandler = originalTask.beginHandler
      this.progressHandler = originalTask.progressHandler
      this.doneHandler = originalTask.doneHandler
      this.errorHandler = originalTask.errorHandler
      this.stateHandler = originalTask.stateHandler
    }
  }

  begin(handler: BeginHandler) {
    validateHandler(handler)
    this.beginHandler = handler
    return this
  }

  progress(handler: ProgressHandler) {
    validateHandler(handler)
    this.progressHandler = handler
    return this
  }

  done(handler: DoneHandler) {
    validateHandler(handler)
    this.doneHandler = handler
    return this
  }

  error(handler: ErrorHandler) {
    validateHandler(handler)
    this.errorHandler = handler
    return this
  }

  stateChange(handler: StateHandler) {
    validateHandler(handler)
    this.stateHandler = handler
    return this
  }

  onBegin(params: BeginHandlerObject) {
    this.state = 'DOWNLOADING'
    this.beginHandler?.(params)
  }

  onProgress({ bytesDownloaded, bytesTotal }: ProgressHandlerObject) {
    this.bytesDownloaded = bytesDownloaded
    this.bytesTotal = bytesTotal
    this.progressHandler?.({ bytesDownloaded, bytesTotal })
  }

  onDone(params: DoneHandlerObject) {
    this.state = 'DONE'
    this.bytesDownloaded = params.bytesDownloaded
    this.bytesTotal = params.bytesTotal
    this.doneHandler?.(params)
  }

  onError(params: ErrorHandlerObject) {
    this.state = 'FAILED'
    this.errorHandler?.(params)
  }

  pause() {
    console.log('DownloadTask: pause', this.id)
    this.state = 'PAUSED'
    RNBackgroundDownloader.pauseTask(this.id)
  }

  resume() {
    console.log('DownloadTask: resume', this.id)
    this.state = 'DOWNLOADING'
    this.errorCode = 0
    RNBackgroundDownloader.resumeTask(this.id)
  }

  stop() {
    console.log('DownloadTask: stop', this.id)
    this.state = 'STOPPED'
    RNBackgroundDownloader.stopTask(this.id)
  }

  tryParseJson(element: unknown) {
    try {
      if (typeof element === 'string')
        element = JSON.parse(element)

      return element
    } catch (e) {
      console.warn('DownloadTask tryParseJson', e)
      return null
    }
  }
}
