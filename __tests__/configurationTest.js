import {
  setConfig,
} from '../src/index'
import { config } from '../src/config'
import { NativeModules, Platform } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Configuration Options', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  test('setConfig with maxParallelDownloads', () => {
    setConfig({
      maxParallelDownloads: 8,
    })

    expect(config.maxParallelDownloads).toBe(8)
    expect(RNBackgroundDownloaderNative.setMaxParallelDownloads).toHaveBeenCalledWith(8)
  })

  test('setConfig with maxParallelDownloads validation - valid value', () => {
    const consoleSpy = jest.spyOn(console, 'warn')

    setConfig({
      maxParallelDownloads: 1,
    })

    expect(config.maxParallelDownloads).toBe(1)
    expect(consoleSpy).not.toHaveBeenCalled()
    expect(RNBackgroundDownloaderNative.setMaxParallelDownloads).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
  })

  test('setConfig with maxParallelDownloads validation - invalid value', () => {
    const consoleSpy = jest.spyOn(console, 'warn')
    const originalValue = config.maxParallelDownloads

    setConfig({
      maxParallelDownloads: 0,
    })

    expect(config.maxParallelDownloads).toBe(originalValue) // Should not change
    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('maxParallelDownloads must be a number >= 1')
    )

    consoleSpy.mockRestore()
  })

  test('setConfig with allowsCellularAccess - true', () => {
    setConfig({
      allowsCellularAccess: true,
    })

    expect(config.allowsCellularAccess).toBe(true)
    expect(RNBackgroundDownloaderNative.setAllowsCellularAccess).toHaveBeenCalledWith(true)
  })

  test('setConfig with allowsCellularAccess - false', () => {
    setConfig({
      allowsCellularAccess: false,
    })

    expect(config.allowsCellularAccess).toBe(false)
    expect(RNBackgroundDownloaderNative.setAllowsCellularAccess).toHaveBeenCalledWith(false)
  })

  test('setConfig with multiple configuration options', () => {
    setConfig({
      maxParallelDownloads: 6,
      allowsCellularAccess: false,
      isLogsEnabled: true,
      progressInterval: 2000,
    })

    expect(config.maxParallelDownloads).toBe(6)
    expect(config.allowsCellularAccess).toBe(false)
    expect(config.isLogsEnabled).toBe(true)
    expect(config.progressInterval).toBe(2000)

    expect(RNBackgroundDownloaderNative.setMaxParallelDownloads).toHaveBeenCalledWith(6)
    expect(RNBackgroundDownloaderNative.setAllowsCellularAccess).toHaveBeenCalledWith(false)
    expect(RNBackgroundDownloaderNative.setLogsEnabled).toHaveBeenCalledWith(true)

    // Reset to default values for subsequent tests
    setConfig({
      maxParallelDownloads: 4,
      allowsCellularAccess: true,
      isLogsEnabled: false,
      progressInterval: 1000,
    })
  })

  test('default values for new configuration options', () => {
    expect(config.maxParallelDownloads).toBe(4)
    expect(config.allowsCellularAccess).toBe(true)
  })

  test('android notification extras default to off and are opt-in', () => {
    expect(config.showCompletionNotification).toBe(false)
    expect(config.showCancelAction).toBe(false)

    setConfig({
      showNotificationsEnabled: true,
    })

    // Enabling notifications alone must not turn on the alerting completion
    // notification or the Cancel button
    expect(config.showCompletionNotification).toBe(false)
    expect(config.showCancelAction).toBe(false)

    setConfig({
      showNotificationsEnabled: true,
      showCompletionNotification: true,
      showCancelAction: true,
    })

    expect(config.showCompletionNotification).toBe(true)
    expect(config.showCancelAction).toBe(true)
  })

  test('setConfig forwards notification config to native on android', () => {
    const originalOS = Platform.OS
    Platform.OS = 'android'

    try {
      setConfig({
        showNotificationsEnabled: true,
        showCompletionNotification: true,
        showCancelAction: true,
        notificationsGrouping: {
          enabled: true,
          mode: 'individual',
          texts: { downloadCancel: 'Abort' },
        },
      })

      expect(RNBackgroundDownloaderNative.setNotificationGroupingConfig).toHaveBeenCalledWith(
        expect.objectContaining({
          enabled: true,
          showNotificationsEnabled: true,
          showCompletionNotification: true,
          showCancelAction: true,
          mode: 'individual',
          texts: expect.objectContaining({ downloadCancel: 'Abort' }),
        })
      )
    } finally {
      Platform.OS = originalOS
      setConfig({
        showNotificationsEnabled: false,
        showCompletionNotification: false,
        showCancelAction: false,
      })
    }
  })

  test('setConfig does not call native methods when values are undefined', () => {
    jest.clearAllMocks()

    setConfig({
      isLogsEnabled: true,
    })

    expect(RNBackgroundDownloaderNative.setLogsEnabled).toHaveBeenCalled()
    expect(RNBackgroundDownloaderNative.setMaxParallelDownloads).not.toHaveBeenCalled()
    expect(RNBackgroundDownloaderNative.setAllowsCellularAccess).not.toHaveBeenCalled()
  })

  test('setConfig preserves existing config when setting only some values', () => {
    setConfig({
      maxParallelDownloads: 10,
      allowsCellularAccess: true,
    })

    expect(config.maxParallelDownloads).toBe(10)
    expect(config.allowsCellularAccess).toBe(true)

    // Now set only one value
    setConfig({
      allowsCellularAccess: false,
    })

    // maxParallelDownloads should still be 10
    expect(config.maxParallelDownloads).toBe(10)
    // allowsCellularAccess should be updated
    expect(config.allowsCellularAccess).toBe(false)
  })
})
