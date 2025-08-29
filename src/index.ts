import { NativeModules, NativeEventEmitter } from 'react-native'
import DownloadTask from './DownloadTask'
import UploadTask from './UploadTask'
import NativeRNBackgroundDownloader from './NativeRNBackgroundDownloader'
import { DownloadOptions, UploadOptions } from './index.d'

const { RNBackgroundDownloader } = NativeModules
const RNBackgroundDownloaderEmitter = new NativeEventEmitter(RNBackgroundDownloader)

const MIN_PROGRESS_INTERVAL = 250
const tasksMap = new Map()
const uploadTasksMap = new Map()

const config = {
  headers: {},
  progressInterval: 1000,
  progressMinBytes: 1024 * 1024, // 1MB default
  isLogsEnabled: false,
}

function log (...args) {
  if (config.isLogsEnabled)
    console.log('[RNBackgroundDownloader]', ...args)
}

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] downloadBegin', id, rest)
  const task = tasksMap.get(id)
  task?.onBegin(rest)
})

RNBackgroundDownloaderEmitter.addListener('downloadProgress', events => {
  log('[RNBackgroundDownloader] downloadProgress-1', events, tasksMap)
  for (const event of events) {
    const { id, ...rest } = event
    const task = tasksMap.get(id)
    log('[RNBackgroundDownloader] downloadProgress-2', id, task)
    task?.onProgress(rest)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] downloadComplete', id, rest)
  const task = tasksMap.get(id)
  task?.onDone(rest)

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] downloadFailed', id, rest)
  const task = tasksMap.get(id)
  task?.onError(rest)

  tasksMap.delete(id)
})

// Upload event listeners
RNBackgroundDownloaderEmitter.addListener('uploadBegin', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] uploadBegin', id, rest)
  const task = uploadTasksMap.get(id)
  task?.onBegin(rest)
})

RNBackgroundDownloaderEmitter.addListener('uploadProgress', events => {
  log('[RNBackgroundDownloader] uploadProgress-1', events, uploadTasksMap)
  for (const event of events) {
    const { id, ...rest } = event
    const task = uploadTasksMap.get(id)
    log('[RNBackgroundDownloader] uploadProgress-2', id, task)
    task?.onProgress(rest)
  }
})

RNBackgroundDownloaderEmitter.addListener('uploadComplete', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] uploadComplete', id, rest)
  const task = uploadTasksMap.get(id)
  task?.onDone(rest)

  uploadTasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('uploadFailed', ({ id, ...rest }) => {
  log('[RNBackgroundDownloader] uploadFailed', id, rest)
  const task = uploadTasksMap.get(id)
  task?.onError(rest)

  uploadTasksMap.delete(id)
})

export function setConfig ({ headers, progressInterval, progressMinBytes, isLogsEnabled }) {
  if (typeof headers === 'object') config.headers = headers

  if (progressInterval != null)
    if (typeof progressInterval === 'number' && progressInterval >= MIN_PROGRESS_INTERVAL)
      config.progressInterval = progressInterval
    else
      console.warn(`[RNBackgroundDownloader] progressInterval must be a number >= ${MIN_PROGRESS_INTERVAL}. You passed ${progressInterval}`)

  if (progressMinBytes != null)
    if (typeof progressMinBytes === 'number' && progressMinBytes >= 0)
      config.progressMinBytes = progressMinBytes
    else
      console.warn(`[RNBackgroundDownloader] progressMinBytes must be a number >= 0. You passed ${progressMinBytes}`)

  if (typeof isLogsEnabled === 'boolean') config.isLogsEnabled = isLogsEnabled
}

export async function checkForExistingDownloads () {
  log('[RNBackgroundDownloader] checkForExistingDownloads-1')
  const foundTasks = await NativeRNBackgroundDownloader.checkForExistingDownloads()
  log('[RNBackgroundDownloader] checkForExistingDownloads-2', foundTasks)

  return foundTasks.map(taskInfo => {
    // SECOND ARGUMENT RE-ASSIGNS EVENT HANDLERS
    const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id))
    log('[RNBackgroundDownloader] checkForExistingDownloads-3', taskInfo)

    if (taskInfo.state === RNBackgroundDownloader.TaskRunning) {
      task.state = 'DOWNLOADING'
    } else if (taskInfo.state === RNBackgroundDownloader.TaskSuspended) {
      task.state = 'PAUSED'
    } else if (taskInfo.state === RNBackgroundDownloader.TaskCanceling) {
      task.stop()
      return null
    } else if (taskInfo.state === RNBackgroundDownloader.TaskCompleted) {
      if (taskInfo.bytesDownloaded === taskInfo.bytesTotal)
        task.state = 'DONE'
      else
        // IOS completed the download but it was not done.
        return null
    }
    tasksMap.set(taskInfo.id, task)
    return task
  }).filter(task => !!task)
}

