import {
  createUploadTask,
  getExistingUploadTasks,
  setConfig,
} from '../src/index'
import { UploadTask } from '../src/UploadTask'

let uploadTask

// Note: These tests validate the JavaScript API layer for uploads.
// Native implementation tests will be added once iOS and Android native code is implemented.

test('createUploadTask function', () => {
  uploadTask = createUploadTask({
    id: 'test-upload',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
  })
  expect(uploadTask).toBeInstanceOf(UploadTask)
  // Task is not started yet
  expect(uploadTask.state).toBe('PENDING')
})

test('upload task has correct properties', () => {
  const task = createUploadTask({
    id: 'test-upload-props',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    method: 'POST',
    metadata: { userId: '123' },
  })

  expect(task.id).toBe('test-upload-props')
  expect(task.state).toBe('PENDING')
  expect(task.metadata).toEqual({ userId: '123' })
  expect(task.bytesUploaded).toBe(0)
  expect(task.bytesTotal).toBe(0)
})

test('upload task supports different HTTP methods', () => {
  const postTask = createUploadTask({
    id: 'test-post',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    method: 'POST',
  })
  expect(postTask.uploadParams?.method).toBe('POST')

  const putTask = createUploadTask({
    id: 'test-put',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    method: 'PUT',
  })
  expect(putTask.uploadParams?.method).toBe('PUT')

  const patchTask = createUploadTask({
    id: 'test-patch',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    method: 'PATCH',
  })
  expect(patchTask.uploadParams?.method).toBe('PATCH')
})

test('upload task event handler chaining', () => {
  const task = createUploadTask({
    id: 'test-chaining',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
  })

  const result = task
    .begin(() => {})
    .progress(() => {})
    .done(() => {})
    .error(() => {})

  expect(result).toBe(task)
})

test('getExistingUploadTasks returns empty array when no native implementation', async () => {
  const tasks = await getExistingUploadTasks()
  expect(Array.isArray(tasks)).toBe(true)
  expect(tasks.length).toBe(0)
})

test('upload task validates required fields', () => {
  expect(() => {
    createUploadTask({
      id: '',
      url: 'https://example.com/upload',
      source: '/path/to/file.pdf',
    })
  }).toThrow()

  expect(() => {
    createUploadTask({
      id: 'test',
      url: '',
      source: '/path/to/file.pdf',
    })
  }).toThrow()

  expect(() => {
    createUploadTask({
      id: 'test',
      url: 'https://example.com/upload',
      source: '',
    })
  }).toThrow()
})

test('upload task accepts custom headers', () => {
  setConfig({
    headers: {
      'X-Global-Header': 'global-value',
    },
  })

  const task = createUploadTask({
    id: 'test-headers',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    headers: {
      'X-Custom-Header': 'custom-value',
    },
  })

  // Global and custom headers should be merged
  expect(task.uploadParams?.headers).toHaveProperty('X-Global-Header')
  expect(task.uploadParams?.headers).toHaveProperty('X-Custom-Header')
})

test('upload task accepts multipart form parameters', () => {
  const task = createUploadTask({
    id: 'test-multipart',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
    fieldName: 'file',
    mimeType: 'application/pdf',
    parameters: {
      userId: '123',
      action: 'upload',
    },
  })

  expect(task.uploadParams?.fieldName).toBe('file')
  expect(task.uploadParams?.mimeType).toBe('application/pdf')
  expect(task.uploadParams?.parameters).toEqual({
    userId: '123',
    action: 'upload',
  })
})

test('upload task event handlers are functions', () => {
  const task = createUploadTask({
    id: 'test-handlers',
    url: 'https://example.com/upload',
    source: '/path/to/file.pdf',
  })

  expect(() => {
    task.begin(() => {})
  }).not.toThrow()

  expect(() => {
    task.begin('not a function')
  }).toThrow('begin handler must be a function')
})

// Tests for when native implementation is available will trigger actual upload events
// For now, these tests document the expected behavior

describe('Upload events (native implementation required)', () => {
  test.skip('begin event should provide expected bytes', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'testUploadBegin',
        url: 'https://example.com/upload',
        source: '/path/to/file.pdf',
      }).begin(({ expectedBytes }) => {
        expect(expectedBytes).toBeGreaterThan(0)
        expect(task.state).toBe('UPLOADING')
        resolve()
      })

      task.start()
      // Native implementation would emit uploadBegin event
    })
  })

  test.skip('progress event should provide upload progress', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'testUploadProgress',
        url: 'https://example.com/upload',
        source: '/path/to/file.pdf',
      }).progress(({ bytesUploaded, bytesTotal }) => {
        expect(bytesUploaded).toBeGreaterThan(0)
        expect(bytesTotal).toBeGreaterThan(0)
        expect(bytesUploaded).toBeLessThanOrEqual(bytesTotal)
        resolve()
      })

      task.start()
      // Native implementation would emit uploadProgress events
    })
  })

  test.skip('done event should provide server response', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'testUploadDone',
        url: 'https://example.com/upload',
        source: '/path/to/file.pdf',
      }).done(({ responseCode, responseBody, bytesUploaded, bytesTotal }) => {
        expect(responseCode).toBe(200)
        expect(typeof responseBody).toBe('string')
        expect(task.state).toBe('DONE')
        resolve()
      })

      task.start()
      // Native implementation would emit uploadComplete event
    })
  })

  test.skip('error event should be called on upload failure', () => {
    return new Promise(resolve => {
      const task = createUploadTask({
        id: 'testUploadError',
        url: 'https://invalid-url',
        source: '/path/to/file.pdf',
      }).error(({ error, errorCode }) => {
        expect(typeof error).toBe('string')
        expect(typeof errorCode).toBe('number')
        expect(task.state).toBe('FAILED')
        resolve()
      })

      task.start()
      // Native implementation would emit uploadFailed event
    })
  })

  test.skip('pause should pause an active upload', async () => {
    const task = createUploadTask({
      id: 'testUploadPause',
      url: 'https://example.com/upload',
      source: '/path/to/file.pdf',
    })

    task.start()
    await task.pause()
    expect(task.state).toBe('PAUSED')
  })

  test.skip('resume should resume a paused upload', async () => {
    const task = createUploadTask({
      id: 'testUploadResume',
      url: 'https://example.com/upload',
      source: '/path/to/file.pdf',
    })

    task.start()
    await task.pause()
    await task.resume()
    expect(task.state).toBe('UPLOADING')
  })

  test.skip('stop should cancel an upload', async () => {
    const task = createUploadTask({
      id: 'testUploadStop',
      url: 'https://example.com/upload',
      source: '/path/to/file.pdf',
    })

    task.start()
    await task.stop()
    expect(task.state).toBe('STOPPED')
  })
})
