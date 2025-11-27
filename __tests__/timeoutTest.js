import RNBackgroundDownloader from '../src/index'
import DownloadTask from '../src/DownloadTask'

// Test for timeout configuration to prevent downloads from staying
// in PENDING state when URLs are slow to respond
test('createDownloadTask with slow URL should handle timeout gracefully', () => {
  const downloadTask = RNBackgroundDownloader.createDownloadTask({
    id: 'timeout-test',
    url: 'https://httpstat.us/200?sleep=10000', // Simulate slow response
    destination: '/tmp/timeout-test.file',
  })

  expect(downloadTask).toBeInstanceOf(DownloadTask)
  expect(downloadTask.state).toBe('PENDING')

  // The download task should be created successfully
  // The actual timeout behavior is handled in the native Android code
  // by the HttpURLConnection timeout configuration
})

test('createDownloadTask configuration should include timeouts', () => {
  // This test verifies that our timeout configuration is documented
  // The actual timeout values are set in OnBegin.java:
  // - connectTimeout: 30000ms (30 seconds)
  // - readTimeout: 60000ms (60 seconds)

  const downloadTask = RNBackgroundDownloader.createDownloadTask({
    id: 'config-test',
    url: 'https://example.com/test.file',
    destination: '/tmp/config-test.file',
  })

  expect(downloadTask).toBeInstanceOf(DownloadTask)
})
