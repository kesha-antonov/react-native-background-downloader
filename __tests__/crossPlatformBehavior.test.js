/**
 * Cross-platform behavior contract.
 *
 * iOS and Android each reimplement the download/upload state machine natively, so the
 * two can drift - a fix on one platform can leave the other exposed. Issue #170 was
 * exactly that: download failures already surfaced to JS, but upload failures did not,
 * so a failed upload hung forever. These tests pin the JS-layer contract that BOTH
 * platforms must satisfy identically, regardless of which native side emits the event:
 *
 *   - a native *Failed event MUST reach the task's error handler (never hang), and
 *   - a native *Complete event MUST reach the task's done handler,
 *
 * with the download and upload paths behaving symmetrically. If a native platform stops
 * emitting one of these, or the JS routing regresses, these tests fail.
 */
import {
  createDownloadTask,
  createUploadTask,
} from '../src/index'

const emitEvent = global.__RNBackgroundDownloaderEmitEvent

const FAILURE = { error: 'network is unreachable', errorCode: -1009 }

describe('failure events reach the task error handler (issue #170 guard)', () => {
  test('download failure surfaces to onError and marks the task FAILED', () => {
    return new Promise(resolve => {
      const task = createDownloadTask({
        id: 'xplat-download-fail',
        url: 'https://example.com/file.zip',
        destination: '/tmp/file.zip',
      }).error(({ error, errorCode }) => {
        expect(error).toBe(FAILURE.error)
        expect(errorCode).toBe(FAILURE.errorCode)
        expect(task.state).toBe('FAILED')
        resolve()
      })
      task.start()

      emitEvent('downloadFailed', { id: 'xplat-download-fail', ...FAILURE })
    })
  })

  test('upload failure surfaces to onError and marks the task FAILED', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'xplat-upload-fail',
        url: 'https://example.com/upload',
        source: '/tmp/file.pdf',
      }).error(({ error, errorCode }) => {
        expect(error).toBe(FAILURE.error)
        expect(errorCode).toBe(FAILURE.errorCode)
        expect(task.state).toBe('FAILED')
        resolve()
      })
      task.start()

      // The native side (uploadFailed / onUploadFailed) must route here - before #170
      // this event was never emitted for setup failures, so the promise hung.
      emitEvent('uploadFailed', { id: 'xplat-upload-fail', ...FAILURE })
    })
  })
})

describe('completion events are symmetric across download and upload', () => {
  test('download completion surfaces to onDone and marks the task DONE', () => {
    return new Promise(resolve => {
      const task = createDownloadTask({
        id: 'xplat-download-done',
        url: 'https://example.com/file.zip',
        destination: '/tmp/file.zip',
      }).done(() => {
        expect(task.state).toBe('DONE')
        resolve()
      })
      task.start()

      emitEvent('downloadComplete', {
        id: 'xplat-download-done',
        location: '/tmp/file.zip',
        bytesDownloaded: 100,
        bytesTotal: 100,
      })
    })
  })

  test('upload completion surfaces to onDone and marks the task DONE', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'xplat-upload-done',
        url: 'https://example.com/upload',
        source: '/tmp/file.pdf',
      }).done(() => {
        expect(task.state).toBe('DONE')
        resolve()
      })
      task.start()

      emitEvent('uploadComplete', {
        id: 'xplat-upload-done',
        bytesUploaded: 100,
        bytesTotal: 100,
      })
    })
  })
})

test('a failure event for an unknown id does not throw', () => {
  // Native may emit for a task JS no longer tracks (app restart, race). Must be a no-op.
  expect(() => {
    emitEvent('downloadFailed', { id: 'no-such-download', ...FAILURE })
    emitEvent('uploadFailed', { id: 'no-such-upload', ...FAILURE })
  }).not.toThrow()
})
