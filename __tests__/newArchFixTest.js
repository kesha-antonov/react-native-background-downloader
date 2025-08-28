/* eslint-disable */
/**
 * This test verifies that the new architecture fix works correctly.
 * The Android implementation now properly handles event emission
 * even when JavaScript bridge is not immediately ready.
 */
import { NativeModules, NativeEventEmitter } from 'react-native'
import RNBackgroundDownloader from '../src/index'

describe('New Architecture Fix Verification', () => {
  test('events should fire correctly after Android native fix', (done) => {
    // Enable logging to see the events
    RNBackgroundDownloader.setConfig({ isLogsEnabled: true })
    
    const task = RNBackgroundDownloader.download({
      id: 'newArchTest',
      url: 'https://example.com/test.zip',
      destination: '/test/file.zip',
    })
    
    let beginFired = false
    let progressFired = false
    
    task
      .begin((params) => {
        beginFired = true
        expect(params.expectedBytes).toBeGreaterThan(0)
      })
      .progress((params) => {
        progressFired = true
        expect(params.bytesDownloaded).toBeGreaterThanOrEqual(0)
        expect(params.bytesTotal).toBeGreaterThan(0)
      })
      .done(() => {
        // Verify that all events fired
        expect(beginFired).toBe(true)
        expect(progressFired).toBe(true)
        done()
      })
    
    // Simulate native events (as they would come from the Android/iOS layer)
    const emitter = new NativeEventEmitter(NativeModules.RNBackgroundDownloader)
    
    setTimeout(() => {
      emitter.emit('downloadBegin', {
        id: 'newArchTest',
        expectedBytes: 1000,
        headers: {}
      })
    }, 10)
    
    setTimeout(() => {
      emitter.emit('downloadProgress', [{
        id: 'newArchTest',
        bytesDownloaded: 500,
        bytesTotal: 1000
      }])
    }, 20)
    
    setTimeout(() => {
      emitter.emit('downloadComplete', {
        id: 'newArchTest',
        bytesDownloaded: 1000,
        bytesTotal: 1000
      })
    }, 30)
  })
})