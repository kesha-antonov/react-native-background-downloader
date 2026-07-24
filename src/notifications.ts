import { config, DEFAULT_NOTIFICATION_TEXTS } from './config'

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
    downloadPaused: texts.downloadPaused ?? DEFAULT_NOTIFICATION_TEXTS.downloadPaused,
    downloadFinished: texts.downloadFinished ?? DEFAULT_NOTIFICATION_TEXTS.downloadFinished,
    groupTitle: texts.groupTitle ?? DEFAULT_NOTIFICATION_TEXTS.groupTitle,
    // For native side, we send a pattern with {count} placeholder
    groupText: typeof texts.groupText === 'function'
      ? '{count} download(s) in progress' // Default pattern if function provided
      : (texts.groupText ?? '{count} download(s) in progress'),
  }
}
