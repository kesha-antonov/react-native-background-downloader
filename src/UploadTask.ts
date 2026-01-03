import {
  UploadTaskInfo,
  UploadTask as UploadTaskType,
  UploadBeginHandler,
  UploadProgressHandler,
  UploadDoneHandler,
  UploadErrorHandler,
  UploadBeginHandlerParams,
  UploadProgressHandlerParams,
  UploadDoneHandlerParams,
  UploadErrorHandlerParams,
  UploadTaskInfoNative,
  UploadParams,
  UploadTaskState,
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

export class UploadTask {
  id: string = ''
  metadata: Metadata = {}

  state: UploadTaskState = 'PENDING'
  errorCode: number = 0
  bytesUploaded: number = 0
  bytesTotal: number = 0

  uploadParams?: UploadParams

  beginHandler?: UploadBeginHandler
  progressHandler?: UploadProgressHandler
  doneHandler?: UploadDoneHandler
  errorHandler?: UploadErrorHandler

  constructor (taskParams: UploadTaskInfo | UploadTaskInfoNative, originalTask?: UploadTaskType) {
    this.id = taskParams.id

    if ((taskParams as UploadTaskInfoNative).bytesUploaded)
      this.bytesUploaded = (taskParams as UploadTaskInfoNative).bytesUploaded

    if ((taskParams as UploadTaskInfoNative).bytesTotal)
      this.bytesTotal = (taskParams as UploadTaskInfoNative).bytesTotal

    this.metadata = this.tryParseJson(taskParams.metadata) ?? {}

    if (originalTask) {
      this.beginHandler = originalTask.beginHandler
      this.progressHandler = originalTask.progressHandler
      this.doneHandler = originalTask.doneHandler
      this.errorHandler = originalTask.errorHandler
    }
  }

  // event listeners setters

  begin (handler: UploadBeginHandler) {
    if (typeof handler !== 'function')
      throw new Error('begin handler must be a function')

    this.beginHandler = handler
    return this
  }

  progress (handler: UploadProgressHandler) {
    if (typeof handler !== 'function')
      throw new Error('progress handler must be a function')

    this.progressHandler = handler
    return this
  }

  done (handler: UploadDoneHandler) {
    if (typeof handler !== 'function')
      throw new Error('done handler must be a function')

    this.doneHandler = handler
    return this
  }

  error (handler: UploadErrorHandler) {
    if (typeof handler !== 'function')
      throw new Error('error handler must be a function')

    this.errorHandler = handler
    return this
  }

  // event listeners

  onBegin (params: UploadBeginHandlerParams) {
    this.state = 'UPLOADING'
    this.bytesTotal = params.expectedBytes
    this.beginHandler?.(params)
  }

  onProgress (params: UploadProgressHandlerParams) {
    this.bytesUploaded = params.bytesUploaded
    this.bytesTotal = params.bytesTotal
    this.progressHandler?.(params)
  }

  onDone (params: UploadDoneHandlerParams) {
    this.state = 'DONE'
    this.bytesUploaded = params.bytesUploaded
    this.bytesTotal = params.bytesTotal
    this.doneHandler?.(params)
  }

  onError (params: UploadErrorHandlerParams) {
    this.state = 'FAILED'
    this.errorHandler?.(params)
  }

  // methods

  setUploadParams (uploadParams: UploadParams) {
    this.uploadParams = uploadParams
  }

  async pause (): Promise<void> {
    log('UploadTask: pause', this.id)
    this.state = 'PAUSED'
    const nativeModule = getNativeModule()
    if (nativeModule.pauseUploadTask)
      await nativeModule.pauseUploadTask(this.id)
    else
      log('UploadTask: pause not supported - native implementation missing')
  }

  async resume (): Promise<void> {
    log('UploadTask: resume', this.id)
    this.state = 'UPLOADING'
    this.errorCode = 0
    const nativeModule = getNativeModule()
    if (nativeModule.resumeUploadTask)
      await nativeModule.resumeUploadTask(this.id)
    else
      log('UploadTask: resume not supported - native implementation missing')
  }

  start () {
    if (this.state !== 'PENDING') {
      log('UploadTask: start. Upload already started, can\' start again... ', this.id)
      this.errorHandler?.({ error: 'Upload already started', errorCode: -1 })
      return
    }

    if (!this.uploadParams) {
      log('UploadTask: start. uploadParams is missing. "setUploadParams" wasn\'t called before "start"', this.id)
      this.errorHandler?.({ error: 'uploadParams is missing. setUploadParams must be called before start', errorCode: -2 })
      return
    }

    const nativeModule = getNativeModule()
    if (!nativeModule.upload) {
      log('UploadTask: start. Upload not supported - native implementation missing')
      this.errorHandler?.({ error: 'Upload not supported - native implementation missing', errorCode: -3 })
      return
    }

    this.state = 'UPLOADING'

    // kick-off upload after returning the task
    nativeModule.upload({
      id: this.id,
      metadata: JSON.stringify(this.metadata),
      progressInterval: config.progressInterval,
      progressMinBytes: config.progressMinBytes,
      ...this.uploadParams,
      method: this.uploadParams.method ?? 'POST',
      headers: this.headersToUnsafeObject(this.uploadParams.headers),
      isAllowedOverRoaming: this.uploadParams.isAllowedOverRoaming ?? false,
      isAllowedOverMetered: this.uploadParams.isAllowedOverMetered ?? false,
      isNotificationVisible: this.uploadParams.isNotificationVisible ?? false,
    })
  }

  async stop (): Promise<void> {
    log('UploadTask: stop', this.id)

    this.state = 'STOPPED'
    const nativeModule = getNativeModule()
    if (nativeModule.stopUploadTask)
      await nativeModule.stopUploadTask(this.id)
    else
      log('UploadTask: stop not supported - native implementation missing')
  }

  tryParseJson (metadata?: string | Metadata | object): Metadata | null {
    try {
      if (typeof metadata === 'string')
        return JSON.parse(metadata) as Metadata

      return (metadata as Metadata) ?? null
    } catch (e) {
      log('UploadTask tryParseJson', e)
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
