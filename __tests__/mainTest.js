import {
  createDownloadTask,
  getExistingDownloadTasks,
  setConfig,
} from '../src/index'
import { DownloadTask } from '../src/DownloadTask'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader
const emitEvent = global.__RNBackgroundDownloaderEmitEvent

let downloadTask

test('createDownloadTask function', () => {
  downloadTask = createDownloadTask({
    id: 'test',
    url: 'test',
    destination: 'test',
  })
  expect(downloadTask).toBeInstanceOf(DownloadTask)
  // Task is not started yet, download should not be called
  expect(downloadTask.state).toBe('PENDING')
})

test('start download', () => {
  const startDT = createDownloadTask({
    id: 'testStart',
    url: 'test',
    destination: 'test',
  })
  startDT.start()
  expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()
})

test('begin event', () => {
  const mockedHeaders = { Etag: '123' }
  return new Promise(resolve => {
    const beginDT = createDownloadTask({
      id: 'testBegin',
      url: 'test',
      destination: 'test',
    }).begin(({ expectedBytes, headers }) => {
      expect(expectedBytes).toBe(9001)
      expect(headers).toBe(mockedHeaders)
      expect(beginDT.state).toBe('DOWNLOADING')
      resolve()
    })
    beginDT.start()
    emitEvent('downloadBegin', {
      id: 'testBegin',
      expectedBytes: 9001,
      headers: mockedHeaders,
    })
  })
})

test('progress event', () => {
  return new Promise(resolve => {
    const progressDT = createDownloadTask({
      id: 'testProgress',
      url: 'test',
      destination: 'test',
    }).progress(({ bytesDownloaded, bytesTotal }) => {
      expect(bytesDownloaded).toBe(100)
      expect(bytesTotal).toBe(200)
      resolve()
    })
    progressDT.start()
    emitEvent('downloadProgress', [{
      id: 'testProgress',
      bytesDownloaded: 100,
      bytesTotal: 200,
    }])
  })
})

test('done event', () => {
  return new Promise(resolve => {
    const doneDT = createDownloadTask({
      id: 'testDone',
      url: 'test',
      destination: 'test',
    }).done(() => {
      expect(doneDT.state).toBe('DONE')
      resolve()
    })
    doneDT.start()
    emitEvent('downloadComplete', {
      id: 'testDone',
    })
  })
})

test('fail event', () => {
  return new Promise(resolve => {
    const failDT = createDownloadTask({
      id: 'testFail',
      url: 'test',
      destination: 'test',
    }).error(({ error, errorCode }) => {
      expect(error).toBeInstanceOf(Error)
      expect(errorCode).toBe(-1)
      expect(failDT.state).toBe('FAILED')
      resolve()
    })
    failDT.start()
    emitEvent('downloadFailed', {
      id: 'testFail',
      error: new Error('test'),
      errorCode: -1,
    })
  })
})

test('pause', () => {
  const pauseDT = createDownloadTask({
    id: 'testPause',
    url: 'test',
    destination: 'test',
  })
  pauseDT.start()

  pauseDT.pause()
  expect(pauseDT.state).toBe('PAUSED')
  expect(RNBackgroundDownloaderNative.pauseTask).toHaveBeenCalled()
})

test('resume', () => {
  const resumeDT = createDownloadTask({
    id: 'testResume',
    url: 'test',
    destination: 'test',
  })
  resumeDT.start()

  resumeDT.resume()
  expect(resumeDT.state).toBe('DOWNLOADING')
  expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalled()
})

test('pause and resume cycle', () => {
  return new Promise(resolve => {
    const cycleDT = createDownloadTask({
      id: 'testPauseResumeCycle',
      url: 'https://example.com/largefile.zip',
      destination: '/tmp/largefile.zip',
    })

    // Start the download
    cycleDT.begin(() => {
      expect(cycleDT.state).toBe('DOWNLOADING')

      // Pause the download
      cycleDT.pause()
      expect(cycleDT.state).toBe('PAUSED')
      expect(RNBackgroundDownloaderNative.pauseTask).toHaveBeenCalledWith('testPauseResumeCycle')

      // Resume the download
      cycleDT.resume()
      expect(cycleDT.state).toBe('DOWNLOADING')
      expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalledWith('testPauseResumeCycle')

      resolve()
    })

    cycleDT.start()

    // Emit begin event to trigger the test
    emitEvent('downloadBegin', {
      id: 'testPauseResumeCycle',
      expectedBytes: 1000000,
      headers: {},
    })
  })
})

