import {
  cleanup,
  createDownloadTask,
  getExistingDownloadTasks,
  getExistingUploadTasks,
} from '../src/index'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

const NEW_ARCH_EVENTS = [
  'onDownloadBegin',
  'onDownloadProgress',
  'onDownloadComplete',
  'onDownloadFailed',
  'onUploadBegin',
  'onUploadProgress',
  'onUploadComplete',
  'onUploadFailed',
]

const subscriptions = () => NEW_ARCH_EVENTS.flatMap(name =>
  RNBackgroundDownloaderNative[name].mock.results.map(result => result.value)
)

const startDownload = id => createDownloadTask({
  id,
  url: 'https://example.com/file.zip',
  destination: '/tmp/file.zip',
}).start()

describe('cleanup', () => {
  beforeEach(() => {
    cleanup()
    jest.clearAllMocks()
  })

  test('removes the iOS new-architecture event subscriptions', () => {
    startDownload('cleanup-1')
    const added = subscriptions()
    expect(added).toHaveLength(NEW_ARCH_EVENTS.length)

    cleanup()

    for (const subscription of added)
      expect(subscription.remove).toHaveBeenCalledTimes(1)
  })

  test('re-initializing after cleanup subscribes once per event', () => {
    startDownload('cleanup-2')
    cleanup()
    startDownload('cleanup-3')

    const live = subscriptions().filter(subscription => subscription.remove.mock.calls.length === 0)
    expect(live).toHaveLength(NEW_ARCH_EVENTS.length)
  })
})

describe('restoring a canceled task', () => {
  let unhandled

  const onUnhandled = reason => unhandled.push(reason)

  beforeEach(() => {
    unhandled = []
    process.on('unhandledRejection', onUnhandled)
  })

  afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
  })

  // Unhandled rejections are reported after the microtask queue drains
  const flush = () => new Promise(resolve => setTimeout(resolve, 0))

  test('a failed stop of a canceled download is not an unhandled rejection', async () => {
    RNBackgroundDownloaderNative.stopTask.mockRejectedValueOnce(new Error('no such task'))

    const tasks = await getExistingDownloadTasks()
    await flush()

    expect(RNBackgroundDownloaderNative.stopTask).toHaveBeenCalledWith('taskCancelled')
    expect(tasks.map(task => task.id)).not.toContain('taskCancelled')
    expect(unhandled).toEqual([])
  })

  test('a failed stop of a canceled upload is not an unhandled rejection', async () => {
    RNBackgroundDownloaderNative.getExistingUploadTasks.mockResolvedValueOnce([{
      id: 'uploadCancelled',
      metadata: '{}',
      state: RNBackgroundDownloaderNative.TaskCanceling,
      bytesUploaded: 10,
      bytesTotal: 100,
    }])
    RNBackgroundDownloaderNative.stopUploadTask.mockRejectedValueOnce(new Error('no such task'))

    const tasks = await getExistingUploadTasks()
    await flush()

    expect(RNBackgroundDownloaderNative.stopUploadTask).toHaveBeenCalledWith('uploadCancelled')
    expect(tasks).toEqual([])
    expect(unhandled).toEqual([])
  })
})
