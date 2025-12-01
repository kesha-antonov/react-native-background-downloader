/**
 * Tests for redirect handling functionality
 */

import {
  createDownloadTask,
} from '../src/index'
import { DownloadTask } from '../src/DownloadTask'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Redirects Tests', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  test('createDownloadTask without maxRedirects should work as before', () => {
    const task = createDownloadTask({
      id: 'testNoRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })
    task.start()

    expect(task).toBeInstanceOf(DownloadTask)
    expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()

    const lastCallArgs = RNBackgroundDownloaderNative.download.mock.calls[RNBackgroundDownloaderNative.download.mock.calls.length - 1]
    expect(lastCallArgs[0]).toMatchObject({
      id: 'testNoRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

    // maxRedirects should not be present if not specified
    expect(lastCallArgs[0].maxRedirects).toBeUndefined()
  })

  test('createDownloadTask with maxRedirects should pass parameter to native module', () => {
    const task = createDownloadTask({
      id: 'testWithRedirects',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 10,
    })
    task.start()

    expect(task).toBeInstanceOf(DownloadTask)
    expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()

    const lastCallArgs = RNBackgroundDownloaderNative.download.mock.calls[RNBackgroundDownloaderNative.download.mock.calls.length - 1]
    expect(lastCallArgs[0]).toMatchObject({
      id: 'testWithRedirects',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 10,
    })
  })

  test('createDownloadTask with maxRedirects = 0 should work (no redirect resolution)', () => {
    const task = createDownloadTask({
      id: 'testZeroRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
      maxRedirects: 0,
    })
    task.start()

    expect(task).toBeInstanceOf(DownloadTask)
    expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()

    const lastCallArgs = RNBackgroundDownloaderNative.download.mock.calls[RNBackgroundDownloaderNative.download.mock.calls.length - 1]
    expect(lastCallArgs[0]).toMatchObject({
      id: 'testZeroRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
      maxRedirects: 0,
    })
  })

  test('createDownloadTask with headers and maxRedirects should pass both', () => {
    const task = createDownloadTask({
      id: 'testRedirectsWithHeaders',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 5,
      headers: {
        Authorization: 'Bearer token123',
        'Custom-Header': 'value',
      },
    })
    task.start()

    expect(task).toBeInstanceOf(DownloadTask)
    expect(RNBackgroundDownloaderNative.download).toHaveBeenCalled()

    const lastCallArgs = RNBackgroundDownloaderNative.download.mock.calls[RNBackgroundDownloaderNative.download.mock.calls.length - 1]
    expect(lastCallArgs[0]).toMatchObject({
      id: 'testRedirectsWithHeaders',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 5,
      headers: {
        Authorization: 'Bearer token123',
        'Custom-Header': 'value',
      },
    })
  })

  test('maxRedirects parameter should be optional', () => {
    // Test various ways to call createDownloadTask without maxRedirects
    expect(() => {
      createDownloadTask({
        id: 'testOptional1',
        url: 'https://example.com/file1.zip',
        destination: '/tmp/file1.zip',
      })
    }).not.toThrow()

    expect(() => {
      createDownloadTask({
        id: 'testOptional2',
        url: 'https://example.com/file2.zip',
        destination: '/tmp/file2.zip',
        headers: { Test: 'value' },
      })
    }).not.toThrow()

    expect(() => {
      createDownloadTask({
        id: 'testOptional3',
        url: 'https://example.com/file3.zip',
        destination: '/tmp/file3.zip',
        isAllowedOverRoaming: true,
        isAllowedOverMetered: false,
      })
    }).not.toThrow()
  })
})