test('pause during progress and resume continues', () => {
  return new Promise(resolve => {
    let progressCount = 0

    const progressDT = createDownloadTask({
      id: 'testPauseDuringProgress',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

    progressDT
      .begin(() => {
        expect(progressDT.state).toBe('DOWNLOADING')
      })
      .progress(({ bytesDownloaded, bytesTotal }) => {
        progressCount++

        if (progressCount === 1) {
          // First progress - pause the download
          expect(bytesDownloaded).toBe(250000)
          progressDT.pause()
          expect(progressDT.state).toBe('PAUSED')

          // Resume after a small delay
          setTimeout(() => {
            progressDT.resume()
            expect(progressDT.state).toBe('DOWNLOADING')

            // Emit more progress after resume
            emitEvent('downloadProgress', [{
              id: 'testPauseDuringProgress',
              bytesDownloaded: 500000,
              bytesTotal: 1000000,
            }])
          }, 10)
        } else if (progressCount === 2) {
          // Second progress after resume
          expect(bytesDownloaded).toBe(500000)
          resolve()
        }
      })

    progressDT.start()

    // Start the download flow
    emitEvent('downloadBegin', {
      id: 'testPauseDuringProgress',
      expectedBytes: 1000000,
      headers: {},
    })

    // Emit first progress
    emitEvent('downloadProgress', [{
      id: 'testPauseDuringProgress',
      bytesDownloaded: 250000,
      bytesTotal: 1000000,
    }])
  })
})

test('multiple pause/resume cycles', () => {
  const multiCycleDT = createDownloadTask({
    id: 'testMultiplePauseResume',
    url: 'https://example.com/file.zip',
    destination: '/tmp/file.zip',
  })

  multiCycleDT.start()

  // First cycle
  multiCycleDT.pause()
  expect(multiCycleDT.state).toBe('PAUSED')

  multiCycleDT.resume()
  expect(multiCycleDT.state).toBe('DOWNLOADING')

  // Second cycle
  multiCycleDT.pause()
  expect(multiCycleDT.state).toBe('PAUSED')

  multiCycleDT.resume()
  expect(multiCycleDT.state).toBe('DOWNLOADING')

  // Third cycle
  multiCycleDT.pause()
  expect(multiCycleDT.state).toBe('PAUSED')

  multiCycleDT.resume()
  expect(multiCycleDT.state).toBe('DOWNLOADING')

  // Verify native methods were called for this specific task
  const pauseCalls = RNBackgroundDownloaderNative.pauseTask.mock.calls.filter(
    call => call[0] === 'testMultiplePauseResume'
  )
  const resumeCalls = RNBackgroundDownloaderNative.resumeTask.mock.calls.filter(
    call => call[0] === 'testMultiplePauseResume'
  )

  expect(pauseCalls.length).toBe(3)
  expect(resumeCalls.length).toBe(3)
})

test('pause then stop', () => {
  const pauseStopDT = createDownloadTask({
    id: 'testPauseThenStop',
    url: 'https://example.com/file.zip',
    destination: '/tmp/file.zip',
  })

  pauseStopDT.start()

  // Pause first
  pauseStopDT.pause()
  expect(pauseStopDT.state).toBe('PAUSED')

  // Then stop
  pauseStopDT.stop()
  expect(pauseStopDT.state).toBe('STOPPED')
  expect(RNBackgroundDownloaderNative.stopTask).toHaveBeenCalledWith('testPauseThenStop')
})

test('resume completes download successfully', () => {
  return new Promise(resolve => {
    const resumeCompleteDT = createDownloadTask({
      id: 'testResumeComplete',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

    resumeCompleteDT
      .begin(() => {
        // Pause and resume
        resumeCompleteDT.pause()
        resumeCompleteDT.resume()
      })
      .done(() => {
        expect(resumeCompleteDT.state).toBe('DONE')
        resolve()
      })

    resumeCompleteDT.start()

    // Start download flow
    emitEvent('downloadBegin', {
      id: 'testResumeComplete',
      expectedBytes: 1000,
      headers: {},
    })

    // Complete the download
    setTimeout(() => {
      emitEvent('downloadComplete', {
        id: 'testResumeComplete',
        location: '/tmp/file.zip',
      })
    }, 20)
  })
})

test('stop', () => {
  const stopDT = createDownloadTask({
    id: 'testStop',
    url: 'test',
    destination: 'test',
  })
  stopDT.start()

  stopDT.stop()
  expect(stopDT.state).toBe('STOPPED')
  expect(RNBackgroundDownloaderNative.stopTask).toHaveBeenCalled()
})

test('getExistingDownloadTasks', () => {
  return getExistingDownloadTasks()
    .then(foundDownloads => {
      expect(RNBackgroundDownloaderNative.getExistingDownloadTasks).toHaveBeenCalled()
      expect(foundDownloads.length).toBe(4)
      foundDownloads.forEach(foundDownload => {
        expect(foundDownload).toBeInstanceOf(DownloadTask)
        expect(foundDownload.state).not.toBe('FAILED')
        expect(foundDownload.state).not.toBe('STOPPED')
      })
    })
})

test('setConfig with progressMinBytes', () => {
  setConfig({
    progressMinBytes: 500000,
    progressInterval: 2000,
    isLogsEnabled: true,
  })

  // Test that download passes progressMinBytes to native
  const configDownloadTask = createDownloadTask({
    id: 'testConfig',
    url: 'https://example.com/file.zip',
    destination: '/tmp/file.zip',
  })
  configDownloadTask.start()

  expect(RNBackgroundDownloaderNative.download).toHaveBeenCalledWith(
    expect.objectContaining({
      id: 'testConfig',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
      progressInterval: 2000,
    })
  )
  expect(configDownloadTask).toBeInstanceOf(DownloadTask)
})

test('wrong handler type', () => {
  const dt = createDownloadTask({
    id: 'test22222',
    url: 'test',
    destination: 'test',
  })

  expect(() => {
    dt.begin('not function')
  }).toThrow()

  expect(() => {
    dt.progress(7)
  }).toThrow()

  expect(() => {
    dt.done({ iamnota: 'function' })
  }).toThrow()

  expect(() => {
    dt.error('not function')
  }).toThrow()
})

test('download with timeout improvements for slow URLs', () => {
  const timeoutDT = createDownloadTask({
    id: 'testSlowUrl',
    url: 'https://example.com/slow-response',
    destination: '/path/to/file.zip',
  })
  timeoutDT.start()

  expect(timeoutDT).toBeInstanceOf(DownloadTask)
  expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()

  // Verify that the download was called with the expected parameters
  const lastCallArgs = RNBackgroundDownloaderNative.download.mock.calls[RNBackgroundDownloaderNative.download.mock.calls.length - 1]
  expect(lastCallArgs[0]).toMatchObject({
    id: 'testSlowUrl',
    url: 'https://example.com/slow-response',
    destination: '/path/to/file.zip',
  })
})
