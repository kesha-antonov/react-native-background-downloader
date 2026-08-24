import {
  cleanup,
  createDownloadTask,
  createUploadTask,
  getExistingDownloadTasks,
  getExistingUploadTasks,
} from '../src/index'
import { TurboModuleRegistry } from 'react-native'

const nativeModule = TurboModuleRegistry.getEnforcing('RNBackgroundDownloader')

beforeEach(() => {
  cleanup()
  nativeModule.getExistingDownloadTasks.mockReset().mockResolvedValue([])
  nativeModule.getExistingUploadTasks.mockReset().mockResolvedValue([])
  nativeModule.setRuntimeReady.mockReset().mockResolvedValue([])
  nativeModule.acknowledgeRuntimeEvents.mockReset()
})

test('replays a download completion buffered across a runtime reload', async () => {
  nativeModule.setRuntimeReady.mockResolvedValue([{
    name: 'downloadComplete',
    key: 'download:reloaded-download',
    payload: {
      id: 'reloaded-download',
      location: '/tmp/reloaded-download.zip',
      bytesDownloaded: 1024,
      bytesTotal: 1024,
      metadata: '{"assetId":"asset-123"}',
    },
  }])

  const tasks = await getExistingDownloadTasks()
  const done = jest.fn()

  expect(tasks).toHaveLength(1)
  expect(tasks[0].state).toBe('DONE')
  expect(tasks[0].metadata).toEqual({ assetId: 'asset-123' })
  tasks[0].done(done)
  expect(done).toHaveBeenCalledWith({
    location: '/tmp/reloaded-download.zip',
    bytesDownloaded: 1024,
    bytesTotal: 1024,
  })
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['download:reloaded-download'])
  expect(nativeModule.setRuntimeReady).toHaveBeenCalledWith('download')
})

test('replays an upload failure buffered across a runtime reload', async () => {
  nativeModule.setRuntimeReady.mockResolvedValue([{
    name: 'onUploadFailed',
    key: 'upload:reloaded-upload',
    payload: {
      id: 'reloaded-upload',
      error: 'connection reset',
      errorCode: -1005,
      metadata: '{"recordId":"record-456"}',
    },
  }])

  const tasks = await getExistingUploadTasks()
  const failed = jest.fn()

  expect(tasks).toHaveLength(1)
  expect(tasks[0].state).toBe('FAILED')
  expect(tasks[0].metadata).toEqual({ recordId: 'record-456' })
  tasks[0].error(failed)
  expect(failed).toHaveBeenCalledWith({
    error: 'connection reset',
    errorCode: -1005,
  })
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['upload:reloaded-upload'])
  expect(nativeModule.setRuntimeReady).toHaveBeenCalledWith('upload')
})

test('activates event delivery after native task reconciliation', async () => {
  const order = []
  nativeModule.getExistingDownloadTasks.mockImplementation(async () => {
    order.push('reconciled')
    return []
  })
  nativeModule.setRuntimeReady.mockImplementation(async () => {
    order.push('ready')
    return []
  })

  await getExistingDownloadTasks()

  expect(order).toEqual(['reconciled', 'ready'])
})

test('re-arms native event delivery for every reconciliation', async () => {
  await getExistingDownloadTasks()
  await getExistingDownloadTasks()

  expect(nativeModule.setRuntimeReady).toHaveBeenCalledTimes(2)
  expect(nativeModule.setRuntimeReady).toHaveBeenNthCalledWith(1, 'download')
  expect(nativeModule.setRuntimeReady).toHaveBeenNthCalledWith(2, 'download')
})

test('download reconciliation leaves upload events buffered for the next runtime', async () => {
  const uploadEvent = {
    name: 'uploadComplete',
    key: 'upload:pending-upload',
    payload: {
      id: 'pending-upload',
      responseCode: 200,
      responseBody: '',
      bytesUploaded: 512,
      bytesTotal: 512,
    },
  }
  nativeModule.setRuntimeReady.mockImplementation(async family => family === 'upload' ? [uploadEvent] : [])

  await getExistingDownloadTasks()

  expect(nativeModule.acknowledgeRuntimeEvents).not.toHaveBeenCalled()
  expect(nativeModule.acknowledgeRuntimeEvents).not.toHaveBeenCalledWith(['upload:pending-upload'])

  cleanup()
  nativeModule.acknowledgeRuntimeEvents.mockClear()

  const uploads = await getExistingUploadTasks()

  expect(uploads).toHaveLength(1)
  expect(uploads[0].state).toBe('DONE')
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['upload:pending-upload'])
})

test('acknowledges a terminal event delivered to the active runtime', () => {
  const task = createDownloadTask({
    id: 'live-download',
    url: 'https://example.com/file',
    destination: '/tmp/live-download',
  })
  task.start()

  global.__RNBackgroundDownloaderEmitEvent('downloadComplete', {
    id: 'live-download',
    location: '/tmp/live-download',
    bytesDownloaded: 1024,
    bytesTotal: 1024,
    metadata: '{}',
  })

  expect(task.state).toBe('DONE')
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['download:live-download'])
})

test('keeps a same-id download retry created by an error handler attached', () => {
  const progress = jest.fn()
  let retry
  const task = createDownloadTask({
    id: 'retry-download',
    url: 'https://example.com/first',
    destination: '/tmp/retry-download',
  }).error(() => {
    retry = createDownloadTask({
      id: 'retry-download',
      url: 'https://example.com/retry',
      destination: '/tmp/retry-download',
    }).progress(progress)
    retry.start()
  })
  task.start()

  global.__RNBackgroundDownloaderEmitEvent('downloadFailed', {
    id: 'retry-download',
    error: 'connection reset',
    errorCode: -1,
    metadata: '{}',
  })
  global.__RNBackgroundDownloaderEmitEvent('downloadProgress', [{
    id: 'retry-download',
    bytesDownloaded: 512,
    bytesTotal: 1024,
  }])

  expect(retry.state).toBe('DOWNLOADING')
  expect(progress).toHaveBeenCalledWith({ bytesDownloaded: 512, bytesTotal: 1024 })
})

test('keeps a same-id upload retry created by a completion handler attached', () => {
  const progress = jest.fn()
  let retry
  const task = createUploadTask({
    id: 'retry-upload',
    url: 'https://example.com/first',
    source: '/tmp/first-upload',
  }).done(() => {
    retry = createUploadTask({
      id: 'retry-upload',
      url: 'https://example.com/retry',
      source: '/tmp/retry-upload',
    }).progress(progress)
    retry.start()
  })
  task.start()

  global.__RNBackgroundDownloaderEmitEvent('uploadComplete', {
    id: 'retry-upload',
    responseCode: 200,
    responseBody: '',
    bytesUploaded: 1024,
    bytesTotal: 1024,
    metadata: '{}',
  })
  global.__RNBackgroundDownloaderEmitEvent('uploadProgress', [{
    id: 'retry-upload',
    bytesUploaded: 512,
    bytesTotal: 1024,
  }])

  expect(retry.state).toBe('UPLOADING')
  expect(progress).toHaveBeenCalledWith({ bytesUploaded: 512, bytesTotal: 1024 })
})
