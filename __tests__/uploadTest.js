/* eslint-disable */

import RNBackgroundDownloader from '../src/index'

const { checkForExistingUploads, upload } = RNBackgroundDownloader

describe('Background Upload Tests', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  test('upload requires id, url and source', () => {
    expect(() => upload({ })).toThrow('[RNBackgroundDownloader] id, url and source are required')
    expect(() => upload({ id: 'test' })).toThrow('[RNBackgroundDownloader] id, url and source are required')
    expect(() => upload({ id: 'test', url: 'https://example.com/upload' })).toThrow('[RNBackgroundDownloader] id, url and source are required')
  })

  test('upload returns UploadTask', () => {
    const uploadTask = upload({
      id: 'testUpload',
      url: 'https://example.com/upload',
      source: '/path/to/file.jpg'
    })

    expect(uploadTask).toBeDefined()
    expect(uploadTask.id).toBe('testUpload')
    expect(uploadTask.state).toBe('PENDING')
    expect(typeof uploadTask.begin).toBe('function')
    expect(typeof uploadTask.progress).toBe('function')
    expect(typeof uploadTask.done).toBe('function')
    expect(typeof uploadTask.error).toBe('function')
  })

  test('checkForExistingUploads function exists', () => {
    expect(typeof checkForExistingUploads).toBe('function')
  })

  test('upload task chain methods', () => {
    const uploadTask = upload({
      id: 'testChain',
      url: 'https://example.com/upload',
      source: '/path/to/file.jpg'
    })

    const beginHandler = jest.fn()
    const progressHandler = jest.fn()
    const doneHandler = jest.fn()
    const errorHandler = jest.fn()

    const chained = uploadTask
      .begin(beginHandler)
      .progress(progressHandler)
      .done(doneHandler)
      .error(errorHandler)

    expect(chained).toBe(uploadTask) // Should return same instance for chaining
    expect(uploadTask.beginHandler).toBe(beginHandler)
    expect(uploadTask.progressHandler).toBe(progressHandler)
    expect(uploadTask.doneHandler).toBe(doneHandler)
    expect(uploadTask.errorHandler).toBe(errorHandler)
  })
})