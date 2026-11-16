const fs = require('fs')
const os = require('os')
const path = require('path')
const {
  migrateText,
  migratePackage,
} = require('../bin/react-native-background-downloader')

describe('migrate-package', () => {
  it('rewrites the library classes and packages', () => {
    const { text, changed, unresolved } = migrateText(
      [
        'import com.eko.RNBackgroundDownloaderPackage;',
        '-keep class com.eko.RNBGDTaskConfig { *; }',
        'import com.eko.uidt.UIDTJobManager',
      ].join('\n')
    )
    expect(text).toBe(
      [
        'import com.kesha_antonov.RNBackgroundDownloaderPackage;',
        '-keep class com.kesha_antonov.RNBGDTaskConfig { *; }',
        'import com.kesha_antonov.uidt.UIDTJobManager',
      ].join('\n')
    )
    expect(changed).toHaveLength(3)
    expect(unresolved).toHaveLength(0)
  })

  it('leaves other com.eko names alone and lists them', () => {
    const source = [
      'package com.eko.myapp',
      '-keep class com.eko.** { *; }',
      'import com.ekolabs.Something',
    ].join('\n')
    const { text, changed, unresolved } = migrateText(source)
    expect(text).toBe(source)
    expect(changed).toHaveLength(0)
    expect(unresolved.map((line) => line.line)).toEqual([1, 2])
  })

  describe('on an app directory', () => {
    let dir

    beforeEach(() => {
      dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rnbd-migrate-'))
      const javaDir = path.join(dir, 'android/app/src/main/java/com/myapp')
      fs.mkdirSync(javaDir, { recursive: true })
      fs.writeFileSync(
        path.join(javaDir, 'MainApplication.java'),
        'import com.eko.RNBackgroundDownloaderPackage;\n'
      )
      fs.writeFileSync(
        path.join(dir, 'app.json'),
        '{"extraProguardRules": "-keep class com.eko.RNBGDUploadTaskConfig { *; }"}\n'
      )
      const skipped = path.join(dir, 'node_modules/some-lib')
      fs.mkdirSync(skipped, { recursive: true })
      fs.writeFileSync(
        path.join(skipped, 'Foo.java'),
        'import com.eko.RNBGDTaskConfig;\n'
      )
    })

    afterEach(() => {
      fs.rmSync(dir, { recursive: true, force: true })
    })

    it('only reports without --write', () => {
      const results = migratePackage(dir)
      expect(results.map((result) => result.file).sort()).toEqual([
        'android/app/src/main/java/com/myapp/MainApplication.java',
        'app.json',
      ])
      expect(fs.readFileSync(path.join(dir, 'app.json'), 'utf8')).toContain(
        'com.eko.RNBGDUploadTaskConfig'
      )
    })

    it('changes the files with --write and skips node_modules', () => {
      migratePackage(dir, { write: true })
      expect(
        fs.readFileSync(
          path.join(dir, 'android/app/src/main/java/com/myapp/MainApplication.java'),
          'utf8'
        )
      ).toBe('import com.kesha_antonov.RNBackgroundDownloaderPackage;\n')
      expect(fs.readFileSync(path.join(dir, 'app.json'), 'utf8')).toContain(
        'com.kesha_antonov.RNBGDUploadTaskConfig'
      )
      expect(
        fs.readFileSync(path.join(dir, 'node_modules/some-lib/Foo.java'), 'utf8')
      ).toBe('import com.eko.RNBGDTaskConfig;\n')
      expect(migratePackage(dir)).toEqual([])
    })
  })
})
