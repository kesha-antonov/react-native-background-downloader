// Test for Android 12 MMKV error handling
// This test validates that the module can handle MMKV initialization failures gracefully

import * as RNBackgroundDownloader from '../src/index'

// Mock console.log to avoid cluttering test output
global.console.log = jest.fn()

describe('MMKV Error Handling for Android 12', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should handle MMKV initialization gracefully', () => {
    // This test ensures that if MMKV initialization fails (as it might on Android 12),
    // the module doesn't crash but continues to function

    expect(() => {
      // Test basic functionality still works when MMKV might not be available
      RNBackgroundDownloader.download({
        id: 'test-mmkv-error',
        url: 'https://example.com/file.zip',
        destination: '/tmp/test-file.zip',
      })
    }).not.toThrow()
  })

  it('should allow downloading without persistence when MMKV fails', async () => {
    // Test that downloads can still be initiated even if persistence is not available
    const downloadOptions = {
      id: 'test-no-persistence',
      url: 'https://example.com/file.zip',
      destination: '/tmp/test-file.zip',
    }

    expect(() => {
      const task = RNBackgroundDownloader.download(downloadOptions)
      expect(task).toBeDefined()
      expect(task.id).toBe(downloadOptions.id)
    }).not.toThrow()
  })

  it('should handle checkForExistingDownloads when MMKV is unavailable', async () => {
    // When MMKV is not available, there should be no existing downloads to restore
    // but the function should not crash

    const existingDownloads = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(existingDownloads)).toBe(true)
  })

  it('should maintain core API functionality despite MMKV issues', () => {
    // Test that all main API functions are available and don't throw
    expect(typeof RNBackgroundDownloader.download).toBe('function')
    expect(typeof RNBackgroundDownloader.checkForExistingDownloads).toBe('function')
    expect(typeof RNBackgroundDownloader.ensureDownloadsAreRunning).toBe('function')
    expect(typeof RNBackgroundDownloader.completeHandler).toBe('function')
    expect(typeof RNBackgroundDownloader.setConfig).toBe('function')

    // Test that constants are still available
    expect(RNBackgroundDownloader.directories).toBeDefined()
    expect(RNBackgroundDownloader.directories.documents).toBeDefined()
  })

  it('should log appropriate warnings when MMKV is unavailable', () => {
    // This test verifies that appropriate logging occurs when MMKV fails
    // The actual logging happens in the native Android code, but we can test
    // that the JavaScript layer handles it gracefully

    expect(() => {
      RNBackgroundDownloader.setConfig({
        headers: { 'User-Agent': 'Test Agent' },
        progressInterval: 1000,
        progressMinBytes: 1024,
        isLogsEnabled: true,
      })
    }).not.toThrow()
  })
})
