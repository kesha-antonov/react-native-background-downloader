import { NativeModules, NativeEventEmitter, Platform } from 'react-native'
import DownloadTask from './lib/DownloadTask'

const { RNBackgroundDownloader } = NativeModules
const RNBackgroundDownloaderEmitter = new NativeEventEmitter(RNBackgroundDownloader)

const tasksMap = new Map()
let headers = {}

RNBackgroundDownloaderEmitter.addListener('downloadProgress', events => {
  for (const event of events) {
    const task = tasksMap.get(event.id)
    task?.onProgress(event.percent, event.written, event.total)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, location }) => {
  const task = tasksMap.get(id)
  task?.onDone({ location })

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', event => {
  const task = tasksMap.get(event.id)
  task?.onError(event.error, event.errorcode)

  tasksMap.delete(event.id)
})

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, expectedBytes, headers }) => {
  const task = tasksMap.get(id)
  task?.onBegin({ expectedBytes, headers })
})

export function setHeaders (h = {}) {
  if (typeof h !== 'object')
    throw new Error('[RNBackgroundDownloader] headers must be an object')

  headers = h
}

export function initDownloader (options = {}) {
  if (Platform.OS === 'android')
    RNBackgroundDownloader.initDownloader(options)
}

export function checkForExistingDownloads () {
  return RNBackgroundDownloader.checkForExistingDownloads()
    .then(foundTasks => {
      return foundTasks.map(taskInfo => {
        // SECOND ARGUMENT RE-ASSIGNS EVENT HANDLERS
        const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id))

        if (taskInfo.state === RNBackgroundDownloader.TaskRunning) {
          task.state = 'DOWNLOADING'
        } else if (taskInfo.state === RNBackgroundDownloader.TaskSuspended) {
          task.state = 'PAUSED'
        } else if (taskInfo.state === RNBackgroundDownloader.TaskCanceling) {
          task.stop()
          return null
        } else if (taskInfo.state === RNBackgroundDownloader.TaskCompleted) {
          if (taskInfo.bytesWritten === taskInfo.totalBytes)
            task.state = 'DONE'
          else
            // IOS completed the download but it was not done.
            return null
        }
        tasksMap.set(taskInfo.id, task)
        return task
      }).filter(task => task !== null)
    })
}

export function ensureDownloadsAreRunning () {
  return checkForExistingDownloads()
    .then(tasks => {
      for (const task of tasks)
        if (task.state === 'DOWNLOADING') {
          task.pause()
          task.resume()
        }
    })
}

export function completeHandler (jobId) {
  return RNBackgroundDownloader.completeHandler(jobId)
}

type DownloadOptions = {
  id: string,
  url: string,
  destination: string,
  headers?: object,
  metadata?: object,
}

export function download (options : DownloadOptions) {
  if (!options.id || !options.url || !options.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  options.headers = { ...headers, ...(options.headers || {}) }

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {}

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

export const Network = {
  WIFI_ONLY: RNBackgroundDownloader.OnlyWifi,
  ALL: RNBackgroundDownloader.AllNetworks,
}

export const Priority = {
  HIGH: RNBackgroundDownloader.PriorityHigh,
  MEDIUM: RNBackgroundDownloader.PriorityNormal,
  LOW: RNBackgroundDownloader.PriorityLow,
}

export default {
  initDownloader,
  download,
  checkForExistingDownloads,
  ensureDownloadsAreRunning,
  completeHandler,
  setHeaders,
  directories,
  Network,
  Priority,
}
