const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..')
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8')

describe('foreground-only native contract', () => {
  test('Android manifest contributes no permissions or components', () => {
    const manifest = read('android/src/main/AndroidManifest.xml')
    expect(manifest).not.toMatch(/uses-permission|<application|<service|<receiver|<provider/)
  })

  test('native dependency declarations contain no persistence library', () => {
    expect(read('android/build.gradle')).not.toMatch(/MMKV|gson/i)
    expect(read('react-native-background-downloader.podspec')).not.toMatch(/MMKV/)
  })

  test('native implementations contain no background execution infrastructure', () => {
    const android = fs.readdirSync(path.join(root, 'android/src/main/java/com/eko'), { recursive: true })
      .filter(file => file.endsWith('.kt'))
      .map(file => read(path.join('android/src/main/java/com/eko', file)))
      .join('\n')
    const ios = read('ios/RNBackgroundDownloader.mm')

    expect(android).not.toMatch(/DownloadManager|JobScheduler|foregroundService|WakeLock|UIDT|NotificationManager|MMKV|Gson/)
    expect(ios).not.toMatch(/backgroundSessionConfiguration|sessionSendsLaunchEvents|handleEventsForBackgroundURLSession|MMKV/)
    expect(ios).toMatch(/defaultSessionConfiguration/)
    expect(ios).toMatch(/RequestTimeoutSeconds = 30/)
    expect(read('android/src/main/java/com/eko/DownloadConstants.kt')).toMatch(/CONNECT_TIMEOUT_MS = 30_000/)
    expect(read('android/src/main/java/com/eko/DownloadConstants.kt')).toMatch(/READ_TIMEOUT_MS = 30_000/)
  })

  test('runtime configuration methods are absent from the public native spec', () => {
    const spec = read('src/NativeRNBackgroundDownloader.ts')
    expect(spec).not.toMatch(/setLogsEnabled|setMaxParallelDownloads|setAllowsCellularAccess|setNotificationGroupingConfig|updateTaskHeaders/)
    expect(read('src/index.ts')).not.toMatch(/export function setConfig/)
    expect(read('src/DownloadTask.ts')).not.toMatch(/setDownloadParams|updateTaskHeaders/)
  })

  test('the package supports only the React Native New Architecture', () => {
    const androidBuild = read('android/build.gradle')
    const androidModule = read('android/src/main/java/com/eko/RNBackgroundDownloaderModule.kt')
    const iosHeader = read('ios/RNBackgroundDownloader.h')
    const iosImplementation = read('ios/RNBackgroundDownloader.mm')
    const podspec = read('react-native-background-downloader.podspec')
    const javascript = read('src/index.ts')
    const spec = read('src/NativeRNBackgroundDownloader.ts')

    expect(androidBuild).toMatch(/requires React Native's New Architecture/)
    expect(androidBuild).not.toMatch(/src\/oldarch|src\/newarch|IS_NEW_ARCHITECTURE_ENABLED/)
    expect(fs.existsSync(path.join(root, 'android/src/oldarch/java/com/eko/RNBackgroundDownloaderModule.kt'))).toBe(false)
    expect(fs.existsSync(path.join(root, 'android/src/newarch/java/com/eko/RNBackgroundDownloaderModule.kt'))).toBe(false)
    expect(androidModule).toMatch(/NativeRNBackgroundDownloaderSpec/)

    expect(podspec).toMatch(/RCT_NEW_ARCH_ENABLED.*== '0'/)
    expect(podspec).toMatch(/requires React Native's New Architecture/)
    expect(iosHeader).toMatch(/NativeRNBackgroundDownloaderSpecBase/)
    expect(iosHeader).not.toMatch(/RCTEventEmitter|RCTBridgeModule|RCT_NEW_ARCH_ENABLED/)
    expect(iosImplementation).not.toMatch(/RCT_EXPORT_METHOD|RCT_NEW_ARCH_ENABLED/)

    expect(javascript).toMatch(/TurboModuleRegistry\.getEnforcing/)
    expect(javascript).not.toMatch(/NativeModules|NativeEventEmitter/)
    expect(spec).not.toMatch(/addListener|removeListeners/)
    expect(require('../package.json').peerDependencies['react-native']).toBe('>=0.76.0')
  })

  test('plugin only owns the four private build settings', () => {
    const plugin = read('plugin/src/index.ts')
    expect(plugin).not.toMatch(/withAppDelegate|withDangerousMod|withProjectBuildGradle|withGradleProperties|bridging|MMKV|notification/i)
    expect(plugin.match(/withAndroidManifest/g)).toHaveLength(2)
    expect(plugin.match(/withInfoPlist/g)).toHaveLength(2)
  })
})
