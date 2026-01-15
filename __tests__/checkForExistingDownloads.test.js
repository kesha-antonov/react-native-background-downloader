/**
 * Test for getExistingDownloadTasks function to ensure it works on both
 * new architecture (TurboModules) and old architecture (Bridge)
 */

import { getExistingDownloadTasks, createDownloadTask } from '../src/index'
import { NativeModules } from 'react-native'

const { RNBackgroundDownloader } = NativeModules

describe('getExistingDownloadTasks', () => {
  test('getExistingDownloadTasks should be defined and callable', async () => {
    // Mock the native method to return an empty array
    RNBackgroundDownloader.getExistingDownloadTasks.mockResolvedValue([])

    // This should not throw TypeError: getExistingDownloadTasks is not a function
    expect(typeof getExistingDownloadTasks).toBe('function')

    const result = await getExistingDownloadTasks()

    // Should return an array (even if empty)
    expect(Array.isArray(result)).toBe(true)
    expect(RNBackgroundDownloader.getExistingDownloadTasks).toHaveBeenCalled()
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

    RNBackgroundDownloader.getExistingDownloadTasks.mockResolvedValue(mockExistingDownloads)

    const result = await getExistingDownloadTasks()

    expect(result).toHaveLength(2)
    expect(result[0].id).toBe('existing-download-1')
    expect(result[0].state).toBe('DOWNLOADING')
    expect(result[1].id).toBe('existing-download-2')
    expect(result[1].state).toBe('DONE')
  })

  test('getExistingDownloadTasks should return paused tasks with errorCode -999', async () => {
    // This tests the fix for paused tasks not being returned after app restart
    // On iOS, paused tasks have state=Canceling (2) and errorCode=-999
    const mockExistingDownloads = [
      {
        id: 'paused-download-1',
        state: 2, // TASK_CANCELING -> should be PAUSED if errorCode is -999
        bytesDownloaded: 512,
        bytesTotal: 2048,
        metadata: '{"filename": "paused.zip"}',
        errorCode: -999, // NSURLErrorCancelled - indicates paused, not failed
      },
    ]

    RNBackgroundDownloader.getExistingDownloadTasks.mockResolvedValue(mockExistingDownloads)

    const result = await getExistingDownloadTasks()

    expect(result).toHaveLength(1)
    expect(result[0].id).toBe('paused-download-1')
    expect(result[0].state).toBe('PAUSED')
    expect(result[0].bytesDownloaded).toBe(512)
    expect(result[0].bytesTotal).toBe(2048)
  })

  test('getExistingDownloadTasks should return suspended tasks as PAUSED', async () => {
    // TaskSuspended (1) should also be returned as PAUSED
    const mockExistingDownloads = [
      {
        id: 'suspended-download-1',
        state: 1, // TASK_SUSPENDED -> PAUSED
        bytesDownloaded: 1000,
        bytesTotal: 5000,
        metadata: '{}',
      },
    ]

    RNBackgroundDownloader.getExistingDownloadTasks.mockResolvedValue(mockExistingDownloads)

    const result = await getExistingDownloadTasks()

    expect(result).toHaveLength(1)
    expect(result[0].id).toBe('suspended-download-1')
    expect(result[0].state).toBe('PAUSED')
  })

  test('getExistingDownloadTasks should preserve event handlers from original task', async () => {
    // First create a task with handlers
    const originalTask = createDownloadTask({
      id: 'handler-test',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

    const beginHandler = jest.fn()
    const progressHandler = jest.fn()
    const doneHandler = jest.fn()
    const errorHandler = jest.fn()

    originalTask.begin(beginHandler)
    originalTask.progress(progressHandler)
    originalTask.done(doneHandler)
    originalTask.error(errorHandler)

    // Mock native to return task with same id
    RNBackgroundDownloader.getExistingDownloadTasks.mockResolvedValue([
      {
        id: 'handler-test',
        state: 0, // TASK_RUNNING
        bytesDownloaded: 100,
        bytesTotal: 1000,
        metadata: '{}',
      },
    ])

    const result = await getExistingDownloadTasks()

    expect(result).toHaveLength(1)
    expect(result[0].id).toBe('handler-test')
    // Handlers should be preserved from original task
    expect(result[0].beginHandler).toBe(beginHandler)
    expect(result[0].progressHandler).toBe(progressHandler)
    expect(result[0].doneHandler).toBe(doneHandler)
    expect(result[0].errorHandler).toBe(errorHandler)
  })
})
