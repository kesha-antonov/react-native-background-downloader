import React from 'react'
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { createThemedStyles, useTheme } from '../theme'

export type IoniconName = React.ComponentProps<typeof Ionicons>['name']

interface ToolbarButtonProps {
  label: string
  accessibilityLabel: string
  icon: IoniconName
  onPress: () => void
  variant?: 'filled' | 'plain'
  disabled?: boolean
}

// One of the equal-width 44pt buttons in a toolbar card
const ToolbarButton = ({ label, accessibilityLabel, icon, onPress, variant = 'plain', disabled }: ToolbarButtonProps) => {
  const theme = useTheme()
  const styles = useStyles()
  const filled = variant === 'filled'
  const fg = disabled ? theme.colors.disabledIcon : filled ? theme.colors.onPrimary : theme.colors.text
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole='button'
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: !!disabled }}
      android_ripple={{ color: filled ? theme.colors.rippleOnPrimary : theme.colors.ripple }}
      style={({ pressed }) => [
        styles.button,
        pressed && Platform.OS === 'ios' && styles.pressed,
      ]}
    >
      {/* The color is on this view: with android_ripple, a Pressable can keep its first
          background on Android, e.g. the dark theme's after switching to light */}
      <View style={[styles.fill, disabled ? styles.disabled : filled ? styles.filled : styles.plain]} />
      <Ionicons name={icon} size={18} color={fg} />
      <Text style={[styles.text, { color: fg }]} numberOfLines={1}>{label}</Text>
    </Pressable>
  )
}

export default ToolbarButton

const useStyles = createThemedStyles(theme => ({
  button: {
    flex: 1,
    height: 44,
    borderRadius: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingHorizontal: 8,
    overflow: 'hidden',
  },
  fill: {
    ...StyleSheet.absoluteFillObject,
  },
  filled: {
    backgroundColor: theme.colors.primary,
  },
  plain: {
    backgroundColor: theme.colors.control,
  },
  disabled: {
    backgroundColor: theme.colors.disabledBg,
  },
  pressed: {
    opacity: 0.6,
  },
  text: {
    fontSize: 14,
    fontWeight: '600',
  },
}))
