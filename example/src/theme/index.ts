import { StyleSheet, useColorScheme, type ViewStyle, type TextStyle, type ImageStyle } from 'react-native'
import { DarkTheme, DefaultTheme, type Theme as NavigationTheme } from '@react-navigation/native'

// ---------------------------------------------------------------------------
// Semantic color tokens. Screens and components use these, never raw colors
// ---------------------------------------------------------------------------

export interface StatusColors {
  // Bar fill, state label, badge icon
  strong: string
  // Badge background
  soft: string
}

export interface ToneColors {
  bg: string
  fg: string
}

export type StatusKey = 'idle' | 'downloading' | 'paused' | 'done' | 'failed' | 'stopped'
export type Tone = 'primary' | 'warning' | 'neutral' | 'danger'

export interface Theme {
  dark: boolean
  colors: {
    background: string
    card: string
    border: string
    divider: string
    text: string
    textSecondary: string
    textMuted: string
    track: string
    primary: string
    primarySoft: string
    primaryText: string
    onPrimary: string
    // Secondary toolbar buttons
    control: string
    disabledBg: string
    disabledIcon: string
    // A second accent for non-status chrome, e.g. the notification settings icon
    accent: string
    accentSoft: string
    ripple: string
    rippleOnPrimary: string
    // The light band that crosses a moving progress bar
    shimmer: string
    switchTrackOff: string
    switchTrackOn: string
    switchThumbOff: string
    switchThumbOn: string
  }
  status: Record<StatusKey, StatusColors>
  tones: Record<Tone, ToneColors>
}

export const lightTheme: Theme = {
  dark: false,
  colors: {
    background: '#F3F4F6',
    card: '#FFFFFF',
    border: '#E5E7EB',
    divider: '#F1F5F9',
    text: '#0F172A',
    textSecondary: '#64748B',
    textMuted: '#94A3B8',
    track: '#E5E7EB',
    primary: '#2563EB',
    primarySoft: '#DBEAFE',
    primaryText: '#1D4ED8',
    onPrimary: '#FFFFFF',
    control: '#F1F5F9',
    disabledBg: '#F8FAFC',
    disabledIcon: '#CBD5E1',
    accent: '#4F46E5',
    accentSoft: '#EEF2FF',
    ripple: 'rgba(15, 23, 42, 0.10)',
    rippleOnPrimary: 'rgba(255, 255, 255, 0.25)',
    shimmer: 'rgba(255, 255, 255, 0.45)',
    switchTrackOff: '#CBD5E1',
    switchTrackOn: '#93C5FD',
    switchThumbOff: '#F8FAFC',
    switchThumbOn: '#2563EB',
  },
  status: {
    idle: { strong: '#64748B', soft: '#F1F5F9' },
    downloading: { strong: '#2563EB', soft: '#DBEAFE' },
    paused: { strong: '#D97706', soft: '#FEF3C7' },
    done: { strong: '#16A34A', soft: '#DCFCE7' },
    failed: { strong: '#DC2626', soft: '#FEE2E2' },
    stopped: { strong: '#6B7280', soft: '#F3F4F6' },
  },
  tones: {
    primary: { bg: '#DBEAFE', fg: '#1D4ED8' },
    warning: { bg: '#FEF3C7', fg: '#B45309' },
    neutral: { bg: '#F1F5F9', fg: '#334155' },
    danger: { bg: '#FEE2E2', fg: '#B91C1C' },
  },
}

