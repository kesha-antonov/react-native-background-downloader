import {
  createDownloadTask,
} from '../src/index'

const emitEvent = global.__RNBackgroundDownloaderEmitEvent

test('native download errors reach the task', () => {
  return new Promise(resolve => {
    const errorDT = createDownloadTask({
      id: 'testCannotResume',
      url: 'test',
      destination: 'test',
    }).error(({ error, errorCode }) => {
      expect(errorCode).toBe(-1001)
      expect(error).toBe('The request timed out')
      expect(errorDT.state).toBe('FAILED')
      resolve()
    })
    errorDT.start()

    emitEvent('downloadFailed', {
      id: 'testCannotResume',
      error: 'The request timed out',
      errorCode: -1001,
    })
  })
})
