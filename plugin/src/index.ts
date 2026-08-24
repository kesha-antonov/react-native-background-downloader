import { ConfigPlugin, withAndroidManifest, withInfoPlist } from '@expo/config-plugins'
import type { AndroidManifest } from '@expo/config-plugins/build/android/Manifest'
import type { InfoPlist } from '@expo/config-plugins/build/ios/IosConfig.types'

export interface PluginOptions {
  maxParallelDownloads?: number
  enableLogging?: boolean
  progressInterval?: number
  progressMinBytes?: number
}

export interface ResolvedPluginOptions {
  maxParallelDownloads: number
  enableLogging: boolean
  progressInterval: number
  progressMinBytes: number
}

export const IOS_MAX_PARALLEL_DOWNLOADS_KEY = 'RNBackgroundDownloaderMaxParallelDownloads'
export const IOS_ENABLE_LOGGING_KEY = 'RNBackgroundDownloaderEnableLogging'
export const IOS_PROGRESS_INTERVAL_KEY = 'RNBackgroundDownloaderProgressInterval'
export const IOS_PROGRESS_MIN_BYTES_KEY = 'RNBackgroundDownloaderProgressMinBytes'
export const ANDROID_MAX_PARALLEL_DOWNLOADS_KEY = 'com.eko.rnbgd.MAX_PARALLEL_DOWNLOADS'
export const ANDROID_ENABLE_LOGGING_KEY = 'com.eko.rnbgd.ENABLE_LOGGING'
export const ANDROID_PROGRESS_INTERVAL_KEY = 'com.eko.rnbgd.PROGRESS_INTERVAL'
export const ANDROID_PROGRESS_MIN_BYTES_KEY = 'com.eko.rnbgd.PROGRESS_MIN_BYTES'

const DEFAULTS: ResolvedPluginOptions = {
  maxParallelDownloads: 4,
  enableLogging: false,
  progressInterval: 1000,
  progressMinBytes: 1024 * 1024,
}

export function resolvePluginOptions (options?: PluginOptions): ResolvedPluginOptions {
  const resolved = { ...DEFAULTS, ...options }

  if (!Number.isInteger(resolved.maxParallelDownloads) || resolved.maxParallelDownloads < 1)
    throw new Error('maxParallelDownloads must be a positive integer')
  if (typeof resolved.enableLogging !== 'boolean')
    throw new Error('enableLogging must be a boolean')
  if (!Number.isInteger(resolved.progressInterval) || resolved.progressInterval < 250)
    throw new Error('progressInterval must be an integer greater than or equal to 250')
  if (!Number.isInteger(resolved.progressMinBytes) || resolved.progressMinBytes < 0)
    throw new Error('progressMinBytes must be a non-negative integer')

  return resolved
}

export function applyAndroidBuildConfig (manifest: AndroidManifest, options: ResolvedPluginOptions): AndroidManifest {
  const application = manifest.manifest.application?.[0]
  if (!application)
    throw new Error('AndroidManifest.xml is missing its application element')

  const values: Record<string, string> = {
    [ANDROID_MAX_PARALLEL_DOWNLOADS_KEY]: String(options.maxParallelDownloads),
    [ANDROID_ENABLE_LOGGING_KEY]: String(options.enableLogging),
    [ANDROID_PROGRESS_INTERVAL_KEY]: String(options.progressInterval),
    [ANDROID_PROGRESS_MIN_BYTES_KEY]: String(options.progressMinBytes),
  }
  const keys = new Set(Object.keys(values))
  application['meta-data'] = (application['meta-data'] ?? []).filter(item => !keys.has(item.$?.['android:name']))

  for (const [name, value] of Object.entries(values))
    application['meta-data'].push({ $: { 'android:name': name, 'android:value': value } })

  return manifest
}

export function applyIosBuildConfig (infoPlist: InfoPlist, options: ResolvedPluginOptions): InfoPlist {
  infoPlist[IOS_MAX_PARALLEL_DOWNLOADS_KEY] = options.maxParallelDownloads
  infoPlist[IOS_ENABLE_LOGGING_KEY] = options.enableLogging
  infoPlist[IOS_PROGRESS_INTERVAL_KEY] = options.progressInterval
  infoPlist[IOS_PROGRESS_MIN_BYTES_KEY] = options.progressMinBytes
  return infoPlist
}

const withRNBackgroundDownloader: ConfigPlugin<PluginOptions | void> = (config, options) => {
  const resolved = resolvePluginOptions(options || undefined)

  config = withAndroidManifest(config, config => {
    config.modResults = applyAndroidBuildConfig(config.modResults, resolved)
    return config
  })

  return withInfoPlist(config, config => {
    config.modResults = applyIosBuildConfig(config.modResults, resolved)
    return config
  })
}

export default withRNBackgroundDownloader
