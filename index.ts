import { NativeModules, NativeEventEmitter } from 'react-native'
import DownloadTask from './lib/DownloadTask'

const { RNBackgroundDownloader } = NativeModules
const RNBackgroundDownloaderEmitter = new NativeEventEmitter(RNBackgroundDownloader)

const tasksMap = new Map()
let headers = {}

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, ...rest }) => {
  console.log('[RNBackgroundDownloader] downloadBegin', id, rest)
  const task = tasksMap.get(id)
  task?.onBegin(rest)
})

RNBackgroundDownloaderEmitter.addListener('downloadProgress', events => {
  // console.log('[RNBackgroundDownloader] downloadProgress-1', events, tasksMap)
  for (const event of events) {
    const { id, ...rest } = event
    const task = tasksMap.get(id)
    // console.log('[RNBackgroundDownloader] downloadProgress-2', id, task)
    task?.onProgress(rest)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, ...rest }) => {
  console.log('[RNBackgroundDownloader] downloadComplete', id, rest)
  const task = tasksMap.get(id)
  task?.onDone(rest)

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', ({ id, ...rest }) => {
  console.log('[RNBackgroundDownloader] downloadFailed', id, rest)
  const task = tasksMap.get(id)
  task?.onError(rest)

  tasksMap.delete(id)
})

export function setHeaders (h = {}) {
  if (typeof h !== 'object')
    throw new Error('[RNBackgroundDownloader] headers must be an object')

  headers = h
}

export function checkForExistingDownloads () {
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

export function ensureDownloadsAreRunning () {
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

export function completeHandler (jobId: string) {
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
  progressInterval?: number,
}

export function download (options: DownloadOptions) {
  console.log('[RNBackgroundDownloader] download', options)
  if (!options.id || !options.url || !options.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  options.headers = { ...headers, ...(options.headers || {}) }

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {}

  options.destination = options.destination.replace('file://', '')

  if (options.isAllowedOverRoaming == null) options.isAllowedOverRoaming = true
  if (options.isAllowedOverMetered == null) options.isAllowedOverMetered = true
  if (options.progressInterval == null) options.progressInterval = 1000

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
  download,
  checkForExistingDownloads,
  ensureDownloadsAreRunning,
  completeHandler,
  setHeaders,
  directories,
}
