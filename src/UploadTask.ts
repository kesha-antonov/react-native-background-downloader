import { NativeModules } from 'react-native'
import { TaskInfo } from './index.d'

const { RNBackgroundDownloader } = NativeModules

function validateHandler (handler) {
  const type = typeof handler

  if (type !== 'function')
    throw new TypeError(`[RNBackgroundDownloader] expected argument to be a function, got: ${type}`)
}

export default class UploadTask {
  id = ''
  state = 'PENDING'
  metadata = {}

  bytesUploaded = 0
  bytesTotal = 0

  beginHandler
  progressHandler
  doneHandler
  errorHandler

  constructor (taskInfo: TaskInfo, originalTask?: TaskInfo) {
    this.id = taskInfo.id
    this.bytesUploaded = taskInfo.bytesDownloaded ?? 0 // Reuse field for uploaded bytes
    this.bytesTotal = taskInfo.bytesTotal ?? 0

    const metadata = this.tryParseJson(taskInfo.metadata)
    if (metadata)
      this.metadata = metadata

    if (originalTask) {
      this.beginHandler = originalTask.beginHandler
      this.progressHandler = originalTask.progressHandler
      this.doneHandler = originalTask.doneHandler
      this.errorHandler = originalTask.errorHandler
    }
  }

  begin (handler) {
    validateHandler(handler)
    this.beginHandler = handler
    return this
  }

  progress (handler) {
    validateHandler(handler)
    this.progressHandler = handler
    return this
  }

  done (handler) {
    validateHandler(handler)
    this.doneHandler = handler
    return this
  }

  error (handler) {
    validateHandler(handler)
    this.errorHandler = handler
    return this
  }

  onBegin (params) {
    this.state = 'UPLOADING'
    this.beginHandler?.(params)
  }

  onProgress ({ bytesDownloaded, bytesTotal }) {
    // Native layer will send bytesDownloaded/bytesTotal, we map to uploaded
    this.bytesUploaded = bytesDownloaded
    this.bytesTotal = bytesTotal
    this.progressHandler?.({ bytesDownloaded, bytesTotal })
  }

  onDone (params) {
    this.state = 'DONE'
    this.bytesUploaded = params.bytesDownloaded // Map from native response
    this.bytesTotal = params.bytesTotal
    this.doneHandler?.(params)
  }

  onError (params) {
    this.state = 'FAILED'
    this.errorHandler?.(params)
  }

  pause () {
    this.state = 'PAUSED'
    RNBackgroundDownloader.pauseTask(this.id)
  }

  resume () {
    this.state = 'UPLOADING'
    RNBackgroundDownloader.resumeTask(this.id)
  }

  stop () {
    this.state = 'STOPPED'
    RNBackgroundDownloader.stopTask(this.id)
  }

  tryParseJson (element) {
    try {
      if (typeof element === 'string')
        element = JSON.parse(element)

      return element
    } catch (e) {
      console.warn('UploadTask tryParseJson', e)
      return null
    }
  }
}
