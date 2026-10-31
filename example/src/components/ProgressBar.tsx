import React, { useEffect, useRef } from 'react'
import { StyleSheet, View } from 'react-native'
import Animated, {
  type SharedValue,
  Easing,
  cancelAnimation,
  interpolate,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated'
import { createThemedStyles, useTheme } from '../theme'

interface ProgressBarProps {
  progress: number
  indeterminate: boolean
  // Transferring now: the fill shimmers, so a slow transfer still looks alive
  active: boolean
  color: string
}

const SHIMMER_WIDTH = 0.35 // of the track

interface BarViewsProps {
  progress: SharedValue<number>
  sweep: SharedValue<number>
  trackWidth: SharedValue<number>
  color: SharedValue<string>
  shimmerColor: SharedValue<string>
  // 1 or 0: shared values, so the views never re-render (see ProgressBar)
  indeterminate: SharedValue<number>
  moving: SharedValue<number>
}

// The bar's views take only shared values and never re-render: a React commit of an
// animated view re-applies a stale copy of its animated style, which froze the bar part
// way (or made it lag) whenever the row re-rendered, e.g. on each progress event.
// For the same reason the colors (including theme colors) arrive as shared values, and
// this component must not read the theme itself
const BarViews = React.memo(({ progress, sweep, trackWidth, color, shimmerColor, indeterminate, moving }: BarViewsProps) => {
  // Scaled rather than sized from the left edge
  const fillStyle = useAnimatedStyle(() => ({
    backgroundColor: color.value,
    opacity: indeterminate.value ? 0 : 1,
    transform: [{ scaleX: progress.value }],
  }))

  // A light band crossing the fill, ending at its edge so it never shows on the empty track
  const shimmerStyle = useAnimatedStyle(() => {
    const width = trackWidth.value * SHIMMER_WIDTH
    const end = trackWidth.value * progress.value - width
    return {
      width,
      backgroundColor: shimmerColor.value,
      opacity: !indeterminate.value && moving.value ? 1 : 0,
      transform: [{ translateX: interpolate(sweep.value, [0, 1], [-width, end]) }],
    }
  })

  // For an unknown size: a segment crossing the whole track
  const segmentStyle = useAnimatedStyle(() => {
    const width = trackWidth.value * SHIMMER_WIDTH
    return {
      width,
      backgroundColor: color.value,
      opacity: indeterminate.value && moving.value ? 1 : 0,
      transform: [{ translateX: interpolate(sweep.value, [0, 1], [-width, trackWidth.value]) }],
    }
  })

  return (
    <>
      <Animated.View style={[barStyles.fill, fillStyle]} />
      <Animated.View style={[barStyles.band, shimmerStyle]} />
      <Animated.View style={[barStyles.band, segmentStyle]} />
    </>
  )
})

// Static, theme-independent: BarViews never re-renders, so it can't use a themed sheet
const barStyles = StyleSheet.create({
  fill: {
    ...StyleSheet.absoluteFill,
    borderRadius: 2,
    transformOrigin: 'left',
  },
  band: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    borderRadius: 2,
  },
})

// A thin scaleX-based bar. It glides linearly between progress events, shimmers while
// transferring and shows a sliding segment when the size is unknown
const ProgressBar = ({ progress, indeterminate, active, color }: ProgressBarProps) => {
  const theme = useTheme()
  const styles = useStyles()
  // Start from the current value so a row that mounts mid-transfer
  // doesn't animate from zero
  const progressAnim = useSharedValue(progress)
  const trackWidth = useSharedValue(0)
  const sweep = useSharedValue(0)
  const colorValue = useSharedValue(color)
  const shimmerColorValue = useSharedValue(theme.colors.shimmer)
  const indeterminateValue = useSharedValue(indeterminate ? 1 : 0)
  const movingValue = useSharedValue(0)
  const reduceMotion = useReducedMotion()
  const moving = (active || indeterminate) && !reduceMotion
  const shimmerColor = theme.colors.shimmer

  useEffect(() => {
    colorValue.value = color
    shimmerColorValue.value = shimmerColor
    indeterminateValue.value = indeterminate ? 1 : 0
    movingValue.value = moving ? 1 : 0
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [color, shimmerColor, indeterminate, moving])

  // Progress arrives in steps (one event per progressInterval). Gliding linearly to
  // each new value over the time the last step took keeps the bar moving at the
  // transfer's pace until the next event, instead of jumping and then standing still
  const lastUpdateAt = useRef(Date.now())
  useEffect(() => {
    const now = Date.now()
    const sinceLastUpdate = now - lastUpdateAt.current
    lastUpdateAt.current = now
    if (progress < progressAnim.value) {
      // Started over: no animating backwards
      progressAnim.value = progress
      return
    }
    const duration = progress >= 1 ? 250 : Math.min(Math.max(sinceLastUpdate, 150), 1500)
    progressAnim.value = withTiming(progress, { duration, easing: Easing.linear })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress])

  useEffect(() => {
    cancelAnimation(sweep)
    sweep.value = 0
    if (moving)
      sweep.value = withRepeat(
        withTiming(1, { duration: indeterminate ? 1100 : 1400, easing: Easing.inOut(Easing.quad) }),
        -1,
        false
      )
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [moving, indeterminate])

  return (
    <View
      style={styles.track}
      onLayout={event => { trackWidth.value = event.nativeEvent.layout.width }}
    >
      <BarViews
        progress={progressAnim}
        sweep={sweep}
        trackWidth={trackWidth}
        color={colorValue}
        shimmerColor={shimmerColorValue}
        indeterminate={indeterminateValue}
        moving={movingValue}
      />
    </View>
  )
}

export default ProgressBar

const useStyles = createThemedStyles(theme => ({
  track: {
    height: 4,
    borderRadius: 2,
    backgroundColor: theme.colors.track,
    overflow: 'hidden',
    marginTop: 12,
  },
}))
