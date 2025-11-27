import RNBackgroundDownloader from '../src/index'
import { NativeModules, NativeEventEmitter } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader
const nativeEmitter = new NativeEventEmitter(RNBackgroundDownloaderNative)

test('ERROR_CANNOT_RESUME handling', () => {
  return new Promise(resolve => {
    const errorDT = RNBackgroundDownloader.createDownloadTask({
      id: 'testCannotResume',
      url: 'test',
      destination: 'test',
    }).error(({ error, errorCode }) => {
      expect(errorCode).toBe(1008) // DownloadManager.ERROR_CANNOT_RESUME
      expect(error).toContain('ERROR_CANNOT_RESUME')
      expect(error).toContain('Android DownloadManager limitations')
      expect(error).toContain('Try restarting the download')
      expect(errorDT.state).toBe('FAILED')
      resolve()
    })
    errorDT.start()

    // Simulate the ERROR_CANNOT_RESUME error
    nativeEmitter.emit('downloadFailed', {
      id: 'testCannotResume',
      error: 'ERROR_CANNOT_RESUME - Unable to resume download. This may occur with large files due to Android DownloadManager limitations. Try restarting the download.',
      errorCode: 1008,
    })
  })
})
