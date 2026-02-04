import { NativeModules, Platform, TurboModuleRegistry, NativeEventEmitter, NativeModule } from 'react-native'
import { DownloadTask } from './DownloadTask'
import { UploadTask } from './UploadTask'
import { Config, DownloadParams, Headers, Metadata, TaskInfo, TaskInfoNative, UploadParams, UploadTaskInfo, UploadTaskInfoNative } from './types'
import { config, log, DEFAULT_PROGRESS_INTERVAL, DEFAULT_PROGRESS_MIN_BYTES, getNotificationTextsForNative, DEFAULT_NOTIFICATION_TEXTS } from './config'
import type { Spec } from './NativeRNBackgroundDownloader'

type RNBackgroundDownloaderModule = Spec & {
  TaskRunning: number
  TaskSuspended: number
  TaskCanceling: number
  TaskCompleted: number
  documents: string
}

// Lazy initialization state
let RNBackgroundDownloader: (RNBackgroundDownloaderModule & NativeModule) | null = null
let turboModule: Spec | null = null
let isIOSNewArchitecture = false
let isInitialized = false

/**
 * Lazily initialize the native module.
 * This is called on first actual use of the module, not at import time.
 * This prevents issues with module loading before React Native's bridge is ready.
 */
function ensureNativeModuleInitialized (): RNBackgroundDownloaderModule & NativeModule {
  if (isInitialized && RNBackgroundDownloader != null)
    return RNBackgroundDownloader

  // Try TurboModules first
  turboModule = TurboModuleRegistry.get<Spec>('RNBackgroundDownloader')
  // Check if iOS new architecture event emitters are available
  // On Android, we always use NativeEventEmitter because Android uses RCTDeviceEventEmitter
  isIOSNewArchitecture = Platform.OS === 'ios' && turboModule != null && typeof turboModule.onDownloadBegin === 'function'

  if (isIOSNewArchitecture && turboModule) {
    // New architecture: TurboModules use getConstants() method
    const constants = turboModule.getConstants()
    RNBackgroundDownloader = Object.assign(turboModule, constants) as RNBackgroundDownloaderModule & NativeModule
  } else {
    // Fall back to old architecture - must use NativeModules for proper event emission
    RNBackgroundDownloader = NativeModules.RNBackgroundDownloader

    // For old architecture, constants may need to be fetched via getConstants() as well
    if (RNBackgroundDownloader && !RNBackgroundDownloader.documents && typeof RNBackgroundDownloader.getConstants === 'function') {
      const constants = RNBackgroundDownloader.getConstants()
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

  isInitialized = true

  // Initialize event listeners after native module is ready
  initializeEventListeners()

  return RNBackgroundDownloader
}

const MIN_PROGRESS_INTERVAL = 250
const tasksMap = new Map<string, DownloadTask>()
const uploadTasksMap = new Map<string, UploadTask>()

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
  location: string
  bytesDownloaded: number
  bytesTotal: number
}

interface DownloadFailedEvent {
  id: string
  error: string
  errorCode: number
}

// Upload event types
interface UploadBeginEvent {
  id: string
  expectedBytes: number
}

interface UploadProgressEvent {
  id: string
  bytesUploaded: number
  bytesTotal: number
}

interface UploadCompleteEvent {
  id: string
  responseCode: number
  responseBody: string
  bytesUploaded: number
  bytesTotal: number
}

interface UploadFailedEvent {
  id: string
  error: string
  errorCode: number
}

// Set up event listeners based on architecture
// For old architecture, we need to defer NativeEventEmitter creation
// to avoid issues during module initialization
let eventListenersInitialized = false
let eventSubscriptions: { remove: () => void }[] = []

/**
 * Clean up event listeners. Call this before hot reload or module invalidation.
 * This prevents memory leaks from accumulated event listeners.
 */
export function cleanup () {
  for (const subscription of eventSubscriptions)
    subscription.remove()

  eventSubscriptions = []
  eventListenersInitialized = false
  isInitialized = false
  // Clear module references to allow proper re-initialization
  RNBackgroundDownloader = null
  turboModule = null
  isIOSNewArchitecture = false
  tasksMap.clear()
  uploadTasksMap.clear()
}

function initializeEventListeners () {
  if (eventListenersInitialized) return
  eventListenersInitialized = true

  if (isIOSNewArchitecture && turboModule) {
    // iOS new architecture: use EventEmitter from TurboModule spec
    turboModule.onDownloadBegin((data: DownloadBeginEvent) => {
      const { id, ...rest } = data
      log('downloadBegin', id, rest)
      const task = tasksMap.get(id)
      if (!task) {
        log('downloadBegin: task not found in tasksMap', id)
        return
      }
      task.onBegin(rest)
    })

    turboModule.onDownloadProgress((events: DownloadProgressEvent[]) => {
      log('downloadProgress', events)
      for (const event of events) {
        const { id, ...rest } = event
        const task = tasksMap.get(id)
        if (task)
          task.onProgress(rest)
      }
    })

    turboModule.onDownloadComplete((data: DownloadCompleteEvent) => {
      const { id, ...rest } = data
      log('downloadComplete', id, rest)
      const task = tasksMap.get(id)
      if (!task)
        log('downloadComplete: task not found in tasksMap', id)
      else
        task.onDone(rest)
      tasksMap.delete(id)
    })

    turboModule.onDownloadFailed((data: DownloadFailedEvent) => {
      const { id, ...rest } = data
      log('downloadFailed', id, rest)
      const task = tasksMap.get(id)
      if (!task)
        log('downloadFailed: task not found in tasksMap', id)
      else
        task.onError(rest)
      tasksMap.delete(id)
    })

    // Upload events for new architecture (optional - may not exist in all versions)
    if (typeof turboModule.onUploadBegin === 'function') {
      turboModule.onUploadBegin?.((data: UploadBeginEvent) => {
        const { id, ...rest } = data
        log('uploadBegin', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task) {
          log('uploadBegin: task not found in uploadTasksMap', id)
          return
        }
        task.onBegin(rest)
      })

      turboModule.onUploadProgress?.((events: UploadProgressEvent[]) => {
        log('uploadProgress', events)
        for (const event of events) {
          const { id, ...rest } = event
          const task = uploadTasksMap.get(id)
          if (task)
            task.onProgress(rest)
        }
      })

      turboModule.onUploadComplete?.((data: UploadCompleteEvent) => {
        const { id, ...rest } = data
        log('uploadComplete', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task)
          log('uploadComplete: task not found in uploadTasksMap', id)
        else
          task.onDone(rest)
        uploadTasksMap.delete(id)
      })

      turboModule.onUploadFailed?.((data: UploadFailedEvent) => {
        const { id, ...rest } = data
        log('uploadFailed', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task)
          log('uploadFailed: task not found in uploadTasksMap', id)
        else
          task.onError(rest)
        uploadTasksMap.delete(id)
      })
    }
  } else {
    // Old architecture: use NativeEventEmitter with the native module
    // RCTEventEmitter on native side requires NativeEventEmitter on JS side
    // RNBackgroundDownloader is guaranteed to be non-null here since initializeEventListeners
    // is only called after ensureNativeModuleInitialized() succeeds
    const eventEmitter = new NativeEventEmitter(RNBackgroundDownloader!)

    eventSubscriptions.push(
      eventEmitter.addListener('downloadBegin', (data: DownloadBeginEvent) => {
        const { id, ...rest } = data
        log('downloadBegin', id, rest)
        const task = tasksMap.get(id)
        if (!task) {
          log('downloadBegin: task not found in tasksMap', id)
          return
        }
        task.onBegin(rest)
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadProgress', (events: DownloadProgressEvent[]) => {
        log('downloadProgress', events)
        for (const event of events) {
          const { id, ...rest } = event
          const task = tasksMap.get(id)
          if (task)
            task.onProgress(rest)
        }
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadComplete', (data: DownloadCompleteEvent) => {
        const { id, ...rest } = data
        log('downloadComplete', id, rest)
        const task = tasksMap.get(id)
        if (!task)
          log('downloadComplete: task not found in tasksMap', id)
        else
          task.onDone(rest)
        tasksMap.delete(id)
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadFailed', (data: DownloadFailedEvent) => {
        const { id, ...rest } = data
        log('downloadFailed', id, rest)
        const task = tasksMap.get(id)
        if (!task)
          log('downloadFailed: task not found in tasksMap', id)
        else
          task.onError(rest)
        tasksMap.delete(id)
      })
    )

    // Upload events for old architecture
    eventSubscriptions.push(
      eventEmitter.addListener('uploadBegin', (data: UploadBeginEvent) => {
        const { id, ...rest } = data
        log('uploadBegin', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task) {
          log('uploadBegin: task not found in uploadTasksMap', id)
          return
        }
        task.onBegin(rest)
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadProgress', (events: UploadProgressEvent[]) => {
        log('uploadProgress', events)
        for (const event of events) {
          const { id, ...rest } = event
          const task = uploadTasksMap.get(id)
          if (task)
            task.onProgress(rest)
        }
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadComplete', (data: UploadCompleteEvent) => {
        const { id, ...rest } = data
        log('uploadComplete', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task)
          log('uploadComplete: task not found in uploadTasksMap', id)
        else
          task.onDone(rest)
        uploadTasksMap.delete(id)
      })
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadFailed', (data: UploadFailedEvent) => {
        const { id, ...rest } = data
        log('uploadFailed', id, rest)
        const task = uploadTasksMap.get(id)
        if (!task)
          log('uploadFailed: task not found in uploadTasksMap', id)
        else
          task.onError(rest)
        uploadTasksMap.delete(id)
      })
    )

    // Native debug log events - forward native iOS logs to JS logCallback
    eventSubscriptions.push(
      eventEmitter.addListener('nativeDebugLog', (data: { message: string, taskId?: string }) => {
        log('[Native]', data.taskId || '', data.message)
      })
    )
  }
}

