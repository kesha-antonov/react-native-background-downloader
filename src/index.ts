import { NativeModules, NativeEventEmitter, Platform } from 'react-native'
import DownloadTask from './DownloadTask'
import { Config, DownloadParams, TaskInfo, TaskInfoNative } from './types'

// Try to get the native module using TurboModuleRegistry first (new architecture),
// then fall back to NativeModules (old architecture)
const isTurboModuleEnabled = global.__turboModuleProxy != null

let RNBackgroundDownloader
if (isTurboModuleEnabled)
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  RNBackgroundDownloader = require('./NativeRNBackgroundDownloader').default
else
  RNBackgroundDownloader = NativeModules.RNBackgroundDownloader

if (!RNBackgroundDownloader)
  throw new Error(
    'The package \'@kesha-antonov/react-native-background-downloader\' doesn\'t seem to be linked. Make sure: \n\n' +
    Platform.select({ ios: '- You have run \'pod install\'\n', default: '' }) +
    '- You rebuilt the app after installing the package\n' +
    '- You are not using Expo Go\n'
  )

const RNBackgroundDownloaderEmitter = new NativeEventEmitter(RNBackgroundDownloader)

const MIN_PROGRESS_INTERVAL = 250
const DEFAULT_PROGRESS_INTERVAL = 1000
const tasksMap = new Map()

export const config = {
  headers: {},
  progressInterval: DEFAULT_PROGRESS_INTERVAL,
  isLogsEnabled: false,
}

export const log = (...args) => {
  if (config.isLogsEnabled)
    console.log('[RNBackgroundDownloader]', ...args)
}

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, ...rest }) => {
  log('downloadBegin', id, rest)
  const task = tasksMap.get(id)
  task?.onBegin(rest)
})

RNBackgroundDownloaderEmitter.addListener('downloadProgress', events => {
  log('downloadProgress-1', events, tasksMap)
  for (const event of events) {
    const { id, ...rest } = event
    const task = tasksMap.get(id)
    log('downloadProgress-2', id, task)
    task?.onProgress(rest)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, ...rest }) => {
  log('downloadComplete', id, rest)
  const task = tasksMap.get(id)
  task?.onDone(rest)

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', ({ id, ...rest }) => {
  log('downloadFailed', id, rest)
  const task = tasksMap.get(id)
  task?.onError(rest)

  tasksMap.delete(id)
})

export function setConfig ({
  headers = {},
  progressInterval = DEFAULT_PROGRESS_INTERVAL,
  isLogsEnabled = false,
}: Config) {
  config.headers = headers

  if (progressInterval >= MIN_PROGRESS_INTERVAL)
    config.progressInterval = progressInterval
  else
    console.warn(`[RNBackgroundDownloader] progressInterval must be a number >= ${MIN_PROGRESS_INTERVAL}. You passed ${progressInterval}`)

  config.isLogsEnabled = isLogsEnabled
}

export const getExistingDownloadTasks = async (): Promise<DownloadTask[]> => {
  const downloads: TaskInfoNative[] = await RNBackgroundDownloader.getExistingDownloadTasks()
  const downloadTasks: DownloadTask[] = downloads.map(taskInfo => {
    // second argument re-assigns event handlers
    const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id))

    switch (taskInfo.state) {
      case RNBackgroundDownloader.TaskRunning: {
        task.state = 'DOWNLOADING'
        break
      }
      case RNBackgroundDownloader.TaskSuspended: {
        task.state = 'PAUSED'
        break
      }
      case RNBackgroundDownloader.TaskCanceling: {
        // On iOS, paused tasks (via cancelByProducingResumeData) are in Canceling state with errorCode -999
        if (taskInfo.errorCode === -999) {
          task.state = 'PAUSED'
        } else {
          task.stop()
          return undefined
        }
        break
      }
      case RNBackgroundDownloader.TaskCompleted: {
        if (taskInfo.bytesDownloaded === taskInfo.bytesTotal)
          task.state = 'DONE'
        else
          // IOS completed the download but it was not done.
          return undefined
      }
    }

    return task
  }).filter(task => !!task)

  for (const task of downloadTasks)
    tasksMap.set(task.id, task)

  return downloadTasks
}

export const ensureDownloadsAreRunning = async (): Promise<void> => {
  log('ensureDownloadsAreRunning')

  const tasks: DownloadTask[] = await getExistingDownloadTasks()
  const tasksDownloading = tasks.filter(task => task.state === 'DOWNLOADING')

  for (const task of tasksDownloading) {
    task.pause()
    task.resume()
  }
}

export const completeHandler = (jobId: string) => {
  if (jobId == null) {
    log('completeHandler: jobId is empty')
    return
  }

  return RNBackgroundDownloader.completeHandler(jobId)
}

export function createDownloadTask ({
  isAllowedOverRoaming = true,
  isAllowedOverMetered = true,
  isNotificationVisible = false,
  ...rest
}: TaskInfo & DownloadParams) {
  log('createDownloadTask', {
    isAllowedOverRoaming,
    isAllowedOverMetered,
    isNotificationVisible,
    ...rest,
  })

  if (!rest.id || !rest.url || !rest.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  rest.headers = { ...config.headers, ...rest.headers }

  rest.destination = rest.destination.replace('file://', '')

  const task = new DownloadTask({
    id: rest.id,
    metadata: rest.metadata,
  })

  task.setDownloadParams({
    isAllowedOverRoaming,
    isAllowedOverMetered,
    isNotificationVisible,
    ...rest,
  })

  tasksMap.set(rest.id, task)

  return task
}

export const directories = {
  documents: RNBackgroundDownloader.documents,
}

export default {
  setConfig,
  createDownloadTask,
  getExistingDownloadTasks,
  ensureDownloadsAreRunning,
  completeHandler,

  directories,
}
