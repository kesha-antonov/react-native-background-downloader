/**
 * Test to verify 16KB memory page size support configuration
 * This test ensures that the library is properly configured to support
 * Android 15+ 16KB memory page size requirements.
 */

import { describe, test, expect } from '@jest/globals'

describe('16KB Memory Page Size Support', () => {
  test('should have Android build configuration for 16KB page alignment', () => {
    // This test verifies that the build configuration files contain
    // the necessary settings for 16KB page size support

    const fs = require('fs')
    const path = require('path')

    // Check main library build.gradle
    const buildGradlePath = path.join(__dirname, '../android/build.gradle')
    const buildGradleContent = fs.readFileSync(buildGradlePath, 'utf8')

    // Should have updated targetSdkVersion and compileSdkVersion to 36
    expect(buildGradleContent).toMatch(/targetSdkVersion.*36/)
    expect(buildGradleContent).toMatch(/compileSdkVersion.*36/)

    // Should have NDK configuration for supported architectures
    expect(buildGradleContent).toMatch(/abiFilters.*arm64-v8a/)

    // Should have packaging options for proper native lib handling
    expect(buildGradleContent).toMatch(/packagingOptions/)
    expect(buildGradleContent).toMatch(/useLegacyPackaging.*false/)

    // Should use updated MMKV version that supports 16KB page sizes
    expect(buildGradleContent).toMatch(/mmkv-shared:2\.2\.0/)
  })

  test('should have gradle.properties configured for 16KB support', () => {
    const fs = require('fs')
    const path = require('path')

    // Check main library gradle.properties
    const gradlePropsPath = path.join(__dirname, '../android/gradle.properties')
    const gradlePropsContent = fs.readFileSync(gradlePropsPath, 'utf8')

    // Should have uncompressed native libs setting
    expect(gradlePropsContent).toMatch(/android\.bundle\.enableUncompressedNativeLibs=false/)

    // Should have AndroidX enabled
    expect(gradlePropsContent).toMatch(/android\.useAndroidX=true/)
  })

  test('should have example app configured for 16KB support', () => {
    const fs = require('fs')
    const path = require('path')

    // Check example app build configuration
    const exampleBuildGradlePath = path.join(__dirname, '../example/android/build.gradle')
    const exampleBuildGradleContent = fs.readFileSync(exampleBuildGradlePath, 'utf8')

    // Should have updated SDK versions
    expect(exampleBuildGradleContent).toMatch(/targetSdkVersion.*36/)
    expect(exampleBuildGradleContent).toMatch(/compileSdkVersion.*36/)

    // Check example app gradle.properties
    const exampleGradlePropsPath = path.join(__dirname, '../example/android/gradle.properties')
    const exampleGradlePropsContent = fs.readFileSync(exampleGradlePropsPath, 'utf8')

    // Should have AndroidX enabled
    expect(exampleGradlePropsContent).toMatch(/android\.useAndroidX=true/)
  })

  test('MMKV dependency version should support 16KB page sizes', () => {
    // MMKV 2.2.0+ includes support for 16KB memory page sizes
    // This test ensures we're using a compatible version

    const fs = require('fs')
    const path = require('path')

    const buildGradlePath = path.join(__dirname, '../android/build.gradle')
    const buildGradleContent = fs.readFileSync(buildGradlePath, 'utf8')

    // Extract MMKV version from build.gradle
    const mmkvMatch = buildGradleContent.match(/mmkv-shared:([0-9.]+)/)
    expect(mmkvMatch).toBeTruthy()

    if (mmkvMatch) {
      const version = mmkvMatch[1]
      const [major, minor] = version.split('.').map(Number)

      // Ensure we're using MMKV 2.2.0 or later
      expect(major).toBeGreaterThanOrEqual(2)
      if (major === 2)
        expect(minor).toBeGreaterThanOrEqual(2)
    }
  })
})