// Event listeners are now initialized lazily when ensureNativeModuleInitialized() is called
// This ensures the bridge is ready before any native module access

export function setConfig ({
  headers = {},
  progressInterval = DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes = DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled = false,
  logCallback,
  maxParallelDownloads,
  allowsCellularAccess,
  showNotificationsEnabled,
  notificationsGrouping,
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

  if (maxParallelDownloads !== undefined)
    if (maxParallelDownloads >= 1)
      config.maxParallelDownloads = maxParallelDownloads
    else
      console.warn(`[RNBackgroundDownloader] maxParallelDownloads must be a number >= 1. You passed ${maxParallelDownloads}`)

  if (allowsCellularAccess !== undefined)
    config.allowsCellularAccess = allowsCellularAccess

  // Update showNotificationsEnabled
  if (showNotificationsEnabled !== undefined)
    config.showNotificationsEnabled = showNotificationsEnabled

  // Update notification grouping config
  if (notificationsGrouping !== undefined)
    config.notificationsGrouping = {
      enabled: notificationsGrouping.enabled ?? false,
      mode: notificationsGrouping.mode ?? 'individual',
      texts: {
        ...DEFAULT_NOTIFICATION_TEXTS,
        ...notificationsGrouping.texts,
      },
    }

  config.isLogsEnabled = isLogsEnabled
  config.logCallback = logCallback

  // Notify native side about configuration changes
  try {
    const nativeModule = ensureNativeModuleInitialized() as RNBackgroundDownloaderModule & NativeModule & {
      setLogsEnabled?: (enabled: boolean) => void
      setMaxParallelDownloads?: (max: number) => void
      setAllowsCellularAccess?: (allows: boolean) => void
      setNotificationGroupingConfig?: (config: { enabled: boolean, showNotificationsEnabled: boolean, mode: string, texts: Record<string, string> }) => void
    }
    if (nativeModule.setLogsEnabled)
      nativeModule.setLogsEnabled(isLogsEnabled)
    // Only call native methods if config was successfully updated
    if (nativeModule.setMaxParallelDownloads && maxParallelDownloads !== undefined && maxParallelDownloads >= 1)
      nativeModule.setMaxParallelDownloads(config.maxParallelDownloads)
    if (nativeModule.setAllowsCellularAccess && allowsCellularAccess !== undefined)
      nativeModule.setAllowsCellularAccess(config.allowsCellularAccess)
    // Update notification config on native side (Android)
    if (Platform.OS === 'android' && nativeModule.setNotificationGroupingConfig)
      nativeModule.setNotificationGroupingConfig({
        enabled: config.notificationsGrouping.enabled,
        showNotificationsEnabled: config.showNotificationsEnabled ?? false,
        mode: config.notificationsGrouping.mode,
        texts: getNotificationTextsForNative(),
      })
  } catch {
    // Ignore if native module is not available yet
  }
}

export const getExistingDownloadTasks = async (): Promise<DownloadTask[]> => {
  const nativeModule = ensureNativeModuleInitialized()
  const downloads = await nativeModule.getExistingDownloadTasks()
  const downloadTasks: DownloadTask[] = downloads.map(downloadInfo => {
    // Parse metadata from JSON string to object
    let metadata: Metadata = {}
    if (downloadInfo.metadata)
      try {
        metadata = JSON.parse(downloadInfo.metadata) as Metadata
      } catch {
        // Keep empty object if parsing fails
      }

    const taskInfo: TaskInfoNative = {
      ...downloadInfo,
      metadata,
      errorCode: downloadInfo.errorCode ?? 0,
    }
    // second argument re-assigns event handlers
    const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id))

    switch (taskInfo.state) {
      case nativeModule.TaskRunning: {
        task.state = 'DOWNLOADING'
        break
      }
      case nativeModule.TaskSuspended: {
        task.state = 'PAUSED'
        break
      }
      case nativeModule.TaskCanceling: {
        // On iOS, paused tasks (via cancelByProducingResumeData) are in Canceling state with errorCode -999
        if (taskInfo.errorCode === -999) {
          task.state = 'PAUSED'
        } else {
          task.stop()
          return undefined
        }
        break
      }
      case nativeModule.TaskCompleted: {
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

export const completeHandler = (jobId: string) => {
  if (jobId == null) {
    log('completeHandler: jobId is empty')
    return
  }

  const nativeModule = ensureNativeModuleInitialized()
  return nativeModule.completeHandler(jobId)
}

export function createDownloadTask ({
  isAllowedOverRoaming = true,
  isAllowedOverMetered = true,
  metadata,
  ...rest
}: TaskInfo & DownloadParams) {
  // Ensure native module and event listeners are initialized before creating tasks
  ensureNativeModuleInitialized()

  if (!rest.id || !rest.url || !rest.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  rest.headers = { ...config.headers, ...rest.headers }

  rest.destination = rest.destination.replace('file://', '')

  const task = new DownloadTask({
    id: rest.id,
    metadata,
  })

  task.setDownloadParams({
    isAllowedOverRoaming,
    isAllowedOverMetered,
    ...rest,
  })

  tasksMap.set(rest.id, task)

  return task
}

export const getExistingUploadTasks = async (): Promise<UploadTask[]> => {
  const nativeModule = ensureNativeModuleInitialized()
  if (!nativeModule.getExistingUploadTasks) {
    log('getExistingUploadTasks: not supported - native implementation missing')
    return []
  }

  const uploads = await nativeModule.getExistingUploadTasks()
  const uploadTasks: UploadTask[] = uploads.map(uploadInfo => {
    // Parse metadata from JSON string to object
    let metadata: Metadata = {}
    if (uploadInfo.metadata)
      try {
        metadata = JSON.parse(uploadInfo.metadata) as Metadata
      } catch {
        // Keep empty object if parsing fails
      }

    const taskInfo: UploadTaskInfoNative = {
      ...uploadInfo,
      metadata,
      errorCode: uploadInfo.errorCode ?? 0,
    }
    // second argument re-assigns event handlers
    const task = new UploadTask(taskInfo, uploadTasksMap.get(taskInfo.id))

    switch (taskInfo.state) {
      case nativeModule.TaskRunning: {
        task.state = 'UPLOADING'
        break
      }
      case nativeModule.TaskSuspended: {
        task.state = 'PAUSED'
        break
      }
      case nativeModule.TaskCanceling: {
        // On iOS, paused tasks (via cancelByProducingResumeData) are in Canceling state with errorCode -999
        if (taskInfo.errorCode === -999) {
          task.state = 'PAUSED'
        } else {
          task.stop()
          return undefined
        }
        break
      }
      case nativeModule.TaskCompleted: {
        if (taskInfo.bytesUploaded === taskInfo.bytesTotal)
          task.state = 'DONE'
        else
          // IOS completed the upload but it was not done.
          return undefined
      }
    }

    return task
  }).filter((task): task is UploadTask => task !== undefined)

  for (const task of uploadTasks)
    uploadTasksMap.set(task.id, task)

  return uploadTasks
}

export function createUploadTask ({
  isAllowedOverRoaming = true,
  isAllowedOverMetered = true,
  metadata,
  ...rest
}: UploadTaskInfo & UploadParams) {
  // Ensure native module and event listeners are initialized before creating tasks
  ensureNativeModuleInitialized()

  if (!rest.id || !rest.url || !rest.source)
    throw new Error('[RNBackgroundDownloader] id, url and source are required')

  rest.headers = { ...config.headers, ...rest.headers }

  rest.source = rest.source.replace('file://', '')

  const task = new UploadTask({
    id: rest.id,
    metadata,
  })

  task.setUploadParams({
    isAllowedOverRoaming,
    isAllowedOverMetered,
    ...rest,
  })

  uploadTasksMap.set(rest.id, task)

  return task
}

// Use getter to lazily initialize native module when directories are accessed
export const directories = {
  get documents () {
    return ensureNativeModuleInitialized().documents
  },
}

/**
 * Get the native module instance.
 * This is exported for internal use by DownloadTask to avoid duplicating
 * the TurboModule/NativeModule lookup logic.
 * @internal
 */
export function getNativeModule (): Spec {
  return ensureNativeModuleInitialized()
}

export type * from './types'
