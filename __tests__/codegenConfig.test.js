/**
 * Test to verify that codegenConfig is properly configured for New Architecture
 */

const packageJson = require('../package.json')

describe('CodegenConfig for New Architecture', () => {
  test('package.json should have codegenConfig', () => {
    expect(packageJson.codegenConfig).toBeDefined()
    expect(typeof packageJson.codegenConfig).toBe('object')
  })

  test('codegenConfig should have correct name', () => {
    expect(packageJson.codegenConfig.name).toBe('RNBackgroundDownloaderSpec')
  })

  test('codegenConfig should have correct type', () => {
    expect(packageJson.codegenConfig.type).toBe('modules')
  })

  test('codegenConfig should point to correct source directory', () => {
    expect(packageJson.codegenConfig.jsSrcsDir).toBe('src')
  })

  test('TypeScript Spec interface should exist', () => {
    // This test ensures the TypeScript interface exists and can be imported
    const NativeModule = require('../src/NativeRNBackgroundDownloader')
    expect(NativeModule.default).toBeDefined()
  })
})
