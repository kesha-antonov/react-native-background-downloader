/**
 * Test for Android-specific pause/resume behavior
 * These tests ensure that pause/resume methods don't crash on Android,
 * even though the functionality is not supported.
 */

import RNBackgroundDownloader from '../src/index'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Android pause/resume limitations', () => {
  let task

  beforeEach(() => {
    task = RNBackgroundDownloader.download({
      id: 'test-android-limitations',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })
  })

  test('pause method should not crash on Android', () => {
    // This should not throw an exception, even on Android
    expect(() => {
      task.pause()
    }).not.toThrow()

    // The mock should still be called (the actual Android limitation
    // is handled at the native level)
    expect(RNBackgroundDownloaderNative.pauseTask).toHaveBeenCalled()
  })

  test('resume method should not crash on Android', () => {
    // This should not throw an exception, even on Android
    expect(() => {
      task.resume()
    }).not.toThrow()

    // The mock should still be called (the actual Android limitation
    // is handled at the native level)
    expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalled()
  })

  test('task state should be updated even if pause/resume is not supported', () => {
    // Even if the native functionality doesn't work on Android,
    // the JavaScript state should still be updated for consistency
    task.pause()
    expect(task.state).toBe('PAUSED')

    task.resume()
    expect(task.state).toBe('DOWNLOADING')
  })
})
