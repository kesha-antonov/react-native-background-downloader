import { NativeModules, Platform, TurboModuleRegistry, DeviceEventEmitter } from 'react-native'
import DownloadTask from './DownloadTask'
import { Config, DownloadParams, Headers, TaskInfo, TaskInfoNative } from './types'
import type { Spec } from './NativeRNBackgroundDownloader'

type NativeModule = Spec & {
  TaskRunning: number
  TaskSuspended: number
  TaskCanceling: number
  TaskCompleted: number
  documents: string
}

// Try to get the native module using TurboModuleRegistry first (new architecture),
// then fall back to NativeModules (old architecture)
let RNBackgroundDownloader: NativeModule

// Try TurboModules first
const turboModule = TurboModuleRegistry.get<Spec>('RNBackgroundDownloader')
const isNewArchitecture = turboModule != null

if (turboModule) {
  // TurboModules use getConstants() method
  const constants = turboModule.getConstants()
  console.log('[RNBackgroundDownloader] TurboModule constants:', constants)
  RNBackgroundDownloader = Object.assign(turboModule, constants) as NativeModule
} else {
  // Fall back to old architecture
  RNBackgroundDownloader = NativeModules.RNBackgroundDownloader
  console.log('[RNBackgroundDownloader] Old arch module:', RNBackgroundDownloader?.documents)

  // For old architecture, constants may need to be fetched via getConstants() as well
  if (RNBackgroundDownloader && !RNBackgroundDownloader.documents && typeof RNBackgroundDownloader.getConstants === 'function') {
    const constants = RNBackgroundDownloader.getConstants()
    console.log('[RNBackgroundDownloader] Fetched constants via getConstants():', constants)
    if (constants)
      Object.assign(RNBackgroundDownloader, constants)
  }
}

if (!RNBackgroundDownloader)
  throw new Error(
    'The package \'@kesha-antonov/react-native-background-downloader\' doesn\'t seem to be linked. Make sure: \n\n' +
    Platform.select({ ios: '- You have run \'pod install\'\n', default: '' }) +
    '- You rebuilt the app after installing the package\n' +
    '- You are not using Expo Go\n'
  )

console.log('[RNBackgroundDownloader] Using architecture:', isNewArchitecture ? 'New (TurboModules)' : 'Old (Bridge)')

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

// Set up event listeners based on architecture
console.log('[RNBackgroundDownloader] Setting up event listeners...')

if (isNewArchitecture && turboModule) {
  // New architecture: use EventEmitter from TurboModule spec
  console.log('[RNBackgroundDownloader] Using TurboModule EventEmitter subscriptions')

  turboModule.onDownloadBegin((data: DownloadBeginEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadBegin received:', data)
    const { id, ...rest } = data
    log('downloadBegin', id, rest)
    const task = tasksMap.get(id)
    task?.onBegin(rest)
  })

  turboModule.onDownloadProgress((events: DownloadProgressEvent[]) => {
    console.log('[RNBackgroundDownloader] EVENT downloadProgress received:', events)
    log('downloadProgress-1', events, tasksMap)
    for (const event of events) {
      const { id, ...rest } = event
      const task = tasksMap.get(id)
      log('downloadProgress-2', id, task)
      task?.onProgress(rest)
    }
  })

  turboModule.onDownloadComplete((data: DownloadCompleteEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadComplete received:', data)
    const { id, ...rest } = data
    log('downloadComplete', id, rest)
    const task = tasksMap.get(id)
    task?.onDone(rest)
    tasksMap.delete(id)
  })

  turboModule.onDownloadFailed((data: DownloadFailedEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadFailed received:', data)
    const { id, ...rest } = data
    log('downloadFailed', id, rest)
    const task = tasksMap.get(id)
    task?.onError(rest)
    tasksMap.delete(id)
  })
} else {
  // Old architecture: use DeviceEventEmitter
  console.log('[RNBackgroundDownloader] Using DeviceEventEmitter subscriptions')

  DeviceEventEmitter.addListener('downloadBegin', (data: DownloadBeginEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadBegin received:', data)
    const { id, ...rest } = data
    log('downloadBegin', id, rest)
    const task = tasksMap.get(id)
    task?.onBegin(rest)
  })

  DeviceEventEmitter.addListener('downloadProgress', (events: DownloadProgressEvent[]) => {
    console.log('[RNBackgroundDownloader] EVENT downloadProgress received:', events)
    log('downloadProgress-1', events, tasksMap)
    for (const event of events) {
      const { id, ...rest } = event
      const task = tasksMap.get(id)
      log('downloadProgress-2', id, task)
      task?.onProgress(rest)
    }
  })

  DeviceEventEmitter.addListener('downloadComplete', (data: DownloadCompleteEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadComplete received:', data)
    const { id, ...rest } = data
    log('downloadComplete', id, rest)
    const task = tasksMap.get(id)
    task?.onDone(rest)
    tasksMap.delete(id)
  })

  DeviceEventEmitter.addListener('downloadFailed', (data: DownloadFailedEvent) => {
    console.log('[RNBackgroundDownloader] EVENT downloadFailed received:', data)
    const { id, ...rest } = data
    log('downloadFailed', id, rest)
    const task = tasksMap.get(id)
    task?.onError(rest)
    tasksMap.delete(id)
  })
}

console.log('[RNBackgroundDownloader] Event listeners set up complete')

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
