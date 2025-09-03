/**
 * Test to verify the fix for the new architecture build issue.
 * This test ensures that checkForExistingDownloads and other functions
 * handle cases where the native module is not properly initialized
 * or methods are not available.
 */

const mockEventEmitter = () => ({
  addListener: () => ({ remove: () => {} }),
  removeAllListeners: () => {},
  removeSubscription: () => {},
})

describe('New Architecture Build Issue Fix', () => {
  let consoleSpy

  beforeEach(() => {
    jest.resetModules()
    consoleSpy = {
      warn: jest.spyOn(console, 'warn').mockImplementation(),
      error: jest.spyOn(console, 'error').mockImplementation(),
    }
  })

  afterEach(() => {
    consoleSpy.warn.mockRestore()
    consoleSpy.error.mockRestore()
  })

  test('should handle case where native module is null/undefined', async () => {
    // Mock scenario where native module is not available
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: null, // Simulate missing native module
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // checkForExistingDownloads should not throw and return empty array
    const result = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(0)
    expect(consoleSpy.warn).toHaveBeenCalledWith('[RNBackgroundDownloader] Native module not available, returning empty array')
  })

  test('should handle case where native module exists but methods are missing', async () => {
    // Mock scenario where native module exists but methods are missing
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: {
          // Missing checkForExistingDownloads method
          TaskRunning: 0,
          TaskSuspended: 1,
          TaskCanceling: 2,
          TaskCompleted: 3,
        },
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // checkForExistingDownloads should not throw and return empty array
    const result = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(0)
    expect(consoleSpy.warn).toHaveBeenCalledWith('[RNBackgroundDownloader] Bridge module missing required methods, functionality will be limited')
    expect(consoleSpy.warn).toHaveBeenCalledWith('[RNBackgroundDownloader] Native module not available, returning empty array')
  })

  test('should handle case where checkForExistingDownloads throws an error', async () => {
    // Mock scenario where method exists but throws an error
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: {
          checkForExistingDownloads: jest.fn().mockRejectedValue(new Error('Native method failed')),
          TaskRunning: 0,
          TaskSuspended: 1,
          TaskCanceling: 2,
          TaskCompleted: 3,
        },
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // checkForExistingDownloads should catch error and return empty array
    const result = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(0)
    expect(consoleSpy.error).toHaveBeenCalledWith('[RNBackgroundDownloader] Error in checkForExistingDownloads:', new Error('Native method failed'))
  })

  test('should handle case where checkForExistingDownloads returns non-array', async () => {
    // Mock scenario where method returns unexpected data
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: {
          checkForExistingDownloads: jest.fn().mockResolvedValue('not an array'),
          TaskRunning: 0,
          TaskSuspended: 1,
          TaskCanceling: 2,
          TaskCompleted: 3,
        },
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // checkForExistingDownloads should handle non-array and return empty array
    const result = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(0)
    expect(consoleSpy.warn).toHaveBeenCalledWith('[RNBackgroundDownloader] checkForExistingDownloads returned non-array, returning empty array:', 'not an array')
  })

  test('should handle download method gracefully when native module is unavailable', () => {
    // Mock scenario where native module is not available
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: null, // Simulate missing native module
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // download should not throw and return a task with error
    const task = RNBackgroundDownloader.download({
      id: 'test-download',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

    expect(task).toBeDefined()
    expect(task.id).toBe('test-download')
    expect(consoleSpy.error).toHaveBeenCalledWith('[RNBackgroundDownloader] Native module not available for download')
  })

  test('should handle completeHandler gracefully when native module is unavailable', () => {
    // Mock scenario where native module is not available
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: null,
      },
      NativeEventEmitter: jest.fn().mockImplementation(mockEventEmitter),
    }))

    const RNBackgroundDownloader = require('../src/index')

    // completeHandler should not throw
    expect(() => {
      RNBackgroundDownloader.completeHandler('test-id')
    }).not.toThrow()

    expect(consoleSpy.warn).toHaveBeenCalledWith('[RNBackgroundDownloader] Native module not available for completeHandler')
  })
})
