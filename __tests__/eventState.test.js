import {
  createDownloadTask,
  createUploadTask,
  getExistingDownloadTasks,
} from '../src/index'

const emit = (name, data) => global.__RNBackgroundDownloaderEmitEvent(name, data)

const startDownload = (id) => {
  const handlers = { begin: jest.fn(), progress: jest.fn(), done: jest.fn(), error: jest.fn() }
  const task = createDownloadTask({ id, url: 'https://example.com/file.zip', destination: '/tmp/file.zip' })
    .begin(handlers.begin)
    .progress(handlers.progress)
    .done(handlers.done)
    .error(handlers.error)
  task.start()
  return { task, handlers }
}

describe('events that arrive after the app paused or stopped a task', () => {
  test('a late begin leaves a paused task paused', async () => {
    const { task, handlers } = startDownload('late-begin')
    await task.pause()

    emit('downloadBegin', { id: 'late-begin', expectedBytes: 100, headers: {} })

    expect(task.state).toBe('PAUSED')
    expect(handlers.begin).not.toHaveBeenCalled()
  })

  test('progress buffered before a pause does not reach the handler', async () => {
    const { task, handlers } = startDownload('late-progress')
    await task.pause()

    emit('downloadProgress', [{ id: 'late-progress', bytesDownloaded: 50, bytesTotal: 100 }])

    expect(handlers.progress).not.toHaveBeenCalled()
    expect(task.bytesDownloaded).toBe(0)
  })

  test('progress resumes after resume', async () => {
    const { task, handlers } = startDownload('progress-after-resume')
    await task.pause()
    await task.resume()

    emit('downloadProgress', [{ id: 'progress-after-resume', bytesDownloaded: 50, bytesTotal: 100 }])

    expect(handlers.progress).toHaveBeenCalledWith({ bytesDownloaded: 50, bytesTotal: 100 })
  })
})

describe('begin fires once per task', () => {
  test('a second begin, from a new transfer after a resume, is ignored', async () => {
    const { task, handlers } = startDownload('begin-once')
    emit('downloadBegin', { id: 'begin-once', expectedBytes: 100, headers: {} })
    await task.pause()
    await task.resume()

    emit('downloadBegin', { id: 'begin-once', expectedBytes: 60, headers: {} })

    expect(handlers.begin).toHaveBeenCalledTimes(1)
    expect(task.bytesTotal).toBe(100)
  })

  test('a restored task that already has data does not begin again', async () => {
    const tasks = await getExistingDownloadTasks()
    const restored = tasks.find(task => task.id === 'taskRunning')
    const onBegin = jest.fn()
    restored.begin(onBegin)

    emit('downloadBegin', { id: 'taskRunning', expectedBytes: 100, headers: {} })

    expect(onBegin).not.toHaveBeenCalled()
  })

  test('uploads begin once too', () => {
    const onBegin = jest.fn()
    createUploadTask({ id: 'upload-begin-once', url: 'https://example.com/upload', source: '/tmp/file.pdf' })
      .begin(onBegin)
      .start()

    emit('uploadBegin', { id: 'upload-begin-once', expectedBytes: 100 })
    emit('uploadBegin', { id: 'upload-begin-once', expectedBytes: 100 })

    expect(onBegin).toHaveBeenCalledTimes(1)
  })
})

describe('a failed task keeps its error code', () => {
  test('download', () => {
    const { task } = startDownload('failed-download')

    emit('downloadFailed', { id: 'failed-download', error: 'timed out', errorCode: -1001 })

    expect(task.state).toBe('FAILED')
    expect(task.errorCode).toBe(-1001)
  })

  test('upload', () => {
    const task = createUploadTask({ id: 'failed-upload', url: 'https://example.com/upload', source: '/tmp/file.pdf' })
    task.start()

    emit('uploadFailed', { id: 'failed-upload', error: 'server error', errorCode: 500 })

    expect(task.state).toBe('FAILED')
    expect(task.errorCode).toBe(500)
  })
})
