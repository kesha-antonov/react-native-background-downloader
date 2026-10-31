import React from 'react'
import { Platform, Pressable, View } from 'react-native'
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons'
import { createThemedStyles, useTheme, type Tone } from '../theme'
import type { IoniconName } from './ToolbarButton'

export type MciName = React.ComponentProps<typeof MaterialCommunityIcons>['name']

export interface ActionSpec<A extends string = string> {
  action: A
  // Also the accessibility label: "<label> <id>"
  label: string
  icon: IoniconName
  // Takes precedence over `icon`, e.g. Ionicons has no "download again" glyph
  mciIcon?: MciName
  // A small badge in the corner, e.g. "again" on download again
  badge?: MciName
  tone: Tone
  disabled?: boolean
}

interface IconButtonProps {
  spec: ActionSpec
  rowId: string
  onPress: () => void
}

const specsEqual = (a: ActionSpec, b: ActionSpec) =>
  (Object.keys(b) as (keyof ActionSpec)[]).every(key => a[key] === b[key]) &&
  Object.keys(a).length === Object.keys(b).length

// A round 44pt action button. Memoized: onPress is a new arrow on every row render
// but only calls the row's action; skipping it keeps progress ticks from re-rendering
// the button mid-animation. A theme change still re-renders it through useTheme
const IconButton = React.memo(({ spec, rowId, onPress }: IconButtonProps) => {
  const theme = useTheme()
  const styles = useStyles()
  const tone = theme.tones[spec.tone]
  const bg = spec.disabled ? theme.colors.disabledBg : tone.bg
  const fg = spec.disabled ? theme.colors.disabledIcon : tone.fg

  return (
    <Pressable
      onPress={onPress}
      disabled={spec.disabled}
      accessibilityRole='button'
      accessibilityLabel={`${spec.label} ${rowId}`}
      accessibilityState={{ disabled: !!spec.disabled }}
      android_ripple={{ color: theme.colors.ripple, borderless: true, radius: 22 }}
      style={({ pressed }) => [
        styles.button,
        pressed && Platform.OS === 'ios' && styles.pressed,
      ]}
    >
      {/* The color is on this view: with android_ripple, a Pressable can keep its
          first background on Android, e.g. Delete's red on the Stop that replaces it */}
      <View style={[styles.fill, { backgroundColor: bg }]} />
      <View style={styles.content}>
        {spec.mciIcon
          ? <MaterialCommunityIcons name={spec.mciIcon} size={22} color={fg} />
          : <Ionicons name={spec.icon} size={20} color={fg} />}
        {spec.badge && (
          <View style={[styles.badge, { backgroundColor: fg }]}>
            {/* Dark tone backgrounds are translucent, so the glyph takes the card color there */}
            <MaterialCommunityIcons name={spec.badge} size={12} color={theme.dark ? theme.colors.card : bg} />
          </View>
        )}
      </View>
    </Pressable>
  )
}, (prev, next) => prev.rowId === next.rowId && specsEqual(prev.spec, next.spec))

export default IconButton

const useStyles = createThemedStyles(() => ({
  button: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pressed: {
    opacity: 0.6,
  },
  fill: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderRadius: 22,
  },
  content: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badge: {
    position: 'absolute',
    right: 2,
    bottom: 2,
    width: 18,
    height: 18,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
  },
}))
