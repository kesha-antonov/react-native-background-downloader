import { Headers } from './types'

export const DEFAULT_PROGRESS_INTERVAL = 1000
export const DEFAULT_PROGRESS_MIN_BYTES = 1024 * 1024 // 1MB

interface ConfigState {
  headers: Headers
  progressInterval: number
  progressMinBytes: number
  isLogsEnabled: boolean
}

export const config: ConfigState = {
  headers: {},
  progressInterval: DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes: DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled: false,
}

export const log = (...args: unknown[]): void => {
  if (config.isLogsEnabled)
    console.log('[RNBackgroundDownloader]', ...args)
}
