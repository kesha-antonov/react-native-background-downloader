/**
 * Pause and resume are implemented by both process-owned native engines.
 */

import { createDownloadTask } from '../src/index'
import { TurboModuleRegistry } from 'react-native'

const RNBackgroundDownloaderNative = TurboModuleRegistry.getEnforcing('RNBackgroundDownloader')

describe('pause and resume', () => {
  let task

  beforeEach(() => {
    task = createDownloadTask({
      id: 'test-android-limitations',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })
    task.start()
  })

  test('pause delegates to native', () => {
    expect(() => {
      task.pause()
    }).not.toThrow()

    expect(RNBackgroundDownloaderNative.pauseTask).toHaveBeenCalled()
  })

  test('resume delegates to native', () => {
    expect(() => {
      task.resume()
    }).not.toThrow()

    expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalled()
  })

  test('task state is updated', () => {
    task.pause()
    expect(task.state).toBe('PAUSED')

    task.resume()
    expect(task.state).toBe('DOWNLOADING')
  })
})
