import {
  createDownloadTask,
  createUploadTask,
  setConfig,
} from '../src/index'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

const lastCallArgs = fn => fn.mock.calls[fn.mock.calls.length - 1][0]

const startDownload = (id, params = {}) => {
  createDownloadTask({
    id,
    url: 'https://example.com/file.zip',
    destination: '/tmp/file.zip',
    ...params,
  }).start()
  return lastCallArgs(RNBackgroundDownloaderNative.download)
}

const startUpload = (id, params = {}) => {
  createUploadTask({
    id,
    url: 'https://example.com/upload',
    source: '/tmp/file.pdf',
    ...params,
  }).start()
  return lastCallArgs(RNBackgroundDownloaderNative.upload)
}

describe('isAllowedOverMetered follows the global allowsCellularAccess', () => {
  afterEach(() => {
    setConfig({ allowsCellularAccess: true })
  })

  test('defaults to true', () => {
    expect(startDownload('cell-default').isAllowedOverMetered).toBe(true)
    expect(startUpload('cell-default-up').isAllowedOverMetered).toBe(true)
  })

  test('a task without the option inherits allowsCellularAccess: false', () => {
    setConfig({ allowsCellularAccess: false })

    expect(startDownload('cell-global-off').isAllowedOverMetered).toBe(false)
    expect(startUpload('cell-global-off-up').isAllowedOverMetered).toBe(false)
  })

  test('the task option overrides the global setting', () => {
    setConfig({ allowsCellularAccess: false })
    expect(startDownload('cell-task-on', { isAllowedOverMetered: true }).isAllowedOverMetered).toBe(true)

    setConfig({ allowsCellularAccess: true })
    expect(startDownload('cell-task-off', { isAllowedOverMetered: false }).isAllowedOverMetered).toBe(false)
    expect(startUpload('cell-task-off-up', { isAllowedOverMetered: false }).isAllowedOverMetered).toBe(false)
  })
})
