import RNBackgroundDownloader from '../src/index'
import DownloadTask from '../src/DownloadTask'
import { NativeModules, NativeEventEmitter } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader
const nativeEmitter = new NativeEventEmitter(RNBackgroundDownloaderNative)

let downloadTask

test('createDownloadTask function', () => {
  downloadTask = RNBackgroundDownloader.createDownloadTask({
    id: 'test',
    url: 'test',
    destination: 'test',
  })
  expect(downloadTask).toBeInstanceOf(DownloadTask)
  // Task is not started yet, download should not be called
  expect(downloadTask.state).toBe('PENDING')
})

test('start download', () => {
  const startDT = RNBackgroundDownloader.createDownloadTask({
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
    const beginDT = RNBackgroundDownloader.createDownloadTask({
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
    nativeEmitter.emit('downloadBegin', {
      id: 'testBegin',
      expectedBytes: 9001,
      headers: mockedHeaders,
    })
  })
})

test('progress event', () => {
  return new Promise(resolve => {
    const progressDT = RNBackgroundDownloader.createDownloadTask({
      id: 'testProgress',
      url: 'test',
      destination: 'test',
    }).progress(({ bytesDownloaded, bytesTotal }) => {
      expect(bytesDownloaded).toBe(100)
      expect(bytesTotal).toBe(200)
      resolve()
    })
    progressDT.start()
    nativeEmitter.emit('downloadProgress', [{
      id: 'testProgress',
      bytesDownloaded: 100,
      bytesTotal: 200,
    }])
  })
})

test('done event', () => {
  return new Promise(resolve => {
    const doneDT = RNBackgroundDownloader.createDownloadTask({
      id: 'testDone',
      url: 'test',
      destination: 'test',
    }).done(() => {
      expect(doneDT.state).toBe('DONE')
      resolve()
    })
    doneDT.start()
    nativeEmitter.emit('downloadComplete', {
      id: 'testDone',
    })
  })
})

test('fail event', () => {
  return new Promise(resolve => {
    const failDT = RNBackgroundDownloader.createDownloadTask({
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
    nativeEmitter.emit('downloadFailed', {
      id: 'testFail',
      error: new Error('test'),
      errorCode: -1,
    })
  })
})

test('pause', () => {
  const pauseDT = RNBackgroundDownloader.createDownloadTask({
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
  const resumeDT = RNBackgroundDownloader.createDownloadTask({
    id: 'testResume',
    url: 'test',
    destination: 'test',
  })
  resumeDT.start()

  resumeDT.resume()
  expect(resumeDT.state).toBe('DOWNLOADING')
  expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalled()
})

test('stop', () => {
  const stopDT = RNBackgroundDownloader.createDownloadTask({
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
  return RNBackgroundDownloader.getExistingDownloadTasks()
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
  RNBackgroundDownloader.setConfig({
    progressMinBytes: 500000,
    progressInterval: 2000,
    isLogsEnabled: true,
  })

  // Test that download passes progressMinBytes to native
  const configDownloadTask = RNBackgroundDownloader.createDownloadTask({
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
  const dt = RNBackgroundDownloader.createDownloadTask({
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
  const timeoutDT = RNBackgroundDownloader.createDownloadTask({
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
