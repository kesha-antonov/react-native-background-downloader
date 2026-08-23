import { NativeModules, Platform, TurboModuleRegistry, NativeEventEmitter, NativeModule } from 'react-native'
import { DownloadTask } from './DownloadTask'
import { UploadTask } from './UploadTask'
import { Config, DownloadParams, Headers, Metadata, TaskInfo, TaskInfoNative, UploadParams, UploadTaskInfo, UploadTaskInfoNative } from './types'
import { config, DEFAULT_PROGRESS_INTERVAL, DEFAULT_PROGRESS_MIN_BYTES, DEFAULT_NOTIFICATION_TEXTS } from './config'
import { log } from './logger'
import { getNotificationTextsForNative } from './notifications'
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
      'The package \'@anorak-games/react-native-background-downloader\' doesn\'t seem to be linked. Make sure: \n\n' +
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

type PendingDownloadTerminal =
  | { type: 'complete', data: DownloadCompleteEvent }
  | { type: 'failed', data: DownloadFailedEvent }

type PendingUploadTerminal =
  | { type: 'complete', data: UploadCompleteEvent }
  | { type: 'failed', data: UploadFailedEvent }

interface PendingDownloadEvents {
  begin?: DownloadBeginEvent
  progress?: DownloadProgressEvent
  terminal?: PendingDownloadTerminal
}

interface PendingUploadEvents {
  begin?: UploadBeginEvent
  progress?: UploadProgressEvent
  terminal?: PendingUploadTerminal
}

interface BufferedRuntimeEvent {
  name: string
  key: string
  payload: object
}

const pendingDownloadEvents = new Map<string, PendingDownloadEvents>()
const pendingUploadEvents = new Map<string, PendingUploadEvents>()
let runtimeEventsActivation: Promise<void> | null = null

function pendingDownload (id: string): PendingDownloadEvents {
  const pending = pendingDownloadEvents.get(id) ?? {}
  pendingDownloadEvents.set(id, pending)
  return pending
}

function pendingUpload (id: string): PendingUploadEvents {
  const pending = pendingUploadEvents.get(id) ?? {}
  pendingUploadEvents.set(id, pending)
  return pending
}

function handleDownloadBegin (data: DownloadBeginEvent) {
  const task = tasksMap.get(data.id)
  if (task) {
    const { id, ...params } = data
    log('downloadBegin', id, params)
    task.onBegin(params)
    return
  }
  if (!pendingDownload(data.id).terminal)
    pendingDownload(data.id).begin = data
}

function handleDownloadProgress (events: DownloadProgressEvent[]) {
  log('downloadProgress', events)
  for (const data of events) {
    const task = tasksMap.get(data.id)
    if (task)
      task.onProgress({ bytesDownloaded: data.bytesDownloaded, bytesTotal: data.bytesTotal })
    else if (!pendingDownload(data.id).terminal)
      pendingDownload(data.id).progress = data
  }
}

function handleDownloadComplete (data: DownloadCompleteEvent) {
  const task = tasksMap.get(data.id)
  if (!task) {
    pendingDownloadEvents.set(data.id, { terminal: { type: 'complete', data } })
    return
  }
  const { id, ...params } = data
  log('downloadComplete', id, params)
  task.onDone(params)
  tasksMap.delete(id)
}

function handleDownloadFailed (data: DownloadFailedEvent) {
  const task = tasksMap.get(data.id)
  if (!task) {
    pendingDownloadEvents.set(data.id, { terminal: { type: 'failed', data } })
    return
  }
  const { id, ...params } = data
  log('downloadFailed', id, params)
  task.onError(params)
  tasksMap.delete(id)
}

function handleUploadBegin (data: UploadBeginEvent) {
  const task = uploadTasksMap.get(data.id)
  if (task) {
    const { id, ...params } = data
    log('uploadBegin', id, params)
    task.onBegin(params)
    return
  }
  if (!pendingUpload(data.id).terminal)
    pendingUpload(data.id).begin = data
}

function handleUploadProgress (events: UploadProgressEvent[]) {
  log('uploadProgress', events)
  for (const data of events) {
    const task = uploadTasksMap.get(data.id)
    if (task)
      task.onProgress({ bytesUploaded: data.bytesUploaded, bytesTotal: data.bytesTotal })
    else if (!pendingUpload(data.id).terminal)
      pendingUpload(data.id).progress = data
  }
}

