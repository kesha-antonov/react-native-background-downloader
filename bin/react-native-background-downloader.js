#!/usr/bin/env node
'use strict'

// Command line helper for @kesha-antonov/react-native-background-downloader.
//
//   npx react-native-background-downloader migrate-package [dir] [--write]
//
// Finds references to the library's old Android package (com.eko) in an app
// and, with --write, changes them to com.kesha_antonov. Only the library's own
// class and package names are changed; anything else under com.eko is listed
// for review, since an app can have its own com.eko package.

const fs = require('fs')
const path = require('path')

const OLD_PACKAGE = 'com.eko'
const NEW_PACKAGE = 'com.kesha_antonov'

// Classes and subpackages of the library, as they appear after "com.eko."
const LIBRARY_NAMES = [
  'RNBackgroundDownloaderPackage',
  'RNBackgroundDownloaderModule',
  'RNBackgroundDownloaderModuleImpl',
  'RNBGDTaskConfig',
  'RNBGDUploadTaskConfig',
  'UploadPartResult',
  'DownloadConstants',
  'ResumableDownloadService',
  'UIDTDownloadJobService',
  'uidt',
  'utils',
  'handlers',
  'interfaces',
]

const LIBRARY_REFERENCE = new RegExp(
  `\\bcom\\.eko\\.(${LIBRARY_NAMES.join('|')})\\b`,
  'g'
)
const ANY_REFERENCE = /\bcom\.eko\b/

const SCANNED_EXTENSIONS = new Set([
  '.java',
  '.kt',
  '.pro',
  '.gradle',
  '.kts',
  '.xml',
])
// Expo apps keep extra ProGuard rules in the app config (expo-build-properties)
const SCANNED_FILE_NAMES = new Set([
  'app.json',
  'app.config.js',
  'app.config.ts',
  'app.config.mjs',
  'app.config.cjs',
])
const SKIPPED_DIRS = new Set([
  'node_modules',
  '.git',
  'build',
  '.gradle',
  '.cxx',
  'Pods',
  'ios',
])

/**
 * Rewrite the library's com.eko references in one file's text.
 * Returns the new text, the changed lines and the com.eko lines left as they are.
 */
function migrateText (text) {
  const changed = []
  const unresolved = []
  const lines = text.split('\n')
  const newLines = lines.map((line, index) => {
    const newLine = line.replace(
      LIBRARY_REFERENCE,
      (_match, name) => `${NEW_PACKAGE}.${name}`
    )
    if (newLine !== line)
      changed.push({ line: index + 1, before: line, after: newLine })
    if (ANY_REFERENCE.test(newLine))
      unresolved.push({ line: index + 1, text: newLine })
    return newLine
  })
  return { text: newLines.join('\n'), changed, unresolved }
}

function shouldScan (fileName) {
  return (
    SCANNED_FILE_NAMES.has(fileName) ||
    SCANNED_EXTENSIONS.has(path.extname(fileName))
  )
}

function listFiles (dir) {
  const files = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }))
    if (entry.isDirectory()) {
      if (!SKIPPED_DIRS.has(entry.name))
        files.push(...listFiles(path.join(dir, entry.name)))
    } else if (entry.isFile() && shouldScan(entry.name)) {
      files.push(path.join(dir, entry.name))
    }

  return files
}

/**
 * Find (and with write: true, fix) com.eko references under dir.
 */
function migratePackage (dir, { write = false } = {}) {
  const results = []
  for (const file of listFiles(dir)) {
    const text = fs.readFileSync(file, 'utf8')
    if (!text.includes(OLD_PACKAGE)) continue
    const result = migrateText(text)
    if (result.changed.length === 0 && result.unresolved.length === 0) continue
    if (write && result.changed.length > 0)
      fs.writeFileSync(file, result.text)
    results.push({
      file: path.relative(dir, file),
      changed: result.changed,
      unresolved: result.unresolved,
    })
  }
  return results
}

function printUsage () {
  console.log(`Usage: react-native-background-downloader migrate-package [dir] [--write]

Finds references to the old Android package com.eko in your app (Java, Kotlin,
Gradle, ProGuard and manifest files, and the Expo app config) and changes them
to com.kesha_antonov.

  dir       app root to scan, the current directory by default
  --write   change the files; without it nothing is written`)
}

function main (args) {
  const [command, ...rest] = args
  if (command !== 'migrate-package') {
    printUsage()
    return command === undefined || command === '--help' || command === '-h'
      ? 0
      : 1
  }

  const write = rest.includes('--write')
  const dir = path.resolve(rest.find((arg) => !arg.startsWith('--')) || '.')
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    console.error(`Not a directory: ${dir}`)
    return 1
  }

  const results = migratePackage(dir, { write })
  if (results.length === 0) {
    console.log(`No references to ${OLD_PACKAGE} found in ${dir}.`)
    return 0
  }

  let changeCount = 0
  let unresolvedCount = 0
  for (const { file, changed, unresolved } of results) {
    console.log(`\n${file}`)
    for (const change of changed) {
      console.log(`  ${change.line}: ${change.before.trim()}`)
      console.log(`  ${' '.repeat(String(change.line).length)}  ${change.after.trim()}`)
    }
    for (const line of unresolved)
      console.log(`  ${line.line}: ${line.text.trim()}   (check by hand)`)
    changeCount += changed.length
    unresolvedCount += unresolved.length
  }

  console.log('')
  if (changeCount > 0)
    console.log(
      write
        ? `Changed ${changeCount} line(s).`
        : `${changeCount} line(s) to change. Run again with --write to apply.`
    )

  if (unresolvedCount > 0)
    console.log(
      `${unresolvedCount} line(s) mention ${OLD_PACKAGE} but not a class of this library; check them by hand.`
    )

  return 0
}

module.exports = { migrateText, migratePackage, main }

if (require.main === module)
  process.exitCode = main(process.argv.slice(2))
