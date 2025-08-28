import RNBackgroundDownloader from '../src/index'
import { NativeModules } from 'react-native'

const RNBackgroundDownloaderNative = NativeModules.RNBackgroundDownloader

describe('Directory Consistency Tests', () => {
  test('documents directory should be accessible internal storage', () => {
    const documentsPath = RNBackgroundDownloader.directories.documents

    // Should return a path (not null/undefined)
    expect(documentsPath).toBeDefined()
    expect(typeof documentsPath).toBe('string')
    expect(documentsPath.length).toBeGreaterThan(0)

    // On Android, should use internal storage path pattern
    // (mocked in __mocks__ but this shows the expected behavior)
    expect(documentsPath).toMatch(/files$/)
  })

  test('documents directory should match native module constants', () => {
    const documentsFromDirectories = RNBackgroundDownloader.directories.documents
    const documentsFromNative = RNBackgroundDownloaderNative.documents

    // Both should return the same path
    expect(documentsFromDirectories).toBe(documentsFromNative)
  })

  test('directories object should be properly exported', () => {
    expect(RNBackgroundDownloader.directories).toBeDefined()
    expect(typeof RNBackgroundDownloader.directories).toBe('object')
    expect(RNBackgroundDownloader.directories.documents).toBeDefined()
  })
})
