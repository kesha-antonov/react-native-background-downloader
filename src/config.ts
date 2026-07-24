import { Headers, IosDataProtection, NotificationGroupingMode, NotificationsGroupingConfig, NotificationTexts } from './types'

export const DEFAULT_PROGRESS_INTERVAL = 1000
export const DEFAULT_PROGRESS_MIN_BYTES = 1024 * 1024 // 1MB
export const DEFAULT_MAX_PARALLEL_DOWNLOADS = 4
export const DEFAULT_ALLOWS_CELLULAR_ACCESS = true
// Lets a background download save its file even while the device is locked
// (after the first unlock since boot). See the iOS background-download notes in the README.
export const DEFAULT_IOS_DATA_PROTECTION: IosDataProtection = 'completeUntilFirstUserAuthentication'

// Default notification texts
export const DEFAULT_NOTIFICATION_TEXTS: Required<NotificationTexts> = {
  downloadTitle: 'Download',
  downloadStarting: 'Starting download...',
  downloadProgress: 'Downloading... {progress}%',
  downloadPaused: 'Paused',
  downloadFinished: 'Download complete',
  groupTitle: 'Downloads',
  groupText: (count: number) => `${count} download${count !== 1 ? 's' : ''} in progress`,
}

// Logging lives in ./logger (log, LogCallback); notification-text formatting
// lives in ./notifications (getGroupText, getNotificationTextsForNative).
// This module holds only the shared config state and its defaults.
import type { LogCallback } from './logger'

interface ConfigState {
  headers: Headers
  progressInterval: number
  progressMinBytes: number
  isLogsEnabled: boolean
  logCallback?: LogCallback
  maxParallelDownloads: number
  allowsCellularAccess: boolean
  showNotificationsEnabled: boolean
  notificationsGrouping: NotificationsGroupingConfig & { mode: NotificationGroupingMode }
  iosDataProtection: IosDataProtection
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
  iosDataProtection: DEFAULT_IOS_DATA_PROTECTION,
  notificationsGrouping: {
    enabled: false,
    mode: 'individual',
    texts: DEFAULT_NOTIFICATION_TEXTS,
  },
}

