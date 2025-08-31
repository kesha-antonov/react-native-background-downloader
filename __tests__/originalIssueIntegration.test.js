/**
 * Integration test to demonstrate the fix for the original issue:
 * "TypeError: _NativeRNBackgroundDownloader.default.checkForExistingDownloads is not a function (it is undefined)"
 *
 * This test simulates the exact scenario described in the issue.
 */

import RNBackgroundDownloader from '../src/index'
import NativeRNBackgroundDownloader from '../src/NativeRNBackgroundDownloader'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Original Issue Integration Test', () => {
  test('should not throw "TypeError: checkForExistingDownloads is not a function"', async () => {
    // This was the original error:
    // TypeError: _NativeRNBackgroundDownloader.default.checkForExistingDownloads is not a function (it is undefined)

    // Mock the native method
    RNBackgroundDownloaderNative.checkForExistingDownloads.mockResolvedValue([])

    // These calls should NOT throw TypeError anymore
    expect(() => {
      // Direct access to the native module (what was failing)
      expect(typeof NativeRNBackgroundDownloader.checkForExistingDownloads).toBe('function')
    }).not.toThrow()

    expect(() => {
      // Access through the main API (what users actually call)
      expect(typeof RNBackgroundDownloader.checkForExistingDownloads).toBe('function')
    }).not.toThrow()

    // The actual call should work without throwing
    expect(async () => {
      await RNBackgroundDownloader.checkForExistingDownloads()
    }).not.toThrow()

    // And should return the expected result
    await expect(RNBackgroundDownloader.checkForExistingDownloads()).resolves.toEqual([])
  })

  test('should handle the "attempt to resume downloads" scenario from the issue', async () => {
    // Mock existing downloads that need to be resumed
    const mockExistingDownloads = [
      {
        id: 'existing-download-to-resume',
        state: 1, // TASK_SUSPENDED (paused state)
        bytesDownloaded: 1024,
        bytesTotal: 2048,
        metadata: '{"filename": "resume-test.zip"}',
      },
    ]

    RNBackgroundDownloaderNative.checkForExistingDownloads.mockResolvedValue(mockExistingDownloads)

    // This is the typical flow mentioned in the issue: "attempt to resume downloads"
    const existingDownloads = await RNBackgroundDownloader.checkForExistingDownloads()

    expect(existingDownloads).toHaveLength(1)
    expect(existingDownloads[0].id).toBe('existing-download-to-resume')
    expect(existingDownloads[0].state).toBe('PAUSED') // Should map correctly

    // The download task should be resumable (this was the ultimate goal)
    const downloadTask = existingDownloads[0]
    expect(downloadTask).toBeDefined()
    expect(typeof downloadTask.resume).toBe('function')
  })
})
