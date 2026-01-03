import { Headers } from './types'

export const DEFAULT_PROGRESS_INTERVAL = 1000
export const DEFAULT_PROGRESS_MIN_BYTES = 1024 * 1024 // 1MB
export const DEFAULT_MAX_PARALLEL_DOWNLOADS = 4
export const DEFAULT_ALLOWS_CELLULAR_ACCESS = true

// External log callback for capturing logs in parent app
type LogCallback = (tag: string, message: string, ...args: unknown[]) => void

interface ConfigState {
  headers: Headers
  progressInterval: number
  progressMinBytes: number
  isLogsEnabled: boolean
  logCallback?: LogCallback
  maxParallelDownloads: number
  allowsCellularAccess: boolean
}

export const config: ConfigState = {
  headers: {},
  progressInterval: DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes: DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled: false,
  logCallback: undefined,
  maxParallelDownloads: DEFAULT_MAX_PARALLEL_DOWNLOADS,
  allowsCellularAccess: DEFAULT_ALLOWS_CELLULAR_ACCESS,
}

export const log = (...args: unknown[]): void => {
  if (config.isLogsEnabled) {
    console.log('[RNBackgroundDownloader]', ...args)
    // Call external log callback if provided
    if (config.logCallback) {
      const message = args.length > 0 && typeof args[0] === 'string' ? args[0] : 'log'
      const restArgs = args.length > 1 ? args.slice(1) : []
      config.logCallback('RNBD', message, ...restArgs)
    }
  }
}
