// Test for Expo compatibility functionality
// This test validates that the Expo config plugin works correctly

describe('Expo Compatibility', () => {
  it('should have expo plugin configuration in package.json', () => {
    const pkg = require('../package.json')
    expect(pkg.expo).toBeDefined()
    expect(pkg.expo.plugin).toBe('./plugin/build/index.js')
  })

  it('should be able to load the expo plugin', () => {
    expect(() => {
      // This would load the plugin in a real Expo environment
      const plugin = require('../plugin/build/index.js')
      expect(plugin).toBeDefined()
      expect(typeof plugin.default).toBe('function')
    }).not.toThrow()
  })

  it('should include plugin files in package files', () => {
    const pkg = require('../package.json')
    expect(pkg.files).toContain('plugin/build')
    expect(pkg.files).toContain('plugin/package.json')
  })

  it('should have the correct build scripts for the plugin', () => {
    const pkg = require('../package.json')
    expect(pkg.scripts['build:plugin']).toBe('tsc --project plugin/tsconfig.json')
    expect(pkg.scripts['clean:plugin']).toBe('rm -rf plugin/build')
    expect(pkg.scripts.prepublishOnly).toContain('build:plugin')
    expect(pkg.scripts.prepublishOnly).toContain('clean:plugin')
  })
})
