/**
 * Test for progress callback when total bytes are unknown
 * This addresses the issue where progress is not reported for realtime streams
 * or files without known content size
 */

import RNBackgroundDownloader from '../src/index'
import { NativeModules, NativeEventEmitter } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader
const nativeEmitter = new NativeEventEmitter(RNBackgroundDownloaderNative)

describe('Progress callback with unknown total bytes', () => {
  test('progress event should be called when bytesTotal is 0 (unknown)', () => {
    return new Promise(resolve => {
      const progressDT = RNBackgroundDownloader.createDownloadTask({
        id: 'testProgressUnknownTotal',
        url: 'https://example.com/stream',
        destination: '/tmp/stream.dat',
      }).progress(({ bytesDownloaded, bytesTotal }) => {
        expect(bytesDownloaded).toBe(512)
        expect(bytesTotal).toBe(0) // Unknown total bytes
        resolve()
      })
      progressDT.start()

      // Simulate native progress event with unknown total bytes
      nativeEmitter.emit('downloadProgress', [{
        id: 'testProgressUnknownTotal',
        bytesDownloaded: 512,
        bytesTotal: 0,
      }])
    })
  })

  test('progress event should be called when bytesTotal is -1 (unknown)', () => {
    return new Promise(resolve => {
      const progressDT = RNBackgroundDownloader.createDownloadTask({
        id: 'testProgressUnknownTotal2',
        url: 'https://example.com/stream2',
        destination: '/tmp/stream2.dat',
      }).progress(({ bytesDownloaded, bytesTotal }) => {
        expect(bytesDownloaded).toBe(1024)
        expect(bytesTotal).toBe(-1) // Some servers return -1 for unknown
        resolve()
      })
      progressDT.start()

      // Simulate native progress event with unknown total bytes (-1)
      nativeEmitter.emit('downloadProgress', [{
        id: 'testProgressUnknownTotal2',
        bytesDownloaded: 1024,
        bytesTotal: -1,
      }])
    })
  })

  test('multiple progress events with unknown total bytes should work', () => {
    let progressCount = 0

    return new Promise(resolve => {
      const multiProgressDT = RNBackgroundDownloader.createDownloadTask({
        id: 'testProgressMultipleUnknown',
        url: 'https://example.com/stream3',
        destination: '/tmp/stream3.dat',
      }).progress(({ bytesDownloaded, bytesTotal }) => {
        progressCount++
        expect(bytesTotal).toBe(0) // Always unknown

        if (progressCount === 1) {
          expect(bytesDownloaded).toBe(256)
        } else if (progressCount === 2) {
          expect(bytesDownloaded).toBe(768)
        } else if (progressCount === 3) {
          expect(bytesDownloaded).toBe(1536)
          resolve() // All progress calls received
        }
      })
      multiProgressDT.start()

      // Simulate multiple progress events
      nativeEmitter.emit('downloadProgress', [{
        id: 'testProgressMultipleUnknown',
        bytesDownloaded: 256,
        bytesTotal: 0,
      }])

      setTimeout(() => {
        nativeEmitter.emit('downloadProgress', [{
          id: 'testProgressMultipleUnknown',
          bytesDownloaded: 768,
          bytesTotal: 0,
        }])
      }, 10)

      setTimeout(() => {
        nativeEmitter.emit('downloadProgress', [{
          id: 'testProgressMultipleUnknown',
          bytesDownloaded: 1536,
          bytesTotal: 0,
        }])
      }, 20)
    })
  })
})
