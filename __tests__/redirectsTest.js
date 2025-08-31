/**
 * Tests for redirect handling functionality
 */

import RNBackgroundDownloader from '../src/index'
import DownloadTask from '../src/DownloadTask'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Redirects Tests', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  test('download without maxRedirects should work as before', () => {
    const task = RNBackgroundDownloader.download({
      id: 'testNoRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
    })

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

  test('download with maxRedirects should pass parameter to native module', () => {
    const task = RNBackgroundDownloader.download({
      id: 'testWithRedirects',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 10,
    })

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

  test('download with maxRedirects = 0 should work (no redirect resolution)', () => {
    const task = RNBackgroundDownloader.download({
      id: 'testZeroRedirects',
      url: 'https://example.com/file.zip',
      destination: '/tmp/file.zip',
      maxRedirects: 0,
    })

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

  test('download with headers and maxRedirects should pass both', () => {
    const task = RNBackgroundDownloader.download({
      id: 'testRedirectsWithHeaders',
      url: 'https://pdst.fm/e/example.mp3',
      destination: '/tmp/file.mp3',
      maxRedirects: 5,
      headers: {
        Authorization: 'Bearer token123',
        'Custom-Header': 'value',
      },
    })

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
    // Test various ways to call download without maxRedirects
    expect(() => {
      RNBackgroundDownloader.download({
        id: 'testOptional1',
        url: 'https://example.com/file1.zip',
        destination: '/tmp/file1.zip',
      })
    }).not.toThrow()

    expect(() => {
      RNBackgroundDownloader.download({
        id: 'testOptional2',
        url: 'https://example.com/file2.zip',
        destination: '/tmp/file2.zip',
        headers: { Test: 'value' },
      })
    }).not.toThrow()

    expect(() => {
      RNBackgroundDownloader.download({
        id: 'testOptional3',
        url: 'https://example.com/file3.zip',
        destination: '/tmp/file3.zip',
        isAllowedOverRoaming: true,
        isAllowedOverMetered: false,
      })
    }).not.toThrow()
  })
})
