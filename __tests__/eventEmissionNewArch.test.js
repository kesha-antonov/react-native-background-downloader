/**
 * Test to verify event emission works in both architectures
 * Specifically addresses issue #53 where downloadBegin and downloadProgress events
 * weren't firing in React Native new architecture
 */

// Mock both TurboModuleRegistry and NativeModules with event emission capability
const mockTurboModule = {
  checkForExistingDownloads: jest.fn().mockResolvedValue([]),
  completeHandler: jest.fn(),
  download: jest.fn(),
  // Mock native module methods needed for event emitter
  addListener: jest.fn(),
  removeListeners: jest.fn(),
}

const mockBridgeModule = {
  checkForExistingDownloads: jest.fn().mockResolvedValue([]),
  completeHandler: jest.fn(),
  download: jest.fn(),
  // Mock native module methods needed for event emitter
  addListener: jest.fn(),
  removeListeners: jest.fn(),
}

// Mock NativeEventEmitter to track which native module is used
const mockNativeEventEmitter = jest.fn()
const mockEmitterInstance = {
  addListener: jest.fn(),
  removeAllListeners: jest.fn(),
  removeSubscription: jest.fn(),
}
mockNativeEventEmitter.mockReturnValue(mockEmitterInstance)

function setupMocks (shouldUseTurbo = true) {
  jest.resetModules()
  jest.clearAllMocks()

  if (shouldUseTurbo)
    // Mock TurboModuleRegistry success scenario
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockReturnValue(mockTurboModule),
      },
      NativeModules: {
        RNBackgroundDownloader: mockBridgeModule,
      },
      NativeEventEmitter: mockNativeEventEmitter,
    }))
  else
    // Mock TurboModuleRegistry failure scenario
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockImplementation(() => {
          throw new Error('TurboModule not available')
        }),
      },
      NativeModules: {
        RNBackgroundDownloader: mockBridgeModule,
      },
      NativeEventEmitter: mockNativeEventEmitter,
    }))
}

describe('Event Emission Architecture Compatibility', () => {
  afterEach(() => {
    jest.resetModules()
    jest.clearAllMocks()
  })

  test('should use TurboModule for event emitter in New Architecture', () => {
    setupMocks(true)

    // Import the main module to trigger event emitter creation
    require('../src/index')

    // Verify that NativeEventEmitter was called with the TurboModule instance
    expect(mockNativeEventEmitter).toHaveBeenCalledWith(mockTurboModule)
    expect(mockNativeEventEmitter).not.toHaveBeenCalledWith(mockBridgeModule)
  })

  test('should use Bridge module for event emitter in Old Architecture', () => {
    setupMocks(false)

    // Import the main module to trigger event emitter creation
    require('../src/index')

    // Verify that NativeEventEmitter was called with the Bridge module instance
    expect(mockNativeEventEmitter).toHaveBeenCalledWith(mockBridgeModule)
    expect(mockNativeEventEmitter).not.toHaveBeenCalledWith(mockTurboModule)
  })

  test('should properly register event listeners with correct native module', () => {
    setupMocks(true)

    // Import the main module to trigger event listener registration
    require('../src/index')

    // Verify that the event emitter instance is used to add listeners
    expect(mockEmitterInstance.addListener).toHaveBeenCalledWith('downloadBegin', expect.any(Function))
    expect(mockEmitterInstance.addListener).toHaveBeenCalledWith('downloadProgress', expect.any(Function))
    expect(mockEmitterInstance.addListener).toHaveBeenCalledWith('downloadComplete', expect.any(Function))
    expect(mockEmitterInstance.addListener).toHaveBeenCalledWith('downloadFailed', expect.any(Function))
  })

  test('event emission should work in both architectures', () => {
    // Test with New Architecture (TurboModule)
    setupMocks(true)
    require('../src/index')
    expect(mockNativeEventEmitter).toHaveBeenCalledWith(mockTurboModule)

    // Reset for Old Architecture test
    setupMocks(false)
    require('../src/index')
    expect(mockNativeEventEmitter).toHaveBeenCalledWith(mockBridgeModule)
  })
})
