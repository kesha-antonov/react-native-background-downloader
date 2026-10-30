import {
  createDownloadTask,
  setConfig,
} from '../src/index'
import { config } from '../src/config'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('setConfig only changes the options it is given', () => {
  test('a later call keeps headers, logging and progress settings', () => {
    setConfig({
      headers: { Authorization: 'Bearer token' },
      isLogsEnabled: true,
      progressInterval: 2000,
      progressMinBytes: 4096,
    })
    jest.clearAllMocks()

    setConfig({ maxParallelDownloads: 2 })

    expect(config.headers).toEqual({ Authorization: 'Bearer token' })
    expect(config.isLogsEnabled).toBe(true)
    expect(config.progressInterval).toBe(2000)
    expect(config.progressMinBytes).toBe(4096)
    expect(RNBackgroundDownloaderNative.setLogsEnabled).not.toHaveBeenCalled()
  })
})

describe('notification fields on createDownloadTask', () => {
  test('groupId, groupName and notificationTitle are sent in metadata', () => {
    createDownloadTask({
      id: 'group-fields',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
      metadata: { albumId: 7 },
      groupId: 'album',
      groupName: 'Summer',
      notificationTitle: 'Track 1',
    }).start()

    const args = RNBackgroundDownloaderNative.download.mock.calls.at(-1)[0]
    expect(JSON.parse(args.metadata)).toEqual({
      albumId: 7,
      groupId: 'album',
      groupName: 'Summer',
      notificationTitle: 'Track 1',
    })
    expect(args.groupId).toBeUndefined()
  })
})
