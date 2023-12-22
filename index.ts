import { NativeModules, NativeEventEmitter, Platform } from 'react-native'
import DownloadTask from './lib/DownloadTask'

const { RNBackgroundDownloader } = NativeModules
const RNBackgroundDownloaderEmitter = new NativeEventEmitter(RNBackgroundDownloader)

const tasksMap = new Map()
let headers = {}

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, expectedBytes, headers }) => {
  console.log('[RNBackgroundDownloader] downloadBegin', id, expectedBytes, headers)
  const task = tasksMap.get(id)
  task?.onBegin({ expectedBytes, headers })
})

RNBackgroundDownloaderEmitter.addListener('downloadProgress', events => {
  // console.log('[RNBackgroundDownloader] downloadProgress-1', events, tasksMap)
  for (const event of events) {
    const task = tasksMap.get(event.id)
    // console.log('[RNBackgroundDownloader] downloadProgress-2', event.id, task)
    task?.onProgress(event.percent, event.bytesDownloaded, event.bytesTotal)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, location }) => {
  console.log('[RNBackgroundDownloader] downloadComplete', id, location)
  const task = tasksMap.get(id)
  task?.onDone({ location })

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', event => {
  console.log('[RNBackgroundDownloader] downloadFailed', event)
  const task = tasksMap.get(event.id)
  task?.onError(event.error, event.errorCode)

  tasksMap.delete(event.id)
})

export function setHeaders(h = {}) {
  if (typeof h !== 'object')
    throw new Error('[RNBackgroundDownloader] headers must be an object')

  headers = h
}

export function initDownloader(options = {}) {
  if (Platform.OS === 'android')
    RNBackgroundDownloader.initDownloader(options)
}

export function checkForExistingDownloads() {
  console.log('[RNBackgroundDownloader] checkForExistingDownloads-1')
  return RNBackgroundDownloader.checkForExistingDownloads()
    .then(foundTasks => {
      console.log('[RNBackgroundDownloader] checkForExistingDownloads-2', foundTasks)
      return foundTasks.map(taskInfo => {
        // SECOND ARGUMENT RE-ASSIGNS EVENT HANDLERS
        const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id))
        console.log('[RNBackgroundDownloader] checkForExistingDownloads-3', taskInfo)

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
    })
}

export function ensureDownloadsAreRunning() {
  console.log('[RNBackgroundDownloader] ensureDownloadsAreRunning')
  return checkForExistingDownloads()
    .then(tasks => {
      for (const task of tasks)
        if (task.state === 'DOWNLOADING') {
          task.pause()
          task.resume()
        }
    })
}

export function completeHandler(jobId: string) {
  if (jobId == null) {
    console.warn('[RNBackgroundDownloader] completeHandler: jobId is empty')
    return
  }

  return RNBackgroundDownloader.completeHandler(jobId)
}

type DownloadOptions = {
  id: string,
  url: string,
  destination: string,
  headers?: object,
  metadata?: object,
  isAllowedOverRoaming?: boolean,
  isAllowedOverMetered?: boolean,
}

export function download(options: DownloadOptions) {
  console.log('[RNBackgroundDownloader] download', options)
  if (!options.id || !options.url || !options.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  options.headers = { ...headers, ...(options.headers || {}) }

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {}

  options.destination = options.destination.replace('file://', '')

  if (options.isAllowedOverRoaming == null) options.isAllowedOverRoaming = true
  if (options.isAllowedOverMetered == null) options.isAllowedOverMetered = true

  const task = new DownloadTask({
    id: options.id,
    metadata: options.metadata,
  })
  tasksMap.set(options.id, task)

  RNBackgroundDownloader.download({
    ...options,
    metadata: JSON.stringify(options.metadata),
  })

  return task
}

export const directories = {
  documents: RNBackgroundDownloader.documents,
}

export default {
  initDownloader,
  download,
  checkForExistingDownloads,
  ensureDownloadsAreRunning,
  completeHandler,
  setHeaders,
  directories,
}
