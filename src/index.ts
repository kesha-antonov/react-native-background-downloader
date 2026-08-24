import { TurboModuleRegistry } from 'react-native'
import { DownloadTask } from './DownloadTask'
import { UploadTask } from './UploadTask'
import { DownloadParams, Headers, Metadata, TaskInfo, TaskInfoNative, UploadParams, UploadTaskInfo, UploadTaskInfoNative } from './types'
import { configureLogging, log } from './logger'
import type { Spec } from './NativeRNBackgroundDownloader'

type RNBackgroundDownloaderModule = Spec & {
  TaskRunning: number
  TaskSuspended: number
  TaskCanceling: number
  TaskCompleted: number
  documents: string
  isLoggingEnabled: boolean
}

let RNBackgroundDownloader: RNBackgroundDownloaderModule | null = null
let isInitialized = false

/**
 * Lazily initialize the native module.
 * This is called on first actual use of the module, not at import time.
 * This prevents issues with module loading before React Native's bridge is ready.
 */
function ensureNativeModuleInitialized (): RNBackgroundDownloaderModule {
  if (isInitialized && RNBackgroundDownloader != null)
    return RNBackgroundDownloader

  const turboModule = TurboModuleRegistry.getEnforcing<Spec>('RNBackgroundDownloader')
  RNBackgroundDownloader = Object.assign(turboModule, turboModule.getConstants()) as RNBackgroundDownloaderModule

  configureLogging(RNBackgroundDownloader.isLoggingEnabled === true)
  isInitialized = true

  // Initialize event listeners after native module is ready
  initializeEventListeners()

  return RNBackgroundDownloader
}

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
  metadata?: string
}

interface DownloadFailedEvent {
  id: string
  error: string
  errorCode: number
  metadata?: string
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
  metadata?: string
}

interface UploadFailedEvent {
  id: string
  error: string
  errorCode: number
  metadata?: string
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
type TaskFamily = 'download' | 'upload'
let runtimeEventsActivations: Partial<Record<TaskFamily, Promise<BufferedRuntimeEvent[]>>> = {}
let downloadReconciliation: Promise<DownloadTask[]> | null = null
let uploadReconciliation: Promise<UploadTask[]> | null = null

function acknowledgeTerminalEvent (family: TaskFamily, id: string) {
  RNBackgroundDownloader?.acknowledgeRuntimeEvents([`${family}:${id}`])
}

function retireDownloadTask (task: DownloadTask) {
  if (tasksMap.get(task.id) === task)
    tasksMap.delete(task.id)
  acknowledgeTerminalEvent('download', task.id)
}

function retireUploadTask (task: UploadTask) {
  if (uploadTasksMap.get(task.id) === task)
    uploadTasksMap.delete(task.id)
  acknowledgeTerminalEvent('upload', task.id)
}

function parseEventMetadata (metadata?: string): Metadata {
  if (!metadata) return {}

  try {
    const parsed = JSON.parse(metadata) as unknown
    return parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Metadata
      : {}
  } catch {
    return {}
  }
}

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
  const params = { location: data.location, bytesDownloaded: data.bytesDownloaded, bytesTotal: data.bytesTotal }
  log('downloadComplete', data.id, params)
  retireDownloadTask(task)
  task.onDone(params)
}

function handleDownloadFailed (data: DownloadFailedEvent) {
  const task = tasksMap.get(data.id)
  if (!task) {
    pendingDownloadEvents.set(data.id, { terminal: { type: 'failed', data } })
    return
  }
  const params = { error: data.error, errorCode: data.errorCode }
  log('downloadFailed', data.id, params)
  retireDownloadTask(task)
  task.onError(params)
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
  const params = {
    responseCode: data.responseCode,
    responseBody: data.responseBody,
    bytesUploaded: data.bytesUploaded,
    bytesTotal: data.bytesTotal,
  }
  log('uploadComplete', data.id, params)
  retireUploadTask(task)
  task.onDone(params)
}

function handleUploadFailed (data: UploadFailedEvent) {
  const task = uploadTasksMap.get(data.id)
  if (!task) {
    pendingUploadEvents.set(data.id, { terminal: { type: 'failed', data } })
    return
  }
  const params = { error: data.error, errorCode: data.errorCode }
  log('uploadFailed', data.id, params)
  retireUploadTask(task)
  task.onError(params)
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

function isTerminalRuntimeEvent (name: string): boolean {
  return name.endsWith('Complete') || name.endsWith('Failed') ||
    name.endsWith('complete') || name.endsWith('failed')
}

function activateRuntimeEvents (family: TaskFamily): Promise<BufferedRuntimeEvent[]> {
  const currentActivation = runtimeEventsActivations[family]
  if (currentActivation)
    return currentActivation

  const nativeModule = RNBackgroundDownloader!
  const activation = nativeModule.setRuntimeReady(family)
    .then(events => {
      for (const event of events) {
        if (!isTerminalRuntimeEvent(event.name))
          nativeModule.acknowledgeRuntimeEvents([event.key])
        handleBufferedRuntimeEvent(event)
      }
      return events
    })
    .catch(error => {
      delete runtimeEventsActivations[family]
      throw error
    })
  runtimeEventsActivations[family] = activation
  return activation
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
    retireDownloadTask(task)
    task.onDone({ location: data.location, bytesDownloaded: data.bytesDownloaded, bytesTotal: data.bytesTotal })
  } else if (pending.terminal?.type === 'failed') {
    const data = pending.terminal.data
    retireDownloadTask(task)
    task.onError({ error: data.error, errorCode: data.errorCode })
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
    retireUploadTask(task)
    task.onDone({
      responseCode: data.responseCode,
      responseBody: data.responseBody,
      bytesUploaded: data.bytesUploaded,
      bytesTotal: data.bytesTotal,
    })
  } else if (pending.terminal?.type === 'failed') {
    const data = pending.terminal.data
    retireUploadTask(task)
    task.onError({ error: data.error, errorCode: data.errorCode })
  }
}

