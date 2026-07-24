import { config } from './config'

// External log callback for capturing logs in parent app
export type LogCallback = (tag: string, message: string, ...args: unknown[]) => void

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