function handleUploadComplete (data: UploadCompleteEvent) {
  const task = uploadTasksMap.get(data.id)
  if (!task) {
    pendingUploadEvents.set(data.id, { terminal: { type: 'complete', data } })
    return
  }
  const { id, ...params } = data
  log('uploadComplete', id, params)
  task.onDone(params)
  uploadTasksMap.delete(id)
}

function handleUploadFailed (data: UploadFailedEvent) {
  const task = uploadTasksMap.get(data.id)
  if (!task) {
    pendingUploadEvents.set(data.id, { terminal: { type: 'failed', data } })
    return
  }
  const { id, ...params } = data
  log('uploadFailed', id, params)
  task.onError(params)
  uploadTasksMap.delete(id)
}

function handleBufferedRuntimeEvent ({ name, payload }: BufferedRuntimeEvent) {
  const eventName = name.startsWith('on')
    ? name.charAt(2).toLowerCase() + name.slice(3)
    : name

  switch (eventName) {
    case 'downloadBegin':
      handleDownloadBegin(payload as unknown as DownloadBeginEvent)
      break
    case 'downloadProgress':
      handleDownloadProgress([payload as unknown as DownloadProgressEvent])
      break
    case 'downloadComplete':
      handleDownloadComplete(payload as unknown as DownloadCompleteEvent)
      break
    case 'downloadFailed':
      handleDownloadFailed(payload as unknown as DownloadFailedEvent)
      break
    case 'uploadBegin':
      handleUploadBegin(payload as unknown as UploadBeginEvent)
      break
    case 'uploadProgress':
      handleUploadProgress([payload as unknown as UploadProgressEvent])
      break
    case 'uploadComplete':
      handleUploadComplete(payload as unknown as UploadCompleteEvent)
      break
    case 'uploadFailed':
      handleUploadFailed(payload as unknown as UploadFailedEvent)
      break
  }
}

function activateRuntimeEvents (): Promise<void> {
  if (runtimeEventsActivation)
    return runtimeEventsActivation

  const nativeModule = RNBackgroundDownloader!
  runtimeEventsActivation = nativeModule.setRuntimeReady()
    .then(events => {
      for (const event of events)
        handleBufferedRuntimeEvent(event)
      nativeModule.acknowledgeRuntimeEvents(events.map(event => event.key))
    })
    .catch(error => {
      runtimeEventsActivation = null
      throw error
    })
  return runtimeEventsActivation
}

function applyPendingDownloadEvents (task: DownloadTask) {
  const pending = pendingDownloadEvents.get(task.id)
  if (!pending) return
  pendingDownloadEvents.delete(task.id)

  if (pending.begin)
    task.onBegin({ expectedBytes: pending.begin.expectedBytes, headers: pending.begin.headers })
  if (pending.progress)
    task.onProgress({ bytesDownloaded: pending.progress.bytesDownloaded, bytesTotal: pending.progress.bytesTotal })
  if (pending.terminal?.type === 'complete') {
    const data = pending.terminal.data
    task.onDone({ location: data.location, bytesDownloaded: data.bytesDownloaded, bytesTotal: data.bytesTotal })
    tasksMap.delete(task.id)
  } else if (pending.terminal?.type === 'failed') {
    const data = pending.terminal.data
    task.onError({ error: data.error, errorCode: data.errorCode })
    tasksMap.delete(task.id)
  }
}

function applyPendingUploadEvents (task: UploadTask) {
  const pending = pendingUploadEvents.get(task.id)
  if (!pending) return
  pendingUploadEvents.delete(task.id)

  if (pending.begin)
    task.onBegin({ expectedBytes: pending.begin.expectedBytes })
  if (pending.progress)
    task.onProgress({ bytesUploaded: pending.progress.bytesUploaded, bytesTotal: pending.progress.bytesTotal })
  if (pending.terminal?.type === 'complete') {
    const data = pending.terminal.data
    task.onDone({
      responseCode: data.responseCode,
      responseBody: data.responseBody,
      bytesUploaded: data.bytesUploaded,
      bytesTotal: data.bytesTotal,
    })
    uploadTasksMap.delete(task.id)
  } else if (pending.terminal?.type === 'failed') {
    const data = pending.terminal.data
    task.onError({ error: data.error, errorCode: data.errorCode })
    uploadTasksMap.delete(task.id)
  }
}

