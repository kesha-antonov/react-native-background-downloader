import React, { useEffect } from 'react'
import { View } from 'react-native'
import Animated, {
  Easing,
  cancelAnimation,
  interpolate,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated'
import { Ionicons } from '@expo/vector-icons'
import { createThemedStyles, statusColors, useTheme, type TransferStatus } from '../theme'
import type { IoniconName } from './ToolbarButton'

interface StateBadgeProps {
  status: TransferStatus
  icon: IoniconName
}

// A 40pt tinted square that acts out its state: the arrow keeps falling while
// downloading (rising while uploading), the hourglass turns while starting, and the
// pause icon breathes. Memoized so row progress ticks don't re-render it mid-animation
const StateBadge = React.memo(({ status, icon }: StateBadgeProps) => {
  const theme = useTheme()
  const styles = useStyles()
  const colors = statusColors(theme, status)
  const reduceMotion = useReducedMotion()
  const loop = useSharedValue(0)

  useEffect(() => {
    cancelAnimation(loop)
    loop.value = 0
    if (reduceMotion) return

    if (status === 'downloading' || status === 'uploading')
      loop.value = withRepeat(withTiming(1, { duration: 900, easing: Easing.in(Easing.quad) }), -1, false)
    else if (status === 'pending')
      loop.value = withRepeat(withTiming(1, { duration: 1200, easing: Easing.linear }), -1, false)
    else if (status === 'paused')
      loop.value = withRepeat(withTiming(1, { duration: 900, easing: Easing.inOut(Easing.quad) }), -1, true)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, reduceMotion])

  const iconStyle = useAnimatedStyle(() => {
    const t = loop.value
    if (status === 'downloading' || status === 'uploading')
      return {
        opacity: interpolate(t, [0, 0.25, 0.75, 1], [0, 1, 1, 0]),
        transform: [{
          translateY: status === 'downloading'
            ? interpolate(t, [0, 1], [-12, 12])
            : interpolate(t, [0, 1], [12, -12]),
        }, { rotate: '0deg' }],
      }
    // Every branch sets every animated property: one it leaves out keeps its last
    // value, e.g. the arrow's offset when the download finished mid-fall
    if (status === 'pending')
      return { opacity: 1, transform: [{ translateY: 0 }, { rotate: `${t * 360}deg` }] }
    if (status === 'paused')
      return { opacity: interpolate(t, [0, 1], [1, 0.4]), transform: [{ translateY: 0 }, { rotate: '0deg' }] }
    return { opacity: 1, transform: [{ translateY: 0 }, { rotate: '0deg' }] }
  })

  return (
    <View style={[styles.badge, { backgroundColor: colors.soft }]}>
      <Animated.View style={iconStyle}>
        <Ionicons name={icon} size={20} color={colors.strong} />
      </Animated.View>
    </View>
  )
})

export default StateBadge

const useStyles = createThemedStyles(() => ({
  badge: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    // The moving arrow enters and leaves through the edges
    overflow: 'hidden',
  },
}))
