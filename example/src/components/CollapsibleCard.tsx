import React, { useCallback, useEffect, useState } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import Animated, {
  Easing,
  interpolate,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated'
import { Ionicons } from '@expo/vector-icons'
import Card from './Card'
import { createThemedStyles, useTheme } from '../theme'
import type { IoniconName } from './ToolbarButton'

interface CollapsibleCardProps {
  title: string
  subtitle: string
  icon: IoniconName
  // Defaults to the title
  accessibilityLabel?: string
  children: React.ReactNode
}

// A card whose body unfolds under its header. Collapsed by default
const CollapsibleCard = ({ title, subtitle, icon, accessibilityLabel, children }: CollapsibleCardProps) => {
  const theme = useTheme()
  const styles = useStyles()
  const [isOpen, setIsOpen] = useState(false)
  // 0 closed … 1 open; drives the body's height, its fade and the chevron together
  const openness = useSharedValue(0)
  // The body's natural height, measured while it's laid out outside the clip
  const bodyHeight = useSharedValue(0)
  const reduceMotion = useReducedMotion()

  const toggle = useCallback(() => setIsOpen(open => !open), [])

  useEffect(() => {
    const target = isOpen ? 1 : 0
    openness.value = reduceMotion
      ? target
      : withTiming(target, { duration: 280, easing: Easing.out(Easing.cubic) })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, reduceMotion])

  const chevronStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${openness.value * 180}deg` }],
  }))

  const bodyClipStyle = useAnimatedStyle(() => ({
    height: bodyHeight.value * openness.value,
  }))

  const bodyContentStyle = useAnimatedStyle(() => ({
    opacity: interpolate(openness.value, [0, 0.4, 1], [0, 0, 1]),
    transform: [{ translateY: interpolate(openness.value, [0, 1], [-8, 0]) }],
  }))

  return (
    <Card>
      <Pressable
        onPress={toggle}
        style={styles.header}
        accessibilityRole='button'
        accessibilityLabel={accessibilityLabel ?? title}
        accessibilityState={{ expanded: isOpen }}
        android_ripple={{ color: theme.colors.ripple }}
      >
        <View style={styles.icon}>
          <Ionicons name={icon} size={18} color={theme.colors.accent} />
        </View>
        <View style={styles.text}>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.subtitle} numberOfLines={1}>{subtitle}</Text>
        </View>
        <Animated.View style={chevronStyle}>
          <Ionicons name='chevron-down' size={20} color={theme.colors.textSecondary} />
        </Animated.View>
      </Pressable>

      {/* Always rendered: the clip's height unfolds it. Hidden from accessibility and
          touches while closed */}
      <Animated.View
        style={[styles.clip, bodyClipStyle]}
        pointerEvents={isOpen ? 'auto' : 'none'}
        accessibilityElementsHidden={!isOpen}
        importantForAccessibility={isOpen ? 'auto' : 'no-hide-descendants'}
      >
        <Animated.View
          style={[styles.body, bodyContentStyle]}
          onLayout={event => { bodyHeight.value = event.nativeEvent.layout.height }}
        >
          {children}
        </Animated.View>
      </Animated.View>
    </Card>
  )
}

export default CollapsibleCard

const useStyles = createThemedStyles(theme => ({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 60,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  icon: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.accentSoft,
  },
  text: {
    flex: 1,
    minWidth: 0,
  },
  title: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.colors.text,
  },
  subtitle: {
    fontSize: 12,
    lineHeight: 16,
    color: theme.colors.textSecondary,
  },
  clip: {
    overflow: 'hidden',
  },
  // Absolute, so it keeps its natural height (measured for the clip) at any clip height
  body: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingHorizontal: 16,
    paddingBottom: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: theme.colors.border,
  },
}))