// Dark: layered slate surfaces (cards lighter than the page), lighter strong
// colors for contrast, and translucent soft tints that sit on the card color
export const darkTheme: Theme = {
  dark: true,
  colors: {
    background: '#0D1117',
    card: '#171B23',
    border: '#2A303B',
    divider: '#222833',
    text: '#F1F5F9',
    textSecondary: '#9AA4B2',
    textMuted: '#6B7280',
    track: '#2A303B',
    primary: '#2F6FEB',
    primarySoft: 'rgba(59, 130, 246, 0.20)',
    primaryText: '#93C5FD',
    onPrimary: '#FFFFFF',
    control: '#232935',
    disabledBg: '#1C212A',
    disabledIcon: '#475163',
    accent: '#A5B4FC',
    accentSoft: 'rgba(99, 102, 241, 0.22)',
    ripple: 'rgba(255, 255, 255, 0.12)',
    rippleOnPrimary: 'rgba(255, 255, 255, 0.25)',
    shimmer: 'rgba(255, 255, 255, 0.35)',
    switchTrackOff: '#3A4250',
    switchTrackOn: '#1E4FA8',
    switchThumbOff: '#9AA4B2',
    switchThumbOn: '#60A5FA',
  },
  status: {
    idle: { strong: '#9AA4B2', soft: 'rgba(148, 163, 184, 0.14)' },
    downloading: { strong: '#60A5FA', soft: 'rgba(59, 130, 246, 0.20)' },
    paused: { strong: '#FBBF24', soft: 'rgba(245, 158, 11, 0.18)' },
    done: { strong: '#4ADE80', soft: 'rgba(34, 197, 94, 0.18)' },
    failed: { strong: '#F87171', soft: 'rgba(239, 68, 68, 0.18)' },
    stopped: { strong: '#A1A1AA', soft: 'rgba(161, 161, 170, 0.14)' },
  },
  tones: {
    primary: { bg: 'rgba(59, 130, 246, 0.20)', fg: '#93C5FD' },
    warning: { bg: 'rgba(245, 158, 11, 0.18)', fg: '#FCD34D' },
    neutral: { bg: '#262C38', fg: '#CBD5E1' },
    danger: { bg: 'rgba(239, 68, 68, 0.18)', fg: '#FCA5A5' },
  },
}

// Follows the OS color scheme
export const useTheme = (): Theme => useColorScheme() === 'dark' ? darkTheme : lightTheme

// ---------------------------------------------------------------------------
// Transfer status: shared by download and upload rows
// ---------------------------------------------------------------------------

export type TransferStatus = 'idle' | 'pending' | 'downloading' | 'uploading' | 'paused' | 'done' | 'failed' | 'stopped'

export const statusColors = (theme: Theme, status: TransferStatus): StatusColors => {
  switch (status) {
    case 'pending':
    case 'uploading':
      return theme.status.downloading
    default:
      return theme.status[status]
  }
}

// ---------------------------------------------------------------------------
// Theme-aware styles: one StyleSheet per theme, created on first use and cached,
// so components never re-create styles on render
// ---------------------------------------------------------------------------

// What StyleSheet.create takes; React Native 0.88's strict API no longer exports
// StyleSheet.NamedStyles. The constraint keeps style values literal ('600', 'center')
type NamedStyles<T> = { [P in keyof T]: ViewStyle | TextStyle | ImageStyle }

export const createThemedStyles = <T extends NamedStyles<T>>(factory: (theme: Theme) => T) => {
  const cache = new Map<Theme, T>()
  return (): T => {
    const theme = useTheme()
    let styles = cache.get(theme)
    if (!styles) {
      styles = StyleSheet.create(factory(theme))
      cache.set(theme, styles)
    }
    return styles
  }
}

// ---------------------------------------------------------------------------
// @react-navigation theme: headers, back button and screen backgrounds
// ---------------------------------------------------------------------------

const navigationThemes = new Map<Theme, NavigationTheme>()

export const getNavigationTheme = (theme: Theme): NavigationTheme => {
  let navTheme = navigationThemes.get(theme)
  if (!navTheme) {
    const base = theme.dark ? DarkTheme : DefaultTheme
    navTheme = {
      ...base,
      dark: theme.dark,
      colors: {
        ...base.colors,
        primary: theme.dark ? theme.colors.primaryText : theme.colors.primary,
        background: theme.colors.background,
        card: theme.colors.card,
        text: theme.colors.text,
        border: theme.colors.border,
        notification: theme.status.failed.strong,
      },
    }
    navigationThemes.set(theme, navTheme)
  }
  return navTheme
}
