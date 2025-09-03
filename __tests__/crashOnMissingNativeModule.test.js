/**
 * Test to reproduce the specific crash scenario from issue #89
 * "Another new arch build issue" where both TurboModule and Bridge are unavailable
 * This should cause the error: "[RNBackgroundDownloader] Neither TurboModule nor Bridge module available"
 */

describe('Crash on Missing Native Module Fix', () => {
  let originalConsole

  beforeEach(() => {
    jest.resetModules()
    // Capture console methods to test error handling
    originalConsole = {
      warn: console.warn,
      error: console.error,
    }
    console.warn = jest.fn()
    console.error = jest.fn()
  })

  afterEach(() => {
    // Restore console methods
    console.warn = originalConsole.warn
    console.error = originalConsole.error
  })

  test('should not crash when both TurboModule and Bridge are unavailable', async () => {
    // Mock React Native to simulate the exact scenario from issue #89
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModuleRegistry.getEnforcing(...): \'RNBackgroundDownloader\' could not be found. Verify that a module by this name is registered in the native binary.')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: null, // Both TurboModule and Bridge are unavailable
      },
      NativeEventEmitter: jest.fn().mockImplementation(() => ({
        addListener: jest.fn(() => ({ remove: jest.fn() })),
        removeAllListeners: jest.fn(),
        removeSubscription: jest.fn(),
      })),
    }))

    // This should not throw/crash during module initialization
    expect(() => {
      const RNBackgroundDownloader = require('../src/index')

      // These operations should work safely without crashing
      expect(async () => await RNBackgroundDownloader.checkForExistingDownloads()).not.toThrow()
      expect(() => RNBackgroundDownloader.completeHandler('test-id')).not.toThrow()
      expect(() => RNBackgroundDownloader.download({
        id: 'test',
        url: 'https://example.com/file.zip',
        destination: '/tmp/file.zip',
      })).not.toThrow()
    }).not.toThrow()

    // Actually call the methods to test they work
    const RNBackgroundDownloader = require('../src/index')
    const result = await RNBackgroundDownloader.checkForExistingDownloads()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(0)

    // Verify the expected warning messages were logged
    expect(console.warn).toHaveBeenCalledWith(
      '[RNBackgroundDownloader] TurboModule not available, falling back to bridge:',
      expect.stringMatching(/could not be found/)
    )
    expect(console.warn).toHaveBeenCalledWith(
      '[RNBackgroundDownloader] Neither TurboModule nor Bridge module available, functionality will be limited'
    )
    expect(console.warn).toHaveBeenCalledWith(
      '[RNBackgroundDownloader] Native module not available, returning empty array'
    )
  })

  test('should handle NativeEventEmitter creation when native module is null', () => {
    // Mock the exact scenario from the issue
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModuleRegistry.getEnforcing(...): \'RNBackgroundDownloader\' could not be found.')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: null,
      },
      NativeEventEmitter: jest.fn().mockImplementation((nativeModule) => {
        // With the fixed implementation, nativeModule should be null and handled gracefully
        return {
          addListener: jest.fn(() => ({ remove: jest.fn() })),
          removeAllListeners: jest.fn(),
          removeSubscription: jest.fn(),
        }
      }),
    }))

    // Module should initialize without throwing
    expect(() => {
      require('../src/index')
    }).not.toThrow()

    // Should log the expected warning about using mock event emitter
    expect(console.warn).toHaveBeenCalledWith(
      '[RNBackgroundDownloader] Native module not available for event emitter, using mock'
    )
  })
})
