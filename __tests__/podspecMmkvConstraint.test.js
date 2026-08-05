/**
 * Guards the iOS MMKV version constraints declared in the podspec.
 *
 * MMKVCore 2.4.1 does not compile on Apple platforms - Core/aes/AESCrypt.cpp calls
 * memset_s(), and its `#define __STDC_WANT_LIB_EXT1__ 1` lands too late because
 * CocoaPods force-includes the generated prefix header first, so Darwin's <string.h>
 * has already been parsed without declaring memset_s.
 *
 * See https://github.com/Tencent/MMKV/issues/1675
 * and https://github.com/kesha-antonov/react-native-background-downloader/issues/175
 */

import { describe, test, expect } from '@jest/globals'
import fs from 'fs'
import path from 'path'

const podspec = fs.readFileSync(
  path.join(__dirname, '../react-native-background-downloader.podspec'),
  'utf8'
)

const dependencyLine = (pod) => {
  const match = podspec.match(new RegExp(`^\\s*s\\.dependency\\s+'${pod}'.*$`, 'm'))
  return match ? match[0] : null
}

describe('podspec MMKV constraints', () => {
  test('keeps the MMKV floor low so other pods can raise it', () => {
    expect(dependencyLine('MMKV')).toMatch(/'>= 1\.2\.0'/)
  })

  test('excludes the broken MMKV 2.4.1', () => {
    expect(dependencyLine('MMKV')).toMatch(/'!= 2\.4\.1'/)
  })

  // MMKV 2.4.0 depends on 'MMKVCore (~> 2.4.0)', which on its own still resolves to
  // the broken MMKVCore 2.4.1 - excluding MMKV alone is not enough.
  test('excludes the broken MMKVCore 2.4.1 explicitly', () => {
    const line = dependencyLine('MMKVCore')
    expect(line).not.toBeNull()
    expect(line).toMatch(/'!= 2\.4\.1'/)
  })

  test('does not cap MMKV to an upper bound, so fixed releases are still allowed', () => {
    expect(dependencyLine('MMKV')).not.toMatch(/'<[=]?\s*\d/)
    expect(dependencyLine('MMKVCore')).not.toMatch(/'<[=]?\s*\d/)
  })
})
