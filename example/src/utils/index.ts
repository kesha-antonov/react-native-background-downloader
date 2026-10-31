import Toast from 'react-native-root-toast'
import { initialWindowMetrics } from 'react-native-safe-area-context'

const safeTopInset =
  initialWindowMetrics != null && initialWindowMetrics.insets.top
    ? initialWindowMetrics.insets.top + 50
    : 100

export const uuid = () => Math.random().toString(36).substring(2, 6)
export const toast = (message: string, duration = 750, position = safeTopInset) => {
  Toast.show(message, { duration, position })
}

export const formatBytes = (bytes: number): string => {
  if (bytes < 0) return 'Unknown'
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`
}

export const baseName = (path: string) => path.split('/').pop() || path