export async function checkForExistingUploads () {
  log('[RNBackgroundDownloader] checkForExistingUploads-1')
  const foundTasks = await NativeRNBackgroundDownloader.checkForExistingUploads()
  log('[RNBackgroundDownloader] checkForExistingUploads-2', foundTasks)

  return foundTasks.map(taskInfo => {
    // SECOND ARGUMENT RE-ASSIGNS EVENT HANDLERS
    const task = new UploadTask(taskInfo, uploadTasksMap.get(taskInfo.id))
    log('[RNBackgroundDownloader] checkForExistingUploads-3', taskInfo)

    if (taskInfo.state === RNBackgroundDownloader.TaskRunning) {
      task.state = 'UPLOADING'
    } else if (taskInfo.state === RNBackgroundDownloader.TaskSuspended) {
      task.state = 'PAUSED'
    } else if (taskInfo.state === RNBackgroundDownloader.TaskCanceling) {
      task.stop()
      return null
    } else if (taskInfo.state === RNBackgroundDownloader.TaskCompleted) {
      if (taskInfo.bytesDownloaded === taskInfo.bytesTotal)
        task.state = 'DONE'
      else
        // Task completed but not successfully
        return null
    }
    uploadTasksMap.set(taskInfo.id, task)
    return task
  }).filter(task => !!task)
}

export async function ensureDownloadsAreRunning () {
  log('[RNBackgroundDownloader] ensureDownloadsAreRunning')
  const tasks = await checkForExistingDownloads()
  for (const task of tasks)
    if (task.state === 'DOWNLOADING') {
      task.pause()
      task.resume()
    }
}

export async function ensureUploadsAreRunning () {
  log('[RNBackgroundDownloader] ensureUploadsAreRunning')
  const tasks = await checkForExistingUploads()
  for (const task of tasks)
    if (task.state === 'UPLOADING') {
      task.pause()
      task.resume()
    }
}

export function completeHandler (jobId: string) {
  if (jobId == null) {
    console.warn('[RNBackgroundDownloader] completeHandler: jobId is empty')
    return
  }

  return NativeRNBackgroundDownloader.completeHandler(jobId)
}

export function download (options: DownloadOptions) {
  log('[RNBackgroundDownloader] download', options)
  if (!options.id || !options.url || !options.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  options.headers = { ...config.headers, ...options.headers }

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {}

  options.destination = options.destination.replace('file://', '')

  if (options.isAllowedOverRoaming == null) options.isAllowedOverRoaming = true
  if (options.isAllowedOverMetered == null) options.isAllowedOverMetered = true
  if (options.isNotificationVisible == null) options.isNotificationVisible = false

  const task = new DownloadTask({
    id: options.id,
    metadata: options.metadata,
  })
  tasksMap.set(options.id, task)

  NativeRNBackgroundDownloader.download({
    ...options,
    metadata: JSON.stringify(options.metadata),
    progressInterval: config.progressInterval,
    progressMinBytes: config.progressMinBytes,
  })

  return task
}

export function upload (options: UploadOptions) {
  log('[RNBackgroundDownloader] upload', options)
  if (!options.id || !options.url || !options.source)
    throw new Error('[RNBackgroundDownloader] id, url and source are required')

  options.headers = { ...config.headers, ...options.headers }

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {}

  options.source = options.source.replace('file://', '')

  if (options.isAllowedOverRoaming == null) options.isAllowedOverRoaming = true
  if (options.isAllowedOverMetered == null) options.isAllowedOverMetered = true
  if (options.isNotificationVisible == null) options.isNotificationVisible = false
  if (options.method == null) options.method = 'POST'
  if (options.fieldName == null) options.fieldName = 'file'

  const task = new UploadTask({
    id: options.id,
    metadata: options.metadata,
  })
  uploadTasksMap.set(options.id, task)

  NativeRNBackgroundDownloader.upload({
    ...options,
    metadata: JSON.stringify(options.metadata),
    progressInterval: config.progressInterval,
    progressMinBytes: config.progressMinBytes,
  })

  return task
}

export const directories = {
  documents: RNBackgroundDownloader.documents,
}

export default {
  download,
  upload,
  checkForExistingDownloads,
  checkForExistingUploads,
  ensureDownloadsAreRunning,
  ensureUploadsAreRunning,
  completeHandler,

  setConfig,

  directories,
}
