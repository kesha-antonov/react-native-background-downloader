const {
  ANDROID_ENABLE_LOGGING_KEY,
  ANDROID_MAX_PARALLEL_DOWNLOADS_KEY,
  ANDROID_PROGRESS_INTERVAL_KEY,
  ANDROID_PROGRESS_MIN_BYTES_KEY,
  IOS_ENABLE_LOGGING_KEY,
  IOS_MAX_PARALLEL_DOWNLOADS_KEY,
  IOS_PROGRESS_INTERVAL_KEY,
  IOS_PROGRESS_MIN_BYTES_KEY,
  applyAndroidBuildConfig,
  applyIosBuildConfig,
  resolvePluginOptions,
} = require('../plugin/build')
const fs = require('fs')
const path = require('path')

describe('Expo build configuration', () => {
  test('resolves defaults', () => {
    expect(resolvePluginOptions()).toEqual({
      maxParallelDownloads: 4,
      enableLogging: false,
      progressInterval: 1000,
      progressMinBytes: 1048576,
    })
  })

  test.each([
    [{ maxParallelDownloads: 0 }, 'maxParallelDownloads'],
    [{ maxParallelDownloads: 1.5 }, 'maxParallelDownloads'],
    [{ enableLogging: 'yes' }, 'enableLogging'],
    [{ progressInterval: 249 }, 'progressInterval'],
    [{ progressInterval: 250.5 }, 'progressInterval'],
    [{ progressMinBytes: -1 }, 'progressMinBytes'],
  ])('rejects invalid option %p', (option, message) => {
    expect(() => resolvePluginOptions(option)).toThrow(message)
  })

  test('writes and replaces Android metadata idempotently', () => {
    const manifest = {
      manifest: {
        application: [{
          'meta-data': [
            { $: { 'android:name': 'unrelated', 'android:value': 'keep' } },
            { $: { 'android:name': ANDROID_MAX_PARALLEL_DOWNLOADS_KEY, 'android:value': '99' } },
          ],
        }],
      },
    }
    const options = resolvePluginOptions({ maxParallelDownloads: 6, enableLogging: true, progressInterval: 750, progressMinBytes: 0 })

    applyAndroidBuildConfig(manifest, options)
    applyAndroidBuildConfig(manifest, options)

    const metadata = manifest.manifest.application[0]['meta-data']
    expect(metadata).toHaveLength(5)
    expect(Object.fromEntries(metadata.map(item => [item.$['android:name'], item.$['android:value']]))).toEqual({
      unrelated: 'keep',
      [ANDROID_MAX_PARALLEL_DOWNLOADS_KEY]: '6',
      [ANDROID_ENABLE_LOGGING_KEY]: 'true',
      [ANDROID_PROGRESS_INTERVAL_KEY]: '750',
      [ANDROID_PROGRESS_MIN_BYTES_KEY]: '0',
    })
  })

  test('writes iOS Info.plist values without integration mutations', () => {
    const plist = {
      ExistingKey: 'keep',
      [IOS_MAX_PARALLEL_DOWNLOADS_KEY]: 99,
      [IOS_ENABLE_LOGGING_KEY]: false,
      [IOS_PROGRESS_INTERVAL_KEY]: 9999,
      [IOS_PROGRESS_MIN_BYTES_KEY]: 9999,
    }
    const options = resolvePluginOptions({ maxParallelDownloads: 8, enableLogging: true, progressInterval: 500, progressMinBytes: 2048 })

    applyIosBuildConfig(plist, options)
    expect(applyIosBuildConfig(plist, options)).toEqual({
      ExistingKey: 'keep',
      [IOS_MAX_PARALLEL_DOWNLOADS_KEY]: 8,
      [IOS_ENABLE_LOGGING_KEY]: true,
      [IOS_PROGRESS_INTERVAL_KEY]: 500,
      [IOS_PROGRESS_MIN_BYTES_KEY]: 2048,
    })
  })

  test('does not register source, AppDelegate, bridging-header, or Gradle mutations', () => {
    const source = fs.readFileSync(path.join(__dirname, '../plugin/src/index.ts'), 'utf8')
    expect(source).not.toMatch(/withAppDelegate|withDangerousMod|withProjectBuildGradle|withGradleProperties|bridging/i)
    expect(source).toMatch(/withAndroidManifest/)
    expect(source).toMatch(/withInfoPlist/)
  })
})
