import RNBackgroundDownloader from '../index'
import DownloadTask from '../lib/downloadTask'
import { NativeModules, NativeEventEmitter } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader
const nativeEmitter = new NativeEventEmitter(RNBackgroundDownloaderNative)

let downloadTask

test('download function', () => {
  downloadTask = RNBackgroundDownloader.download({
    id: 'test',
    url: 'test',
    destination: 'test',
  })
  expect(downloadTask).toBeInstanceOf(DownloadTask)
  expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()
})

test('begin event', () => {
  const mockedHeaders = { Etag: '123' }
  return new Promise(resolve => {
    const beginDT = RNBackgroundDownloader.download({
      id: 'testBegin',
      url: 'test',
      destination: 'test',
    }).begin(({ expectedBytes, headers }) => {
      expect(expectedBytes).toBe(9001)
      expect(headers).toBe(mockedHeaders)
      expect(beginDT.state).toBe('DOWNLOADING')
      resolve()
    })
    nativeEmitter.emit('downloadBegin', {
      id: 'testBegin',
      expectedBytes: 9001,
      headers: mockedHeaders,
    })
  })
})

test('progress event', () => {
  return new Promise(resolve => {
    RNBackgroundDownloader.download({
      id: 'testProgress',
      url: 'test',
      destination: 'test',
    }).progress((percent, bytesWritten, totalBytes) => {
      expect(percent).toBeCloseTo(0.7)
      expect(bytesWritten).toBe(100)
      expect(totalBytes).toBe(200)
      resolve()
    })
    nativeEmitter.emit('downloadProgress', [{
      id: 'testProgress',
      percent: 0.7,
      written: 100,
      total: 200,
    }])
  })
})

test('done event', () => {
  return new Promise(resolve => {
    const doneDT = RNBackgroundDownloader.download({
      id: 'testDone',
      url: 'test',
      destination: 'test',
    }).done(() => {
      expect(doneDT.state).toBe('DONE')
      resolve()
    })
    nativeEmitter.emit('downloadComplete', {
      id: 'testDone',
    })
  })
})

test('fail event', () => {
  return new Promise(resolve => {
    const failDT = RNBackgroundDownloader.download({
      id: 'testFail',
      url: 'test',
      destination: 'test',
    }).error(error => {
      expect(error).toBeInstanceOf(Error)
      expect(failDT.state).toBe('FAILED')
      resolve()
    })
    nativeEmitter.emit('downloadFailed', {
      id: 'testFail',
      error: new Error('test'),
    })
  })
})

test('pause', () => {
  const pauseDT = RNBackgroundDownloader.download({
    id: 'testPause',
    url: 'test',
    destination: 'test',
  })

  pauseDT.pause()
  expect(pauseDT.state).toBe('PAUSED')
  expect(RNBackgroundDownloaderNative.pauseTask).toHaveBeenCalled()
})

test('resume', () => {
  const resumeDT = RNBackgroundDownloader.download({
    id: 'testResume',
    url: 'test',
    destination: 'test',
  })

  resumeDT.resume()
  expect(resumeDT.state).toBe('DOWNLOADING')
  expect(RNBackgroundDownloaderNative.resumeTask).toHaveBeenCalled()
})

test('stop', () => {
  const stopDT = RNBackgroundDownloader.download({
    id: 'testStop',
    url: 'test',
    destination: 'test',
  })

  stopDT.stop()
  expect(stopDT.state).toBe('STOPPED')
  expect(RNBackgroundDownloaderNative.stopTask).toHaveBeenCalled()
})

test('initDownloader', () => {
  jest.mock('react-native/Libraries/Utilities/Platform', () => {
    const Platform = jest.requireActual(
      'react-native/Libraries/Utilities/Platform'
    )
    Platform.OS = 'android'
    return Platform
  })

  const res = RNBackgroundDownloader.initDownloader()
  expect(RNBackgroundDownloaderNative.initDownloader).toHaveBeenCalled()
  expect(res).toBe(undefined)
})

test('checkForExistingDownloads', () => {
  return RNBackgroundDownloader.checkForExistingDownloads()
    .then(foundDownloads => {
      expect(RNBackgroundDownloaderNative.checkForExistingDownloads).toHaveBeenCalled()
      expect(foundDownloads.length).toBe(4)
      foundDownloads.forEach(foundDownload => {
        expect(foundDownload).toBeInstanceOf(DownloadTask)
        expect(foundDownload.state).not.toBe('FAILED')
        expect(foundDownload.state).not.toBe('STOPPED')
      })
    })
})

test('wrong handler type', () => {
  const dt = RNBackgroundDownloader.download({
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
