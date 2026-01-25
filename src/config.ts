import { Headers, NotificationsGroupingConfig, NotificationTexts } from './types'

export const DEFAULT_PROGRESS_INTERVAL = 1000
export const DEFAULT_PROGRESS_MIN_BYTES = 1024 * 1024 // 1MB
export const DEFAULT_MAX_PARALLEL_DOWNLOADS = 4
export const DEFAULT_ALLOWS_CELLULAR_ACCESS = true

// Default notification texts
export const DEFAULT_NOTIFICATION_TEXTS: Required<NotificationTexts> = {
  downloadTitle: 'Download',
  downloadStarting: 'Starting download...',
  downloadProgress: 'Downloading... {progress}%',
  downloadFinished: 'Download complete',
  groupTitle: 'Downloads',
  groupText: (count: number) => `${count} download${count !== 1 ? 's' : ''} in progress`,
}

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
  showNotificationsEnabled: boolean
  notificationsGrouping: NotificationsGroupingConfig
}

export const config: ConfigState = {
  headers: {},
  progressInterval: DEFAULT_PROGRESS_INTERVAL,
  progressMinBytes: DEFAULT_PROGRESS_MIN_BYTES,
  isLogsEnabled: false,
  logCallback: undefined,
  maxParallelDownloads: DEFAULT_MAX_PARALLEL_DOWNLOADS,
  allowsCellularAccess: DEFAULT_ALLOWS_CELLULAR_ACCESS,
  showNotificationsEnabled: false,
  notificationsGrouping: {
    enabled: false,
    texts: DEFAULT_NOTIFICATION_TEXTS,
  },
}

/**
 * Get notification text with proper pluralization.
 */
export function getGroupText (count: number): string {
  const groupText = config.notificationsGrouping.texts?.groupText ?? DEFAULT_NOTIFICATION_TEXTS.groupText
  if (typeof groupText === 'function')
    return groupText(count)

  return groupText.replace('{count}', String(count))
}

/**
 * Get notification texts config for native side (serializable).
 */
export function getNotificationTextsForNative (): Record<string, string> {
  const texts = config.notificationsGrouping.texts ?? DEFAULT_NOTIFICATION_TEXTS
  return {
    downloadTitle: texts.downloadTitle ?? DEFAULT_NOTIFICATION_TEXTS.downloadTitle,
    downloadStarting: texts.downloadStarting ?? DEFAULT_NOTIFICATION_TEXTS.downloadStarting,
    downloadProgress: texts.downloadProgress ?? DEFAULT_NOTIFICATION_TEXTS.downloadProgress,
    downloadFinished: texts.downloadFinished ?? DEFAULT_NOTIFICATION_TEXTS.downloadFinished,
    groupTitle: texts.groupTitle ?? DEFAULT_NOTIFICATION_TEXTS.groupTitle,
    // For native side, we send a pattern with {count} placeholder
    groupText: typeof texts.groupText === 'function'
      ? '{count} download(s) in progress' // Default pattern if function provided
      : (texts.groupText ?? '{count} download(s) in progress'),
  }
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
