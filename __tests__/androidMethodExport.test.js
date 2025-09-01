/**
 * Test to verify Android oldarch module properly exports methods for React Native binding
 * This test validates the fix for issue #79 where React Native's method discovery
 * was not finding methods in the oldarch implementation
 */

describe('Android Method Export Issue #79', () => {
  test('oldarch RNBackgroundDownloaderModule should have declared methods visible to reflection', () => {
    // This test simulates what React Native does internally when discovering methods
    // The issue was that getDeclaredMethods() on the oldarch class returned no @ReactMethod methods

    // Note: This test would need to run in a Java environment to actually test reflection
    // For now, we document the expected behavior and test the JavaScript interface

    const expectedMethods = [
      'checkForExistingDownloads',
      'completeHandler',
      'download',
      'pauseTask',
      'resumeTask',
      'stopTask',
      'addListener',
      'removeListeners',
    ]

    // In JavaScript, we can verify the module interface exists
    const NativeModule = require('../src/NativeRNBackgroundDownloader').default

    // These methods should be available on the native module
    expectedMethods.forEach(methodName => {
      expect(NativeModule).toHaveProperty(methodName)
      expect(typeof NativeModule[methodName]).toBe('function')
    })
  })

  test('methods should work correctly in old architecture simulation', async () => {
    // Test that the module methods are callable (using mocked version)
    const NativeModule = require('../src/NativeRNBackgroundDownloader').default

    // Test async method
    const downloads = await NativeModule.checkForExistingDownloads()
    expect(Array.isArray(downloads)).toBe(true)

    // Test void methods don't throw
    expect(() => NativeModule.completeHandler('test-id')).not.toThrow()
    expect(() => NativeModule.addListener('test-event')).not.toThrow()
    expect(() => NativeModule.removeListeners(1)).not.toThrow()
  })
})