function reconcilePendingDownloadEvents (tasks: DownloadTask[]) {
  for (const task of tasks)
    applyPendingDownloadEvents(task)

  for (const [id, pending] of Array.from(pendingDownloadEvents.entries())) {
    if (!pending.terminal) continue
    const destination = pending.terminal.type === 'complete' ? pending.terminal.data.location : undefined
    const task = new DownloadTask({ id, metadata: {} })
    task.destination = destination
    tasks.push(task)
    tasksMap.set(id, task)
    applyPendingDownloadEvents(task)
  }
}

function reconcilePendingUploadEvents (tasks: UploadTask[]) {
  for (const task of tasks)
    applyPendingUploadEvents(task)

  for (const [id, pending] of Array.from(pendingUploadEvents.entries())) {
    if (!pending.terminal) continue
    const task = new UploadTask({ id, metadata: {} })
    tasks.push(task)
    uploadTasksMap.set(id, task)
    applyPendingUploadEvents(task)
  }
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
  pendingDownloadEvents.clear()
  pendingUploadEvents.clear()
  runtimeEventsActivation = null
}

function initializeEventListeners () {
  if (eventListenersInitialized) return
  eventListenersInitialized = true

  if (isIOSNewArchitecture && turboModule) {
    // iOS new architecture: use EventEmitter from TurboModule spec
    turboModule.onDownloadBegin(handleDownloadBegin)
    turboModule.onDownloadProgress(handleDownloadProgress)
    turboModule.onDownloadComplete(handleDownloadComplete)
    turboModule.onDownloadFailed(handleDownloadFailed)

    // Upload events for new architecture (optional - may not exist in all versions)
    if (typeof turboModule.onUploadBegin === 'function') {
      turboModule.onUploadBegin?.(handleUploadBegin)
      turboModule.onUploadProgress?.(handleUploadProgress)
      turboModule.onUploadComplete?.(handleUploadComplete)
      turboModule.onUploadFailed?.(handleUploadFailed)
    }
  } else {
    // Old architecture: use NativeEventEmitter with the native module
    // RCTEventEmitter on native side requires NativeEventEmitter on JS side
    // RNBackgroundDownloader is guaranteed to be non-null here since initializeEventListeners
    // is only called after ensureNativeModuleInitialized() succeeds
    const eventEmitter = new NativeEventEmitter(RNBackgroundDownloader!)

    eventSubscriptions.push(
      eventEmitter.addListener('downloadBegin', handleDownloadBegin)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadProgress', handleDownloadProgress)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadComplete', handleDownloadComplete)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('downloadFailed', handleDownloadFailed)
    )

    // Upload events for old architecture
    eventSubscriptions.push(
      eventEmitter.addListener('uploadBegin', handleUploadBegin)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadProgress', handleUploadProgress)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadComplete', handleUploadComplete)
    )

    eventSubscriptions.push(
      eventEmitter.addListener('uploadFailed', handleUploadFailed)
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
  showCompletionNotification,
  showCancelAction,
  notificationsGrouping,
  iosDataProtection,
}: Config) {
  config.headers = headers

  if (iosDataProtection !== undefined)
    config.iosDataProtection = iosDataProtection

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

  // Android 14+ notification extras - both opt-in
  if (showCompletionNotification !== undefined)
    config.showCompletionNotification = showCompletionNotification

  if (showCancelAction !== undefined)
    config.showCancelAction = showCancelAction

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
      setNotificationGroupingConfig?: (config: {
        enabled: boolean
        showNotificationsEnabled: boolean
        showCompletionNotification: boolean
        showCancelAction: boolean
        mode: string
        texts: Record<string, string>
      }) => void
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
        showCompletionNotification: config.showCompletionNotification ?? false,
        showCancelAction: config.showCancelAction ?? false,
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

  await activateRuntimeEvents()
  reconcilePendingDownloadEvents(downloadTasks)

  return downloadTasks
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
  activateRuntimeEvents().catch(error => log('setRuntimeReady', error))

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

  await activateRuntimeEvents()
  reconcilePendingUploadEvents(uploadTasks)

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
  activateRuntimeEvents().catch(error => log('setRuntimeReady', error))

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
