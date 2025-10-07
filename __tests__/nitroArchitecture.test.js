/**
 * Test to verify Nitro architecture support
 * Tests that the library properly detects Nitro modules when available,
 * and falls back to TurboModules or Bridge when Nitro is not installed
 */

describe('Nitro Architecture Support', () => {
  let consoleSpy

  beforeEach(() => {
    jest.resetModules()
    jest.clearAllMocks()

    consoleSpy = {
      log: jest.spyOn(console, 'log').mockImplementation(),
      warn: jest.spyOn(console, 'warn').mockImplementation(),
      error: jest.spyOn(console, 'error').mockImplementation(),
    }
  })

  afterEach(() => {
    consoleSpy.log.mockRestore()
    consoleSpy.warn.mockRestore()
    consoleSpy.error.mockRestore()
  })

  test('should have architecture information exported', () => {
    const RNBackgroundDownloader = require('../src/index')

    // Verify architecture is exported
    expect(RNBackgroundDownloader.architecture).toBeDefined()
    expect(RNBackgroundDownloader.architecture.type).toBeDefined()
    expect(typeof RNBackgroundDownloader.architecture.isNitro).toBe('boolean')
    expect(typeof RNBackgroundDownloader.architecture.isTurboModule).toBe('boolean')
    expect(typeof RNBackgroundDownloader.architecture.isBridge).toBe('boolean')
  })

  test('should export architecture in default export', () => {
    const RNBackgroundDownloader = require('../src/index').default

    expect(RNBackgroundDownloader.architecture).toBeDefined()
    expect(typeof RNBackgroundDownloader.architecture.type).toBe('string')
  })

  test('architecture type should be one of: Nitro, TurboModule, Bridge, or Unknown', () => {
    const RNBackgroundDownloader = require('../src/index')

    const validTypes = ['Nitro', 'TurboModule', 'Bridge', 'Unknown']
    expect(validTypes).toContain(RNBackgroundDownloader.architecture.type)
  })

  test('only one architecture flag should be true at a time', () => {
    const RNBackgroundDownloader = require('../src/index')

    const trueCount = [
      RNBackgroundDownloader.architecture.isNitro,
      RNBackgroundDownloader.architecture.isTurboModule,
      RNBackgroundDownloader.architecture.isBridge,
    ].filter(Boolean).length

    // Either one is true, or none (in case of Unknown)
    expect(trueCount).toBeLessThanOrEqual(1)
  })

  test('architecture type should match the boolean flags', () => {
    const RNBackgroundDownloader = require('../src/index')
    const { architecture } = RNBackgroundDownloader

    if (architecture.type === 'Nitro') {
      expect(architecture.isNitro).toBe(true)
      expect(architecture.isTurboModule).toBe(false)
      expect(architecture.isBridge).toBe(false)
    } else if (architecture.type === 'TurboModule') {
      expect(architecture.isNitro).toBe(false)
      expect(architecture.isTurboModule).toBe(true)
      expect(architecture.isBridge).toBe(false)
    } else if (architecture.type === 'Bridge') {
      expect(architecture.isNitro).toBe(false)
      expect(architecture.isTurboModule).toBe(false)
      expect(architecture.isBridge).toBe(true)
    } else {
      // Unknown
      expect(architecture.isNitro).toBe(false)
      expect(architecture.isTurboModule).toBe(false)
      expect(architecture.isBridge).toBe(false)
    }
  })

  test('should handle Nitro modules gracefully when not installed', () => {
    // Since react-native-nitro-modules is not installed in this test environment,
    // it should fall back to TurboModule or Bridge
    const RNBackgroundDownloader = require('../src/index')

    // Should not crash and should have fallen back
    expect(RNBackgroundDownloader.architecture.type).not.toBe('Nitro')
    expect(RNBackgroundDownloader.architecture.isNitro).toBe(false)
  })

  test('peerDependencies should include react-native-nitro-modules as optional', () => {
    const packageJson = require('../package.json')

    expect(packageJson.peerDependencies['react-native-nitro-modules']).toBe('*')
    expect(packageJson.peerDependenciesMeta['react-native-nitro-modules'].optional).toBe(true)
  })

  test('keywords should include nitro', () => {
    const packageJson = require('../package.json')

    expect(packageJson.keywords).toContain('nitro')
    expect(packageJson.keywords).toContain('nitro modules')
  })
})