function reconcilePendingDownloadEvents (tasks: DownloadTask[]) {
  for (const task of tasks)
    applyPendingDownloadEvents(task)

  for (const [id, pending] of Array.from(pendingDownloadEvents.entries())) {
    if (!pending.terminal) continue
    const destination = pending.terminal.type === 'complete' ? pending.terminal.data.location : undefined
    const task = new DownloadTask({ id, metadata: parseEventMetadata(pending.terminal.data.metadata) })
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
    const task = new UploadTask({ id, metadata: parseEventMetadata(pending.terminal.data.metadata) })
    tasks.push(task)
    uploadTasksMap.set(id, task)
    applyPendingUploadEvents(task)
  }
}

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
  RNBackgroundDownloader = null
  tasksMap.clear()
  uploadTasksMap.clear()
  pendingDownloadEvents.clear()
  pendingUploadEvents.clear()
  runtimeEventsActivations = {}
  downloadReconciliation = null
  uploadReconciliation = null
}

function initializeEventListeners () {
  if (eventListenersInitialized) return
  eventListenersInitialized = true

  eventSubscriptions.push(
    RNBackgroundDownloader!.onDownloadBegin(handleDownloadBegin),
    RNBackgroundDownloader!.onDownloadProgress(handleDownloadProgress),
    RNBackgroundDownloader!.onDownloadComplete(handleDownloadComplete),
    RNBackgroundDownloader!.onDownloadFailed(handleDownloadFailed),
    RNBackgroundDownloader!.onUploadBegin(handleUploadBegin),
    RNBackgroundDownloader!.onUploadProgress(handleUploadProgress),
    RNBackgroundDownloader!.onUploadComplete(handleUploadComplete),
    RNBackgroundDownloader!.onUploadFailed(handleUploadFailed)
  )
}

async function reconcileDownloadTasks (): Promise<DownloadTask[]> {
  const nativeModule = ensureNativeModuleInitialized()
  delete runtimeEventsActivations.download
  let downloads: Awaited<ReturnType<Spec['getExistingDownloadTasks']>>
  try {
    downloads = await nativeModule.getExistingDownloadTasks()
  } catch (error) {
    activateRuntimeEvents('download').catch(activationError => log('setRuntimeReady', activationError))
    throw error
  }
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
    const task = new DownloadTask(taskInfo, { originalTask: tasksMap.get(taskInfo.id) })

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

  await activateRuntimeEvents('download')
  reconcilePendingDownloadEvents(downloadTasks)

  return downloadTasks
}

export const getExistingDownloadTasks = (): Promise<DownloadTask[]> => {
  if (downloadReconciliation) return downloadReconciliation
  const reconciliation = reconcileDownloadTasks().finally(() => {
    if (downloadReconciliation === reconciliation) downloadReconciliation = null
  })
  downloadReconciliation = reconciliation
  return reconciliation
}

export function createDownloadTask ({ metadata, ...rest }: TaskInfo & DownloadParams) {
  // Ensure native module and event listeners are initialized before creating tasks
  ensureNativeModuleInitialized()

  if (!rest.id || !rest.url || !rest.destination)
    throw new Error('[RNBackgroundDownloader] id, url and destination are required')

  rest.destination = rest.destination.replace('file://', '')

  const task = new DownloadTask({
    id: rest.id,
    metadata,
  }, { downloadParams: rest })

  tasksMap.set(rest.id, task)
  activateRuntimeEvents('download').catch(error => log('setRuntimeReady', error))

  return task
}

async function reconcileUploadTasks (): Promise<UploadTask[]> {
  const nativeModule = ensureNativeModuleInitialized()
  delete runtimeEventsActivations.upload
  let uploads: Awaited<ReturnType<Spec['getExistingUploadTasks']>>
  try {
    uploads = await nativeModule.getExistingUploadTasks()
  } catch (error) {
    activateRuntimeEvents('upload').catch(activationError => log('setRuntimeReady', activationError))
    throw error
  }
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

  await activateRuntimeEvents('upload')
  reconcilePendingUploadEvents(uploadTasks)

  return uploadTasks
}

export const getExistingUploadTasks = (): Promise<UploadTask[]> => {
  if (uploadReconciliation) return uploadReconciliation
  const reconciliation = reconcileUploadTasks().finally(() => {
    if (uploadReconciliation === reconciliation) uploadReconciliation = null
  })
  uploadReconciliation = reconciliation
  return reconciliation
}

export function createUploadTask ({ metadata, ...rest }: UploadTaskInfo & UploadParams) {
  // Ensure native module and event listeners are initialized before creating tasks
  ensureNativeModuleInitialized()

  if (!rest.id || !rest.url || !rest.source)
    throw new Error('[RNBackgroundDownloader] id, url and source are required')

  rest.source = rest.source.replace('file://', '')

  const task = new UploadTask({
    id: rest.id,
    metadata,
  })

  task.setUploadParams(rest)

  uploadTasksMap.set(rest.id, task)
  activateRuntimeEvents('upload').catch(error => log('setRuntimeReady', error))

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
