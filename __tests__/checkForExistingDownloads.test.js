/**
 * Test for getExistingDownloadTasks function to ensure it works on both
 * new architecture (TurboModules) and old architecture (Bridge)
 */

import RNBackgroundDownloader from '../src/index'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('getExistingDownloadTasks', () => {
  test('getExistingDownloadTasks should be defined and callable', async () => {
    // Mock the native method to return an empty array
    RNBackgroundDownloaderNative.getExistingDownloadTasks.mockResolvedValue([])

    // This should not throw TypeError: getExistingDownloadTasks is not a function
    expect(typeof RNBackgroundDownloader.getExistingDownloadTasks).toBe('function')

    const result = await RNBackgroundDownloader.getExistingDownloadTasks()

    // Should return an array (even if empty)
    expect(Array.isArray(result)).toBe(true)
    expect(RNBackgroundDownloaderNative.getExistingDownloadTasks).toHaveBeenCalled()
  })

  test('getExistingDownloadTasks should handle existing downloads', async () => {
    const mockExistingDownloads = [
      {
        id: 'existing-download-1',
        state: 0, // TASK_RUNNING -> DOWNLOADING
        bytesDownloaded: 1024,
        bytesTotal: 2048,
        metadata: '{"filename": "test.zip"}',
      },
      {
        id: 'existing-download-2',
        state: 3, // TASK_COMPLETED -> DONE
        bytesDownloaded: 4096,
        bytesTotal: 4096,
        metadata: '{"filename": "complete.pdf"}',
      },
    ]

    RNBackgroundDownloaderNative.getExistingDownloadTasks.mockResolvedValue(mockExistingDownloads)

    const result = await RNBackgroundDownloader.getExistingDownloadTasks()

    expect(result).toHaveLength(2)
    expect(result[0].id).toBe('existing-download-1')
    expect(result[0].state).toBe('DOWNLOADING')
    expect(result[1].id).toBe('existing-download-2')
    expect(result[1].state).toBe('DONE')
  })
})
