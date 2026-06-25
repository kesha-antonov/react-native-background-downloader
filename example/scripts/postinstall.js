#!/usr/bin/env node
/*
 * Wires the example app up to the local library source after `yarn install`.
 *
 * Two things the default tooling does not do for this repo layout:
 *
 *  1. Yarn Berry does not materialize the `link:..` dependency, because the link
 *     target (the repo root) is an ANCESTOR of the example directory. Yarn
 *     resolves it (linkType: soft) but never creates the symlink, so
 *     node_modules/@kesha-antonov/react-native-background-downloader is missing
 *     and both Metro and Expo autolinking fail to find the library. We create
 *     that symlink here.
 *
 *  2. The Expo config plugin entry (app.plugin.js -> plugin/build/index.js) is a
 *     compiled, gitignored artifact. If it has not been built yet, `expo
 *     prebuild` / `expo run:*` fail with "Cannot find module
 *     './plugin/build/index.js'". We build it on demand.
 *
 * This script is intentionally dependency-free and idempotent.
 */
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const exampleDir = path.resolve(__dirname, '..')
const repoRoot = path.resolve(exampleDir, '..')
const PKG = '@kesha-antonov/react-native-background-downloader'

function ensureLibSymlink () {
  const linkPath = path.join(exampleDir, 'node_modules', PKG)
  const linkDir = path.dirname(linkPath)
  const target = path.relative(linkDir, repoRoot)

  try {
    if (
      fs.lstatSync(linkPath).isSymbolicLink() &&
      fs.realpathSync(linkPath) === fs.realpathSync(repoRoot)
    )
      return // already linked correctly
  } catch {
    // does not exist yet - fall through and create it
  }

  fs.rmSync(linkPath, { recursive: true, force: true })
  fs.mkdirSync(linkDir, { recursive: true })
  fs.symlinkSync(target, linkPath, 'dir')
  console.log(`[example] linked ${PKG} -> ${target}`)
}

function ensurePluginBuilt () {
  const built = path.join(repoRoot, 'plugin', 'build', 'index.js')
  if (fs.existsSync(built)) return

  console.log('[example] building Expo config plugin (plugin/build is missing)...')
  try {
    execSync('npm run build-plugin', { cwd: repoRoot, stdio: 'inherit' })
  } catch {
    console.warn(
      '[example] Could not build the Expo config plugin automatically.\n' +
      '          Run `yarn install && yarn build-plugin` in the repo root, then reinstall.'
    )
  }
}

ensureLibSymlink()
ensurePluginBuilt()
