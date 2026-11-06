/**
 * Uploads in parts (S3-style multipart upload): one file, one presigned URL per part,
 * and the response headers (each part's ETag) handed back to the app.
 */
import { NativeModules } from 'react-native'
import {
  createUploadTask,
  getExistingUploadTasks,
} from '../src/index'

const emitEvent = global.__RNBackgroundDownloaderEmitEvent
const native = () => NativeModules.RNBackgroundDownloader

const partUrls = ['https://bucket.example/part1', 'https://bucket.example/part2']

const parts = [
  { partNumber: 1, responseCode: 200, responseBody: '', responseHeaders: { etag: '"a"' }, bytes: 5 },
  { partNumber: 2, responseCode: 200, responseBody: '', responseHeaders: { etag: '"b"' }, bytes: 5 },
]

beforeEach(() => {
  native().upload.mockClear()
  native().stopUploadTask.mockClear()
})

describe('createUploadTask with partUrls', () => {
  test('needs no url and sends the parts with PUT', () => {
    const task = createUploadTask({ id: 'parts-start', source: '/tmp/video.mp4', partUrls, partSize: 5 })
    task.start()

    const options = native().upload.mock.calls[0][0]
    expect(options.partUrls).toEqual(partUrls)
    expect(options.partSize).toBe(5)
    expect(options.method).toBe('PUT')
    // The native spec requires a url; it gets the first part's
    expect(options.url).toBe(partUrls[0])
  })

  test('keeps an explicit method', () => {
    createUploadTask({ id: 'parts-post', source: '/tmp/video.mp4', partUrls, method: 'POST' }).start()

    expect(native().upload.mock.calls[0][0].method).toBe('POST')
  })

  test('rejects an empty list or a non-URL entry', () => {
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls: [] })).toThrow('partUrls must be a non-empty array')
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls: ['https://a', ''] })).toThrow('partUrls must be a non-empty array')
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls: 'https://a' })).toThrow('partUrls must be a non-empty array')
  })

  test('can\'t be combined with multipart form fields', () => {
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls, fieldName: 'file' })).toThrow('can\'t be combined with fieldName or parameters')
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls, parameters: { a: 'b' } })).toThrow('can\'t be combined with fieldName or parameters')
  })

  test('rejects a part size that isn\'t a positive whole number', () => {
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls, partSize: 0 })).toThrow('partSize must be a positive whole number')
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f', partUrls, partSize: 2.5 })).toThrow('partSize must be a positive whole number')
  })

  test('still needs a url without partUrls', () => {
    expect(() => createUploadTask({ id: 'p', source: '/tmp/f' })).toThrow('id, url (or partUrls) and source are required')
  })
})

describe('the done handler gets the response headers', () => {
  test('every part with its ETag, and the upload keeps them', () => {
    return new Promise(resolve => {
      const task = createUploadTask({ id: 'parts-done', source: '/tmp/video.mp4', partUrls })
        .done(params => {
          expect(params.parts.map(part => part.responseHeaders.etag)).toEqual(['"a"', '"b"'])
          expect(params.responseHeaders).toEqual({ etag: '"b"' })
          expect(task.parts).toEqual(parts)
          expect(task.responseHeaders).toEqual({ etag: '"b"' })
          expect(task.state).toBe('DONE')
          resolve()
        })
      task.start()

      emitEvent('uploadComplete', {
        id: 'parts-done',
        responseCode: 200,
        responseBody: '',
        responseHeaders: { etag: '"b"' },
        parts,
        bytesUploaded: 10,
        bytesTotal: 10,
      })
    })
  })

  test('a single upload\'s headers too, and {} from a native side that sends none', () => {
    const received = []
    createUploadTask({ id: 'single-done', url: 'https://example.com/u', source: '/tmp/f' })
      .done(params => received.push(params.responseHeaders))
      .start()
    createUploadTask({ id: 'old-native-done', url: 'https://example.com/u', source: '/tmp/f' })
      .done(params => received.push(params.responseHeaders))
      .start()

    emitEvent('uploadComplete', { id: 'single-done', responseCode: 200, responseBody: '', responseHeaders: { etag: '"x"' }, bytesUploaded: 1, bytesTotal: 1 })
    emitEvent('uploadComplete', { id: 'old-native-done', responseCode: 200, responseBody: '', bytesUploaded: 1, bytesTotal: 1 })

    expect(received).toEqual([{ etag: '"x"' }, {}])
  })

  test('tells native the response was delivered, before the handler runs', () => {
    let stopCallsWhenHandled = -1
    createUploadTask({ id: 'acked', url: 'https://example.com/u', source: '/tmp/f' })
      .done(() => { stopCallsWhenHandled = native().stopUploadTask.mock.calls.length })
      .start()

    emitEvent('uploadComplete', { id: 'acked', responseCode: 200, responseBody: '', bytesUploaded: 1, bytesTotal: 1 })

    expect(native().stopUploadTask).toHaveBeenCalledWith('acked')
    // Sent first: an upload the done handler starts with the same id must not be stopped
    expect(stopCallsWhenHandled).toBe(1)
  })
})

describe('an upload that finished while the app wasn\'t listening', () => {
  test('a completion for an id JS doesn\'t know yet is not acknowledged, so getExistingUploadTasks still returns it', async () => {
    // After a relaunch, iOS can deliver the completion before the app calls getExistingUploadTasks
    emitEvent('uploadComplete', { id: 'not-claimed-yet', responseCode: 200, responseBody: '', bytesUploaded: 10, bytesTotal: 10 })

    expect(native().stopUploadTask).not.toHaveBeenCalledWith('not-claimed-yet')

    native().getExistingUploadTasks.mockResolvedValueOnce([{
      id: 'not-claimed-yet',
      metadata: '{}',
      state: 3,
      bytesUploaded: 10,
      bytesTotal: 10,
      errorCode: null,
      responseCode: 200,
      responseBody: '',
    }])

    const [task] = await getExistingUploadTasks()

    expect(task.state).toBe('DONE')
    expect(task.responseCode).toBe(200)
  })

  test('comes back from getExistingUploadTasks with its parts, and is acknowledged', async () => {
    native().getExistingUploadTasks.mockResolvedValueOnce([{
      id: 'finished-in-background',
      metadata: '{"key":"videos/1.mp4"}',
      state: 3,
      bytesUploaded: 10,
      bytesTotal: 10,
      errorCode: null,
      responseCode: 200,
      responseBody: '',
      responseHeaders: { etag: '"b"' },
      parts,
    }])

    const [task] = await getExistingUploadTasks()

    expect(task.state).toBe('DONE')
    expect(task.parts.map(part => part.responseHeaders.etag)).toEqual(['"a"', '"b"'])
    expect(task.responseCode).toBe(200)
    expect(task.metadata).toEqual({ key: 'videos/1.mp4' })
    expect(native().stopUploadTask).toHaveBeenCalledWith('finished-in-background')
  })
})
