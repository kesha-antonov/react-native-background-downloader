/**
 * Test to verify the fallback mechanism works for both architectures
 */

// Mock both TurboModuleRegistry and NativeModules
const mockTurboModule = {
  checkForExistingDownloads: jest.fn().mockResolvedValue([]),
  completeHandler: jest.fn(),
  download: jest.fn(),
}

const mockBridgeModule = {
  checkForExistingDownloads: jest.fn().mockResolvedValue([]),
  completeHandler: jest.fn(),
  download: jest.fn(),
}

// Create a clean module loader for testing
function createNativeModule (shouldUseTurbo = true) {
  // Reset modules to test different scenarios
  jest.resetModules()

  if (shouldUseTurbo)
    // Mock TurboModuleRegistry success scenario
    jest.doMock('react-native', () => ({
      TurboModuleRegistry: {
        getEnforcing: jest.fn().mockReturnValue(mockTurboModule),
      },
      NativeModules: {
        RNBackgroundDownloader: mockBridgeModule,
      },
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
    }))

  return require('../src/NativeRNBackgroundDownloader').default
}

describe('Architecture Fallback Mechanism', () => {
  afterEach(() => {
    jest.resetModules()
    jest.clearAllMocks()
  })

  test('should use TurboModule when available (New Architecture)', async () => {
    const nativeModule = createNativeModule(true)

    await nativeModule.checkForExistingDownloads()

    // Should have called the TurboModule version
    expect(mockTurboModule.checkForExistingDownloads).toHaveBeenCalled()
    expect(mockBridgeModule.checkForExistingDownloads).not.toHaveBeenCalled()
  })

  test('should fallback to NativeModules when TurboModule unavailable (Old Architecture)', async () => {
    const nativeModule = createNativeModule(false)

    await nativeModule.checkForExistingDownloads()

    // Should have fallen back to the Bridge module version
    expect(mockBridgeModule.checkForExistingDownloads).toHaveBeenCalled()
    expect(mockTurboModule.checkForExistingDownloads).not.toHaveBeenCalled()
  })

  test('should work with other methods too', () => {
    const turboModule = createNativeModule(true)
    const bridgeModule = createNativeModule(false)

    // Test TurboModule
    turboModule.completeHandler('test-id')
    expect(mockTurboModule.completeHandler).toHaveBeenCalledWith('test-id')

    // Test Bridge fallback
    bridgeModule.completeHandler('test-id-2')
    expect(mockBridgeModule.completeHandler).toHaveBeenCalledWith('test-id-2')
  })
})
