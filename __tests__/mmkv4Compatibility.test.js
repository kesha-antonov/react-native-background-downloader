// Test for react-native-mmkv 4+ compatibility
// This test validates that the library can work alongside react-native-mmkv 4+

import * as RNBackgroundDownloader from '../src/index'

// Mock console.log to avoid cluttering test output
global.console.log = jest.fn()

describe('MMKV 4+ Compatibility', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('should handle MMKV dependency updates gracefully', () => {
    // This test ensures that the updated MMKV dependency (mmkv-shared)
    // doesn't break existing functionality

    expect(() => {
      // Test basic functionality still works with updated dependency
      RNBackgroundDownloader.download({
        id: 'test-mmkv4-compatibility',
        url: 'https://example.com/file.zip',
        destination: '/tmp/test-file.zip',
      })
    }).not.toThrow()
  })

  it('should allow configuration changes with updated MMKV', async () => {
    // Test that configuration changes work with the updated dependency
    expect(() => {
      RNBackgroundDownloader.setConfig({
        headers: { 'User-Agent': 'MMKV4 Test Agent' },
        progressInterval: 500,
        progressMinBytes: 2048,
        isLogsEnabled: true,
      })
    }).not.toThrow()
  })

  it('should handle multiple downloads with updated MMKV dependency', () => {
    // Test multiple downloads to ensure stability with mmkv-shared
    const downloads = [
      { id: 'mmkv4-test-1', url: 'https://example.com/file1.zip', destination: '/tmp/file1.zip' },
      { id: 'mmkv4-test-2', url: 'https://example.com/file2.zip', destination: '/tmp/file2.zip' },
      { id: 'mmkv4-test-3', url: 'https://example.com/file3.zip', destination: '/tmp/file3.zip' },
    ]

    expect(() => {
      downloads.forEach(options => {
        const task = RNBackgroundDownloader.download(options)
        expect(task.id).toBe(options.id)
      })
    }).not.toThrow()
  })

  it('should maintain API compatibility with MMKV dependency change', () => {
    // Ensure all core API functions are still available
    expect(typeof RNBackgroundDownloader.download).toBe('function')
    expect(typeof RNBackgroundDownloader.getExistingDownloadTasks).toBe('function')
    expect(typeof RNBackgroundDownloader.ensureDownloadsAreRunning).toBe('function')
    expect(typeof RNBackgroundDownloader.completeHandler).toBe('function')
    expect(typeof RNBackgroundDownloader.setConfig).toBe('function')

    // Test that constants are still available
    expect(RNBackgroundDownloader.directories).toBeDefined()
    expect(RNBackgroundDownloader.directories.documents).toBeDefined()
  })

  it('should handle getExistingDownloadTasks with updated dependency', async () => {
    // Test that existing download restoration works with mmkv-shared
    const existingDownloads = await RNBackgroundDownloader.getExistingDownloadTasks()
    expect(Array.isArray(existingDownloads)).toBe(true)
  })
})
