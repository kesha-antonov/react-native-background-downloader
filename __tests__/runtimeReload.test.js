import {
  cleanup,
  getExistingDownloadTasks,
  getExistingUploadTasks,
} from '../src/index'
import { NativeModules } from 'react-native'

const nativeModule = NativeModules.RNBackgroundDownloader

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
    },
  }])

  const tasks = await getExistingDownloadTasks()
  const done = jest.fn()

  expect(tasks).toHaveLength(1)
  expect(tasks[0].state).toBe('DONE')
  tasks[0].done(done)
  expect(done).toHaveBeenCalledWith({
    location: '/tmp/reloaded-download.zip',
    bytesDownloaded: 1024,
    bytesTotal: 1024,
  })
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['download:reloaded-download'])
})

test('replays an upload failure buffered across a runtime reload', async () => {
  nativeModule.setRuntimeReady.mockResolvedValue([{
    name: 'onUploadFailed',
    key: 'upload:reloaded-upload',
    payload: {
      id: 'reloaded-upload',
      error: 'connection reset',
      errorCode: -1005,
    },
  }])

  const tasks = await getExistingUploadTasks()
  const failed = jest.fn()

  expect(tasks).toHaveLength(1)
  expect(tasks[0].state).toBe('FAILED')
  tasks[0].error(failed)
  expect(failed).toHaveBeenCalledWith({
    error: 'connection reset',
    errorCode: -1005,
  })
  expect(nativeModule.acknowledgeRuntimeEvents).toHaveBeenCalledWith(['upload:reloaded-upload'])
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
