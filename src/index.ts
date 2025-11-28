import { NativeModules, NativeEventEmitter, Platform } from 'react-native'
import DownloadTask from './DownloadTask'
import { Config, DownloadParams, Headers, TaskInfo, TaskInfoNative } from './types'
import type { Spec } from './NativeRNBackgroundDownloader'

// Try to get the native module using TurboModuleRegistry first (new architecture),
// then fall back to NativeModules (old architecture)
const isTurboModuleEnabled = global.__turboModuleProxy != null

type NativeModule = Spec & {
  TaskRunning: number
  TaskSuspended: number
  TaskCanceling: number
  TaskCompleted: number
  documents: string
}

let RNBackgroundDownloader: NativeModule
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
const DEFAULT_PROGRESS_MIN_BYTES = 1024 * 1024 // 1MB
const tasksMap = new Map<string, DownloadTask>()

interface ConfigState {
  headers: Headers
  progressInterval: number
  progressMinBytes: number
  isLogsEnabled: boolean
}

export const config: ConfigState = {
  headers: {},
  progressInterval: DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes: DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled: false,
}

export const log = (...args: unknown[]): void => {
  if (config.isLogsEnabled)
    console.log('[RNBackgroundDownloader]', ...args)
}

interface DownloadBeginEvent {
  id: string
  expectedBytes: number
  headers: Headers
}

interface DownloadProgressEvent {
  id: string
  bytesDownloaded: number
  bytesTotal: number
}

interface DownloadCompleteEvent {
  id: string
  bytesDownloaded: number
  bytesTotal: number
}

interface DownloadFailedEvent {
  id: string
  error: string
  errorCode: number
}

RNBackgroundDownloaderEmitter.addListener('downloadBegin', ({ id, ...rest }: DownloadBeginEvent) => {
  log('downloadBegin', id, rest)
  const task = tasksMap.get(id)
  task?.onBegin(rest)
})

RNBackgroundDownloaderEmitter.addListener('downloadProgress', (events: DownloadProgressEvent[]) => {
  log('downloadProgress-1', events, tasksMap)
  for (const event of events) {
    const { id, ...rest } = event
    const task = tasksMap.get(id)
    log('downloadProgress-2', id, task)
    task?.onProgress(rest)
  }
})

RNBackgroundDownloaderEmitter.addListener('downloadComplete', ({ id, ...rest }: DownloadCompleteEvent) => {
  log('downloadComplete', id, rest)
  const task = tasksMap.get(id)
  task?.onDone(rest)

  tasksMap.delete(id)
})

RNBackgroundDownloaderEmitter.addListener('downloadFailed', ({ id, ...rest }: DownloadFailedEvent) => {
  log('downloadFailed', id, rest)
  const task = tasksMap.get(id)
  task?.onError(rest)

  tasksMap.delete(id)
})

export function setConfig ({
  headers = {},
  progressInterval = DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes = DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled = false,
}: Config) {
  config.headers = headers

  if (progressInterval >= MIN_PROGRESS_INTERVAL)
    config.progressInterval = progressInterval
  else
    console.warn(`[RNBackgroundDownloader] progressInterval must be a number >= ${MIN_PROGRESS_INTERVAL}. You passed ${progressInterval}`)

  if (progressMinBytes >= 0)
    config.progressMinBytes = progressMinBytes
  else
    console.warn(`[RNBackgroundDownloader] progressMinBytes must be a number >= 0. You passed ${progressMinBytes}`)

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
  }).filter((task): task is DownloadTask => task !== undefined)

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
