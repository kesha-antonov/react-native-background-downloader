import Toast from 'react-native-root-toast'
import { initialWindowMetrics } from 'react-native-safe-area-context'

const safeTopInset =
  initialWindowMetrics != null && initialWindowMetrics.insets.top
    ? initialWindowMetrics.insets.top + 50
    : 100

export const uuid = () => Math.random().toString(36).substring(2, 6)
export const toast = (message, duration = 750, position = safeTopInset) => {
  Toast.show(message, { duration, position })
}
