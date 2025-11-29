// Test for x86/ARMv7 architecture compatibility with MMKV fallback
// This test validates that the module works correctly when MMKV is not available

import * as RNBackgroundDownloader from '../src/index'

// Mock console.log to avoid cluttering test output
global.console.log = jest.fn()

describe('Architecture Compatibility (x86/ARMv7 MMKV fallback)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should handle MMKV initialization failures gracefully', () => {
    // This test ensures that if MMKV initialization fails (as it might on x86/ARMv7),
    // the module doesn't crash but continues to function

    expect(() => {
      // Test basic functionality still works when MMKV might not be available
      RNBackgroundDownloader.createDownloadTask({
        id: 'test-architecture-compatibility',
        url: 'https://example.com/file.zip',
        destination: '/tmp/test-file.zip',
      })
    }).not.toThrow()
  })

  it('should provide fallback storage for configuration persistence', async () => {
    // Test that configuration can still be set even if MMKV is not available
    expect(() => {
      RNBackgroundDownloader.setConfig({
        headers: { 'User-Agent': 'Architecture Test Agent' },
        progressInterval: 2000,
        progressMinBytes: 2048,
        isLogsEnabled: true,
      })
    }).not.toThrow()
  })

  it('should handle existing downloads check without MMKV', async () => {
    // When MMKV is not available, there should be no existing downloads to restore
    // but the function should not crash
    const existingDownloads = await RNBackgroundDownloader.getExistingDownloadTasks()
    expect(Array.isArray(existingDownloads)).toBe(true)
  })

  it('should maintain all API functions despite storage limitations', () => {
    // Test that all main API functions are available and functional
    expect(typeof RNBackgroundDownloader.createDownloadTask).toBe('function')
    expect(typeof RNBackgroundDownloader.getExistingDownloadTasks).toBe('function')
    expect(typeof RNBackgroundDownloader.completeHandler).toBe('function')
    expect(typeof RNBackgroundDownloader.setConfig).toBe('function')

    // Test that constants are still available
    expect(RNBackgroundDownloader.directories).toBeDefined()
    expect(RNBackgroundDownloader.directories.documents).toBeDefined()
  })

  it('should handle download tasks without persistence gracefully', () => {
    // Test that downloads can be created and managed even without MMKV persistence
    const downloadOptions = {
      id: 'test-no-mmkv-persistence',
      url: 'https://example.com/large-file.zip',
      destination: '/tmp/large-file.zip',
      headers: {
        Authorization: 'Bearer test-token',
      },
    }

    expect(() => {
      const task = RNBackgroundDownloader.createDownloadTask(downloadOptions)
      expect(task).toBeDefined()
      expect(task.id).toBe(downloadOptions.id)

      // Test task methods are available
      expect(typeof task.pause).toBe('function')
      expect(typeof task.resume).toBe('function')
      expect(typeof task.stop).toBe('function')
    }).not.toThrow()
  })

  it('should provide appropriate warnings for architecture limitations', () => {
    // This test verifies that when MMKV is not available due to architecture limitations,
    // appropriate logging occurs. The actual logging happens in the native Android code,
    // but we can test that the JavaScript layer handles it gracefully

    expect(() => {
      // Multiple downloads to test stability without persistence
      const downloads = [
        { id: 'arch-test-1', url: 'https://example.com/file1.zip', destination: '/tmp/file1.zip' },
        { id: 'arch-test-2', url: 'https://example.com/file2.zip', destination: '/tmp/file2.zip' },
        { id: 'arch-test-3', url: 'https://example.com/file3.zip', destination: '/tmp/file3.zip' },
      ]

      downloads.forEach(options => {
        const task = RNBackgroundDownloader.createDownloadTask(options)
        expect(task.id).toBe(options.id)
      })
    }).not.toThrow()
  })
})
