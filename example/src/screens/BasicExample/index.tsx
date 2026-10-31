import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo, Pressable, Switch, Platform, PermissionsAndroid, Alert, Linking } from 'react-native'
import Animated, { useSharedValue, useAnimatedStyle, withTiming, FadeIn, FadeOut } from 'react-native-reanimated'
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons'
import { Directory, File, Paths } from 'expo-file-system'
import {
  completeHandler,
  getExistingDownloadTasks,
  createDownloadTask,
  setConfig,
  directories,
} from '@kesha-antonov/react-native-background-downloader'
import type { DownloadTask } from '@kesha-antonov/react-native-background-downloader'
import { toast, uuid } from '../../utils'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { createMMKV } from 'react-native-mmkv'

const DOWNLOADS_SUBDIR = 'downloads'

// Storage helper for persisting task IDs between app restarts
const storage = createMMKV({ id: 'download-example-storage' })
const TASK_IDS_KEY = 'taskIds'
const SHOW_NOTIFICATIONS_KEY = 'showNotificationsEnabled'
const NOTIFICATION_GROUPING_KEY = 'notificationGroupingEnabled'
const SUMMARY_ONLY_MODE_KEY = 'summaryOnlyMode'
const CANCEL_ACTION_KEY = 'showCancelAction'
const COMPLETION_NOTIFICATION_KEY = 'showCompletionNotification'

const TaskIdStorage = {
  load: (): Record<string, string> => {
    try {
      const json = storage.getString(TASK_IDS_KEY)
      return json ? JSON.parse(json) : {}
    } catch (e) {
      console.warn('Failed to load persisted task IDs:', e)
      return {}
    }
  },

  save: (mapping: Record<string, string>) => {
    storage.set(TASK_IDS_KEY, JSON.stringify(mapping))
  },

  getOrCreate: (url: string): string => {
    const mapping = TaskIdStorage.load()
    if (mapping[url]) return mapping[url]

    const newId = uuid()
    mapping[url] = newId
    TaskIdStorage.save(mapping)
    return newId
  },

  clear: (url: string) => {
    const mapping = TaskIdStorage.load()
    delete mapping[url]
    TaskIdStorage.save(mapping)
  },

  clearAll: () => {
    storage.remove(TASK_IDS_KEY)
  },
}

// ---------------------------------------------------------------------------
// Design tokens
// ---------------------------------------------------------------------------

const colors = {
  background: '#F3F4F6',
  surface: '#FFFFFF',
  border: '#E5E7EB',
  divider: '#F1F5F9',
  text: '#0F172A',
  textSecondary: '#64748B',
  textMuted: '#94A3B8',
  primary: '#2563EB',
  primarySoft: '#DBEAFE',
  primaryText: '#1D4ED8',
  track: '#E5E7EB',
  disabledBg: '#F8FAFC',
  disabledIcon: '#CBD5E1',
}

type RowStatus = 'idle' | 'pending' | 'downloading' | 'paused' | 'done' | 'failed' | 'stopped'

interface StatusStyle {
  label: string
  icon: React.ComponentProps<typeof Ionicons>['name']
  // Strong color: bar fill, state label, badge icon
  color: string
  // Soft color: badge background
  soft: string
}

const STATUS_STYLES: Record<RowStatus, StatusStyle> = {
  idle: { label: 'Not downloaded', icon: 'cloud-download-outline', color: '#64748B', soft: '#F1F5F9' },
  pending: { label: 'Starting…', icon: 'hourglass-outline', color: '#2563EB', soft: '#DBEAFE' },
  downloading: { label: 'Downloading', icon: 'arrow-down', color: '#2563EB', soft: '#DBEAFE' },
  paused: { label: 'Paused', icon: 'pause', color: '#D97706', soft: '#FEF3C7' },
  done: { label: 'Downloaded', icon: 'checkmark', color: '#16A34A', soft: '#DCFCE7' },
  failed: { label: 'Failed', icon: 'alert', color: '#DC2626', soft: '#FEE2E2' },
  stopped: { label: 'Stopped', icon: 'stop', color: '#6B7280', soft: '#F3F4F6' },
}

const isActiveStatus = (status: RowStatus) =>
  status === 'pending' || status === 'downloading' || status === 'paused'

const statusFromTask = (task: DownloadTask): RowStatus => {
  switch (task.state) {
    case 'PENDING': return 'pending'
    case 'DOWNLOADING': return 'downloading'
    case 'PAUSED': return 'paused'
    case 'DONE': return 'done'
    case 'FAILED': return 'failed'
    case 'STOPPED': return 'stopped'
    default: return 'idle'
  }
}

const formatBytes = (bytes: number): string => {
  if (bytes < 0) return 'Unknown'
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`
}

const baseName = (path: string) => path.split('/').pop() || path

// ---------------------------------------------------------------------------
// Row model: one row per download, whatever its lifecycle stage
// ---------------------------------------------------------------------------

interface UrlItem {
  id: string
  url: string
  maxRedirects?: number
  title?: string
}

type RowKind =
  | 'url' // a predefined URL, can be (re)started
  | 'task' // a task that doesn't belong to a predefined URL (batch, restored)
  | 'file' // a file on disk that no row claims

// Only primitives, so React.memo can compare rows field by field
interface RowModel {
  key: string
  id: string
  kind: RowKind
  title: string
  subtitle: string
  status: RowStatus
  bytesDownloaded: number
  bytesTotal: number
  hasTask: boolean
  hasFile: boolean
  fileName: string | null
  path: string | null
  url: string | null
  errorCode: number | null
}

type RowAction = 'start' | 'pause' | 'resume' | 'stop' | 'delete' | 'remove' | 'details'

interface ActionSpec {
  action: RowAction
  label: string
  icon: React.ComponentProps<typeof Ionicons>['name']
  // Takes precedence over `icon`: Ionicons has no "download again" glyph
  mciIcon?: React.ComponentProps<typeof MaterialCommunityIcons>['name']
  // A small badge in the corner, e.g. "again" on download again
  badge?: React.ComponentProps<typeof MaterialCommunityIcons>['name']
  tone: 'primary' | 'warning' | 'neutral' | 'danger'
  disabled?: boolean
}

const TONES: Record<ActionSpec['tone'], { bg: string, fg: string }> = {
  primary: { bg: colors.primarySoft, fg: colors.primaryText },
  warning: { bg: '#FEF3C7', fg: '#B45309' },
  neutral: { bg: '#F1F5F9', fg: '#334155' },
  danger: { bg: '#FEE2E2', fg: '#B91C1C' },
}

// Two action slots per row. The slots never appear or disappear: their
// content is swapped in place, so the row keeps the same footprint in every state
const getRowActions = (row: RowModel): [ActionSpec, ActionSpec] => {
  const active = isActiveStatus(row.status)

  let primary: ActionSpec
  if (row.status === 'downloading')
    primary = { action: 'pause', label: 'Pause', icon: 'pause', tone: 'warning' }
  else if (row.status === 'pending')
    primary = { action: 'pause', label: 'Pause', icon: 'pause', tone: 'warning', disabled: true }
  else if (row.status === 'paused')
    primary = { action: 'resume', label: 'Resume', icon: 'play', tone: 'primary' }
  else if (row.kind === 'url')
    primary = {
      action: 'start',
      label: 'Start',
      icon: 'download-outline',
      mciIcon: 'download',
      badge: row.status === 'idle' ? undefined : 'sync',
      tone: 'primary',
    }
  else
    primary = { action: 'details', label: 'Details', icon: 'information', tone: 'neutral' }

  let secondary: ActionSpec
  if (active)
    secondary = { action: 'stop', label: 'Stop', icon: 'stop', tone: 'neutral' }
  else if (row.hasFile)
    secondary = { action: 'delete', label: 'Delete', icon: 'trash-outline', tone: 'danger' }
  else if (row.hasTask)
    secondary = { action: 'remove', label: 'Remove', icon: 'close', tone: 'neutral' }
  else
    secondary = { action: 'delete', label: 'Delete', icon: 'trash-outline', tone: 'danger', disabled: true }

  return [primary, secondary]
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

interface IconButtonProps {
  spec: ActionSpec
  rowId: string
  onPress: () => void
}

const IconButton = ({ spec, rowId, onPress }: IconButtonProps) => {
  const tone = TONES[spec.tone]
  const bg = spec.disabled ? colors.disabledBg : tone.bg
  const fg = spec.disabled ? colors.disabledIcon : tone.fg

  return (
    <Pressable
      onPress={onPress}
      disabled={spec.disabled}
      accessibilityRole='button'
      accessibilityLabel={`${spec.label} ${rowId}`}
      accessibilityState={{ disabled: !!spec.disabled }}
      android_ripple={{ color: 'rgba(15, 23, 42, 0.12)', borderless: true, radius: 22 }}
      style={({ pressed }) => [
        styles.iconButton,
        { backgroundColor: bg },
        pressed && Platform.OS === 'ios' && styles.pressed,
      ]}
    >
      {spec.mciIcon
        ? <MaterialCommunityIcons name={spec.mciIcon} size={22} color={fg} />
        : <Ionicons name={spec.icon} size={20} color={fg} />}
      {spec.badge && (
        <View style={[styles.iconBadge, { backgroundColor: fg }]}>
          <MaterialCommunityIcons name={spec.badge} size={12} color={bg} />
        </View>
      )}
    </Pressable>
  )
}

interface ProgressBarProps {
  progress: number
  indeterminate: boolean
  color: string
}

const ProgressBar = ({ progress, indeterminate, color }: ProgressBarProps) => {
  // Start from the current value so a row that mounts mid-download
  // doesn't animate from zero
  const progressAnim = useSharedValue(progress)

  useEffect(() => {
    progressAnim.value = withTiming(progress, { duration: 300 })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress])

  const animatedStyle = useAnimatedStyle(() => ({
    width: `${progressAnim.value * 100}%`,
  }))

  return (
    <View style={styles.progressTrack}>
      <Animated.View
        style={[
          styles.progressFill,
          { backgroundColor: color },
          indeterminate ? styles.progressIndeterminate : animatedStyle,
        ]}
      />
    </View>
  )
}

const rowsEqual = (a: RowModel, b: RowModel) => {
  for (const key of Object.keys(a) as (keyof RowModel)[])
    if (a[key] !== b[key]) return false

  return true
}

interface DownloadRowProps {
  row: RowModel
  onAction: (action: RowAction, key: string) => void
}

const DownloadRow = React.memo(({ row, onAction }: DownloadRowProps) => {
  const status = STATUS_STYLES[row.status]
  const [primary, secondary] = getRowActions(row)

  const isTotalUnknown = row.bytesTotal <= 0
  const showsBytes = row.status !== 'idle' && row.status !== 'pending'
  const progress = row.status === 'done'
    ? 1
    : isTotalUnknown ? 0 : Math.min(1, row.bytesDownloaded / row.bytesTotal)
  const indeterminate = row.status === 'downloading' && isTotalUnknown

  let bytesText = ''
  if (showsBytes)
    bytesText = row.status === 'done' || isTotalUnknown
      ? formatBytes(row.bytesDownloaded)
      : `${formatBytes(row.bytesDownloaded)} / ${formatBytes(row.bytesTotal)}`

  const percentText = showsBytes && (row.status === 'done' || !isTotalUnknown)
    ? `${Math.round(progress * 100)}%`
    : ''

  const stateLabel = row.status === 'failed' && row.errorCode
    ? `${status.label} · ${row.errorCode}`
    : status.label

  return (
    <View style={styles.row}>
      <View style={styles.rowHeader}>
        <Pressable
          style={styles.rowInfo}
          onPress={() => onAction('details', row.key)}
          accessibilityRole='button'
          accessibilityLabel={`Details ${row.id}`}
          accessibilityHint='Shows the URL and file path'
        >
          <View style={[styles.badge, { backgroundColor: status.soft }]}>
            <Ionicons name={status.icon} size={20} color={status.color} />
          </View>
          <View style={styles.rowText}>
            <Text style={styles.rowTitle} numberOfLines={1}>{row.title}</Text>
            <Text style={styles.rowSubtitle} numberOfLines={1}>{row.subtitle}</Text>
          </View>
        </Pressable>
        <View style={styles.rowActions}>
          <IconButton spec={primary} rowId={row.id} onPress={() => onAction(primary.action, row.key)} />
          <IconButton spec={secondary} rowId={row.id} onPress={() => onAction(secondary.action, row.key)} />
        </View>
      </View>

      {/* Progress area is always rendered, so the row height never changes */}
      <ProgressBar
        progress={progress}
        indeterminate={indeterminate}
        color={row.status === 'idle' ? colors.track : status.color}
      />
      <View style={styles.metaRow}>
        <Text style={[styles.metaState, { color: status.color }]} numberOfLines={1}>
          {stateLabel}
        </Text>
        <Text style={styles.metaBytes} numberOfLines={1}>
          {bytesText}
          {bytesText && percentText ? '  ·  ' : ''}
          {percentText ? <Text style={styles.metaPercent}>{percentText}</Text> : null}
        </Text>
      </View>
    </View>
  )
}, (prev, next) => prev.onAction === next.onAction && rowsEqual(prev.row, next.row))

interface ToolbarButtonProps {
  label: string
  accessibilityLabel: string
  icon: React.ComponentProps<typeof Ionicons>['name']
  onPress: () => void
  variant?: 'filled' | 'plain'
  disabled?: boolean
}

const ToolbarButton = ({ label, accessibilityLabel, icon, onPress, variant = 'plain', disabled }: ToolbarButtonProps) => {
  const filled = variant === 'filled'
  const fg = disabled ? colors.disabledIcon : filled ? '#FFFFFF' : colors.text
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole='button'
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: !!disabled }}
      android_ripple={{ color: filled ? 'rgba(255,255,255,0.25)' : 'rgba(15,23,42,0.08)' }}
      style={({ pressed }) => [
        styles.toolbarButton,
        filled ? styles.toolbarButtonFilled : styles.toolbarButtonPlain,
        disabled && styles.toolbarButtonDisabled,
        pressed && Platform.OS === 'ios' && styles.pressed,
      ]}
    >
      <Ionicons name={icon} size={18} color={fg} />
      <Text style={[styles.toolbarButtonText, { color: fg }]} numberOfLines={1}>{label}</Text>
    </Pressable>
  )
}

interface SettingRowProps {
  label: string
  description: string
  value: boolean
  onValueChange: (value: boolean) => void
  disabled?: boolean
}

const SettingRow = ({ label, description, value, onValueChange, disabled }: SettingRowProps) => (
  <View style={[styles.settingRow, disabled && styles.settingRowDisabled]}>
    <View style={styles.settingInfo}>
      <Text style={styles.settingLabel}>{label}</Text>
      <Text style={styles.settingDescription}>{description}</Text>
    </View>
    <Switch
      value={value}
      onValueChange={onValueChange}
      disabled={disabled}
      accessibilityLabel={label}
      trackColor={{ false: '#CBD5E1', true: '#93C5FD' }}
      thumbColor={value ? colors.primary : '#F8FAFC'}
    />
  </View>
)

interface NotificationSettingsProps {
  showNotificationsEnabled: boolean
  onShowNotificationsEnabledChange: (show: boolean) => void
  notificationGroupingEnabled: boolean
  onNotificationGroupingChange: (enabled: boolean) => void
  summaryOnlyMode: boolean
  onSummaryOnlyModeChange: (enabled: boolean) => void
  showCancelAction: boolean
  onShowCancelActionChange: (enabled: boolean) => void
  showCompletionNotification: boolean
  onShowCompletionNotificationChange: (enabled: boolean) => void
}

// Developer settings, collapsed by default so the list comes first
const NotificationSettingsCard = (props: NotificationSettingsProps) => {
  const [isOpen, setIsOpen] = useState(false)
  const rotation = useSharedValue(0)

  const toggle = useCallback(() => setIsOpen(open => !open), [])

  useEffect(() => {
    rotation.value = withTiming(isOpen ? 180 : 0, { duration: 200 })
  }, [isOpen, rotation])

  const chevronStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }))

  const summary = useMemo(() => {
    if (!props.showNotificationsEnabled) return 'Off'
    const parts = ['On']
    if (props.notificationGroupingEnabled)
      parts.push(props.summaryOnlyMode ? 'summary only' : 'grouped')
    if (props.showCancelAction) parts.push('cancel')
    if (props.showCompletionNotification) parts.push('completion')
    return parts.join(' · ')
  }, [props.showNotificationsEnabled, props.notificationGroupingEnabled, props.summaryOnlyMode, props.showCancelAction, props.showCompletionNotification])

  const batchHint = props.summaryOnlyMode
    ? 'Start 5 shows only ONE summary notification.'
    : props.notificationGroupingEnabled
      ? 'Start 5 shows grouped notifications.'
      : 'Start 5 shows individual notifications.'

  return (
    <View style={styles.card}>
      <Pressable
        onPress={toggle}
        style={styles.settingsHeader}
        accessibilityRole='button'
        accessibilityLabel='Notification settings'
        accessibilityState={{ expanded: isOpen }}
        android_ripple={{ color: 'rgba(15,23,42,0.06)' }}
      >
        <View style={[styles.badgeSmall, { backgroundColor: '#EEF2FF' }]}>
          <Ionicons name='notifications-outline' size={18} color='#4F46E5' />
        </View>
        <View style={styles.rowText}>
          <Text style={styles.settingsTitle}>Notification settings</Text>
          <Text style={styles.rowSubtitle} numberOfLines={1}>Android · {summary}</Text>
        </View>
        <Animated.View style={chevronStyle}>
          <Ionicons name='chevron-down' size={20} color={colors.textSecondary} />
        </Animated.View>
      </Pressable>

      {isOpen && (
        <Animated.View
          entering={FadeIn.duration(150)}
          exiting={FadeOut.duration(100)}
          style={styles.settingsBody}
        >
          <SettingRow
            label='Show notifications'
            description='Display download progress notifications'
            value={props.showNotificationsEnabled}
            onValueChange={props.onShowNotificationsEnabledChange}
          />
          <SettingRow
            label='Group notifications'
            description='Group multiple downloads under one notification'
            value={props.notificationGroupingEnabled}
            onValueChange={props.onNotificationGroupingChange}
            disabled={!props.showNotificationsEnabled}
          />
          <SettingRow
            label='Summary only mode'
            description='Show only 1 summary notification with aggregate progress'
            value={props.summaryOnlyMode}
            onValueChange={props.onSummaryOnlyModeChange}
            disabled={!props.showNotificationsEnabled || !props.notificationGroupingEnabled}
          />
          <SettingRow
            label='Cancel action (Android 14+)'
            description='Add a Cancel button to the download notification'
            value={props.showCancelAction}
            onValueChange={props.onShowCancelActionChange}
            disabled={!props.showNotificationsEnabled}
          />
          <SettingRow
            label='Completion notification (Android 14+)'
            description='Notify when a download finishes; tap it to open the file'
            value={props.showCompletionNotification}
            onValueChange={props.onShowCompletionNotificationChange}
            disabled={!props.showNotificationsEnabled}
          />
          <Text style={styles.settingsHint}>{batchHint}</Text>
        </Animated.View>
      )}
    </View>
  )
}

interface HeaderProps extends NotificationSettingsProps {
  rowCount: number
  activeCount: number
  taskCount: number
  fileCount: number
  onBatchDownload: () => void
  onClearTasks: () => void
  onDeleteFiles: () => void
}

const Header = React.memo(({ rowCount, activeCount, taskCount, fileCount, onBatchDownload, onClearTasks, onDeleteFiles, ...settings }: HeaderProps) => (
  <View style={styles.header}>
    <View style={styles.card}>
      <View style={styles.toolbarSummary}>
        <Text style={styles.toolbarTitle}>Downloads</Text>
        <Text style={styles.toolbarStats} numberOfLines={1}>
          {rowCount} items · {activeCount} active · {fileCount} on disk
        </Text>
      </View>
      <View style={styles.toolbarActions}>
        <ToolbarButton
          label='Start 5'
          accessibilityLabel='Start 5 Downloads'
          icon='cloud-download-outline'
          variant='filled'
          onPress={onBatchDownload}
        />
        <ToolbarButton
          label='Clear tasks'
          accessibilityLabel='Clear all tasks'
          icon='stop-circle-outline'
          onPress={onClearTasks}
          disabled={taskCount === 0}
        />
        <ToolbarButton
          label='Delete files'
          accessibilityLabel='Delete all files'
          icon='trash-outline'
          onPress={onDeleteFiles}
          disabled={fileCount === 0}
        />
      </View>
    </View>

    {Platform.OS === 'android' && <NotificationSettingsCard {...settings} />}
  </View>
))

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

const BasicExampleScreen = () => {
  const insets = useSafeAreaInsets()

  // URL definitions - static, no storage access
  const urlDefinitions = useMemo(() => [
    {
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
      title: 'MP3 Audio · 6MB',
    },
    {
      url: 'https://filesamples.com/samples/image/jpeg/sample_5184%C3%973456.jpeg',
      title: 'JPEG · 4K · 5.30 MB',
    },
    {
      url: 'https://proof.ovh.net/files/100Mb.dat',
      title: 'Binary file · 100MB',
    },
    {
      url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/CRMDA/claritaspod.com/measure/pscrb.fm/rss/p/stitcher.simplecastaudio.com/9aa1e238-cbed-4305-9808-c9228fc6dd4f/episodes/b0c9a72a-1cb7-4ac9-80a0-36996fc6470f/audio/128/default.mp3?aid=rss_feed&awCollectionId=9aa1e238-cbed-4305-9808-c9228fc6dd4f&awEpisodeId=b0c9a72a-1cb7-4ac9-80a0-36996fc6470f&feed=dxZsm5kX',
      maxRedirects: 10,
      title: 'Podcast with redirects',
    },
    {
      url: 'https://filesamples.com/samples/video/mp4/sample_3840x2160.mp4',
      title: '4K Video · 126MB',
    },
    {
      url: 'https://testfile.org/1.3GBiconpng',
      title: 'Large File · 1GB',
    },
    {
      url: 'https://firebasestorage.googleapis.com/v0/b/higher-self-7603e.appspot.com/o/hypnosis-sounds%2FjiDIGAaGrNOdN3hjb4xemmGRZyu2-Tue,%2018%20Nov%202025%2010:10:30%20GMT?alt=media&token=12152238-c662-4c94-94e6-864396e41534',
      title: 'Firebase Storage Test',
    },
  ], [])

  // State for URL list with IDs - initialized empty, populated after mount
  const [urlList, setUrlList] = useState<UrlItem[]>([])

  const [downloadTasks, setDownloadTasks] = useState<Map<string, DownloadTask>>(new Map())
  const [destinations, setDestinations] = useState<Map<string, string>>(new Map())
  // Files in the downloads directory: name -> size in bytes
  const [diskFiles, setDiskFiles] = useState<Map<string, number>>(new Map())
  const [notificationGroupingEnabled, setNotificationGroupingEnabled] = useState(() => {
    return storage.getBoolean(NOTIFICATION_GROUPING_KEY) ?? false
  })
  const [showNotificationsEnabled, setShowNotificationsEnabled] = useState(() => {
    return storage.getBoolean(SHOW_NOTIFICATIONS_KEY) ?? false
  })
  const [summaryOnlyMode, setSummaryOnlyMode] = useState(() => {
    return storage.getBoolean(SUMMARY_ONLY_MODE_KEY) ?? false
  })
  // Android 14+ notification extras - both opt-in, independent of showNotificationsEnabled
  const [showCancelAction, setShowCancelAction] = useState(() => {
    return storage.getBoolean(CANCEL_ACTION_KEY) ?? false
  })
  const [showCompletionNotification, setShowCompletionNotification] = useState(() => {
    return storage.getBoolean(COMPLETION_NOTIFICATION_KEY) ?? false
  })

  // Latest values for the row action handler, which stays stable so
  // rows don't all re-render on every progress tick
  const tasksRef = useRef(downloadTasks)
  tasksRef.current = downloadTasks

  // The effect below re-applies the whole notification config whenever any of
  // these change, so the toggles only have to persist their own value
  const handleShowCancelActionChange = useCallback((enabled: boolean) => {
    setShowCancelAction(enabled)
    storage.set(CANCEL_ACTION_KEY, enabled)
  }, [])

  const handleShowCompletionNotificationChange = useCallback((enabled: boolean) => {
    setShowCompletionNotification(enabled)
    storage.set(COMPLETION_NOTIFICATION_KEY, enabled)
  }, [])

  // Handle notification grouping toggle with persistence
  const handleNotificationGroupingChange = useCallback((enabled: boolean) => {
    setNotificationGroupingEnabled(enabled)
    storage.set(NOTIFICATION_GROUPING_KEY, enabled)
    // Apply to native immediately via setConfig
    setConfig({
      notificationsGrouping: {
        enabled,
        mode: summaryOnlyMode ? 'summaryOnly' : 'individual',
        texts: {
          downloadTitle: 'Download',
          downloadStarting: 'Starting...',
          downloadProgress: 'Downloading... {progress}%',
          downloadFinished: 'Complete',
          groupTitle: 'Downloads',
          groupText: '{count} downloads in progress',
        },
      },
      showNotificationsEnabled,
    })
  }, [showNotificationsEnabled, summaryOnlyMode])

  // Handle summaryOnly mode toggle with persistence
  const handleSummaryOnlyModeChange = useCallback((enabled: boolean) => {
    setSummaryOnlyMode(enabled)
    storage.set(SUMMARY_ONLY_MODE_KEY, enabled)
    // Apply to native immediately via setConfig
    setConfig({
      notificationsGrouping: {
        enabled: notificationGroupingEnabled,
        mode: enabled ? 'summaryOnly' : 'individual',
        texts: {
          downloadTitle: 'Download',
          downloadStarting: 'Starting...',
          downloadProgress: 'Downloading... {progress}%',
          downloadFinished: 'Complete',
          groupTitle: 'Batch Download',
          groupText: '{count} files downloading',
        },
      },
      showNotificationsEnabled,
    })
  }, [showNotificationsEnabled, notificationGroupingEnabled])

  // Request POST_NOTIFICATIONS permission on Android 13+
  const requestNotificationPermission = useCallback(async (): Promise<boolean> => {
    if (Platform.OS !== 'android') return true
    if (Platform.Version < 33) return true // Not needed below Android 13

    try {
      // First check if already granted
      const hasPermission = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      )
      if (hasPermission) return true

      // Request permission
      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
        {
          title: 'Notification Permission',
          message: 'This app needs notification permission to show download progress.',
          buttonPositive: 'Allow',
          buttonNegative: 'Deny',
        }
      )

      if (result === PermissionsAndroid.RESULTS.GRANTED) {
        return true
      }

      // If denied or never_ask_again, offer to open settings
      if (result === PermissionsAndroid.RESULTS.DENIED || result === PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN) {
        Alert.alert(
          'Permission Required',
          'Notification permission is required. Please enable it in app settings.',
          [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Open Settings',
              onPress: () => Linking.openSettings(),
            },
          ]
        )
      }
      return false
    } catch (e) {
      console.warn('Failed to request notification permission:', e)
      return false
    }
  }, [])

  // Handle show notifications toggle with persistence and permission request
  const handleShowNotificationsChange = useCallback(async (show: boolean) => {
    if (show) {
      const granted = await requestNotificationPermission()
      if (!granted) {
        // Alert is already shown by requestNotificationPermission
        return
      }
    }
    setShowNotificationsEnabled(show)
    storage.set(SHOW_NOTIFICATIONS_KEY, show)
    // Apply to native immediately via setConfig
    setConfig({
      notificationsGrouping: {
        enabled: notificationGroupingEnabled,
        mode: summaryOnlyMode ? 'summaryOnly' : 'individual',
        texts: {
          downloadTitle: 'Download',
          downloadStarting: 'Starting...',
          downloadProgress: 'Downloading... {progress}%',
          downloadFinished: 'Complete',
          groupTitle: 'Downloads',
          groupText: '{count} downloads in progress',
        },
      },
      showNotificationsEnabled: show,
    })
  }, [requestNotificationPermission, notificationGroupingEnabled, summaryOnlyMode])

  const getDownloadsDir = useCallback(() => {
    // Use Paths.document from expo-file-system which has proper permissions
    return new Directory(Paths.document, DOWNLOADS_SUBDIR)
  }, [])

  const getDownloadsDirPath = useCallback(() => {
    // For the download library, we need path without file:// prefix
    // Use directories.documents from our library if available, otherwise extract from expo Paths
    if (directories.documents)
      return directories.documents + '/' + DOWNLOADS_SUBDIR

    // Paths.document.uri is a URI string, remove file:// prefix
    return Paths.document.uri.replace('file://', '') + '/' + DOWNLOADS_SUBDIR
  }, [])

  const ensureDownloadsDirExists = useCallback(() => {
    const downloadsDir = getDownloadsDir()
    if (!downloadsDir.exists)
      downloadsDir.create()

    return downloadsDir
  }, [getDownloadsDir])

  const readStorage = useCallback(() => {
    try {
      const downloadsDir = getDownloadsDir()
      if (!downloadsDir.exists) {
        setDiskFiles(new Map())
        return
      }
      const files = new Map<string, number>()
      downloadsDir.list().forEach(item => {
        files.set(item.name, item instanceof File ? item.size : 0)
      })
      setDiskFiles(files)
    } catch (error) {
      console.warn('readStorage error:', error)
      toast('Error reading files')
    }
  }, [getDownloadsDir])

  const updateTask = useCallback((task: DownloadTask) => {
    // Store the actual task instance, not a copy, to preserve methods
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
  }, [])

  const process = useCallback((task: DownloadTask) => {
    return task
      .begin(() => {
        updateTask(task)
      })
      .progress(() => {
        updateTask(task)
      })
      .done(() => {
        updateTask(task)
        completeHandler(task.id)
        readStorage()
      })
      .error(({ error, errorCode }) => {
        console.error('task: error', { id: task.id, error, errorCode })
        updateTask(task)
        completeHandler(task.id)
      })
  }, [updateTask, readStorage])

  const resumeExistingTasks = useCallback(async () => {
    try {
      const tasks = await getExistingDownloadTasks()

      if (tasks.length > 0) {
        tasks.forEach(task => process(task))
        setDownloadTasks(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => newMap.set(task.id, task))
          return newMap
        })
        // Restore destinations from task's destination property or metadata
        setDestinations(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => {
            // First try native destination, then metadata
            const dest = task.destination || (task.metadata as { destination?: string } | undefined)?.destination
            if (dest)
              newMap.set(task.id, dest)
          })
          return newMap
        })
      }
    } catch (e) {
      console.warn('getExistingDownloadTasks e', e)
    }
  }, [process])

  const clearStorage = useCallback(() => {
    try {
      const downloadsDir = getDownloadsDir()
      if (!downloadsDir.exists) {
        setDiskFiles(new Map())
        setDestinations(new Map())
        return
      }
      const contents = downloadsDir.list()
      contents.forEach(item => item.delete())
      setDiskFiles(new Map())
      setDestinations(new Map())
      toast('All files deleted')
    } catch (error) {
      console.warn('clearStorage error:', error)
      toast('Error clearing files')
    }
  }, [getDownloadsDir])

  const reset = useCallback(() => {
    tasksRef.current.forEach(task => task.stop())
    setDownloadTasks(new Map())
  }, [])

  const forgetTask = useCallback((id: string) => {
    setDownloadTasks(prev => {
      const newMap = new Map(prev)
      newMap.delete(id)
      return newMap
    })
    setDestinations(prev => {
      const newMap = new Map(prev)
      newMap.delete(id)
      return newMap
    })
  }, [])

  const getFileNameFromUrl = useCallback((url: string, fallbackId: string): string => {
    try {
      // Parse the URL and get the pathname
      const urlObj = new URL(url)
      const pathname = decodeURIComponent(urlObj.pathname)
      // Get the last segment of the path
      const segments = pathname.split('/').filter(Boolean)
      const lastSegment = segments[segments.length - 1]
      // Check if it looks like a filename (has an extension)
      if (lastSegment && lastSegment.includes('.'))
        return lastSegment
    } catch (e) {
      console.warn('Failed to parse URL for filename:', e)
    }
    return fallbackId
  }, [])

  const startDownload = useCallback((urlItem: UrlItem) => {
    // Ensure downloads directory exists
    ensureDownloadsDirExists()
    // Extract filename from URL, fallback to task ID
    const fileName = getFileNameFromUrl(urlItem.url, urlItem.id)
    const destination = getDownloadsDirPath() + '/' + fileName
    const task = createDownloadTask({
      id: urlItem.id,
      url: urlItem.url,
      destination,
      maxRedirects: urlItem.maxRedirects,
      // Store destination in metadata so we can restore it after app restart
      // Also include groupId/groupName for notification grouping (Android)
      metadata: {
        destination,
        // Android 14+: per-download notification title and the name shown in
        // the completion notification
        notificationTitle: `Example: ${fileName}`,
        fileName,
        ...(notificationGroupingEnabled && {
          groupId: 'example-downloads',
          groupName: 'Example Downloads',
        }),
      },
    })
    process(task)
    task.start()
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
    setDestinations(prev => new Map(prev).set(urlItem.id, destination))
  }, [process, ensureDownloadsDirExists, getDownloadsDirPath, getFileNameFromUrl, notificationGroupingEnabled])

  // Batch download - start multiple downloads to test summaryOnly mode
  const startBatchDownload = useCallback(() => {
    ensureDownloadsDirExists()
    const batchUrls = [
      'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
      'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
      'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
      'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3',
      'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3',
    ]

    // Use single timestamp for both groupId and taskIds to avoid race conditions
    const timestamp = Date.now()
    const batchGroupId = `batch-${timestamp}`

    batchUrls.forEach((url, index) => {
      const taskId = `${batchGroupId}-${index}`
      const fileName = `batch-song-${index + 1}.mp3`
      const destination = getDownloadsDirPath() + '/' + fileName

      const task = createDownloadTask({
        id: taskId,
        url,
        destination,
        metadata: {
          destination,
          groupId: batchGroupId,
          groupName: 'Batch Download',
        },
      })
      process(task)
      task.start()
      setDownloadTasks(prev => new Map(prev).set(task.id, task))
      setDestinations(prev => new Map(prev).set(taskId, destination))
    })

    toast(`Started ${batchUrls.length} downloads`)
  }, [process, ensureDownloadsDirExists, getDownloadsDirPath])

  const stopDownload = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task) {
      task.stop()
      updateTask(task)
    }
  }, [updateTask])

  const pauseDownload = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task) {
      task.pause()
      updateTask(task)
    }
  }, [updateTask])

  const resumeDownload = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task) {
      task.resume()
      updateTask(task)
    }
  }, [updateTask])

  // Deletes the row's file from disk and forgets its task, so a predefined
  // URL goes back to "not downloaded" and an extra row leaves the list
  const deleteRowFile = useCallback((row: RowModel) => {
    try {
      if (row.fileName) {
        const file = new File(getDownloadsDir(), row.fileName)
        if (file.exists) {
          file.delete()
          toast('File deleted')
        } else {
          toast('File not found')
        }
      }
      if (row.hasTask)
        forgetTask(row.id)
      readStorage()
    } catch (error) {
      console.warn('deleteFile error:', error)
      toast('Error deleting file')
    }
  }, [getDownloadsDir, forgetTask, readStorage])

  // Stops the task and drops it from the list (the old "remove task" action)
  const removeTask = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task)
      task.stop()
    forgetTask(id)
  }, [forgetTask])

  const showDetails = useCallback((row: RowModel) => {
    const lines = [
      `ID: ${row.id}`,
      `State: ${STATUS_STYLES[row.status].label}`,
      row.url ? `URL: ${row.url}` : null,
      row.path ? `Path: ${row.path}` : null,
      row.status !== 'idle'
        ? `Bytes: ${formatBytes(row.bytesDownloaded)}${row.bytesTotal > 0 ? ` / ${formatBytes(row.bytesTotal)}` : ''}`
        : null,
      row.errorCode ? `Error code: ${row.errorCode}` : null,
    ].filter(Boolean)
    Alert.alert(row.title, lines.join('\n\n'))
  }, [])

  useEffect(() => {
    setConfig({
      isLogsEnabled: true,
      progressMinBytes: 1024 * 100, // 100 KB
      logCallback: (log: string) => {
        console.log('[RNBD]', log)
      },
      showNotificationsEnabled,
      showCancelAction,
      showCompletionNotification,
      notificationsGrouping: {
        enabled: notificationGroupingEnabled,
        mode: summaryOnlyMode ? 'summaryOnly' : 'individual',
        texts: {
          downloadTitle: 'Download',
          downloadStarting: 'Starting...',
          downloadProgress: 'Downloading... {progress}%',
          downloadFinished: 'Complete',
          downloadCancel: 'Cancel',
          groupTitle: 'Batch Download',
          groupText: '{count} files downloading',
        },
      },
    })
  }, [notificationGroupingEnabled, showNotificationsEnabled, summaryOnlyMode, showCancelAction, showCompletionNotification])

  useEffect(() => {
    // Initialize URL list with persisted IDs after mount (when MMKV is ready)
    const initializedUrlList = urlDefinitions.map(def => ({
      id: TaskIdStorage.getOrCreate(def.url),
      ...def,
    }))
    setUrlList(initializedUrlList)

    // Small delay to ensure setConfig has been applied before resuming tasks
    setTimeout(() => {
      resumeExistingTasks()
      readStorage()
    }, 100)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const downloadsPath = getDownloadsDirPath()

  // One list: predefined URLs first (fixed order, never shift), then tasks
  // that don't belong to a URL (batch, restored), then unclaimed files on disk
  const rows = useMemo<RowModel[]>(() => {
    const claimedFiles = new Set<string>()
    const urlIds = new Set(urlList.map(item => item.id))
    const result: RowModel[] = []

    const buildRow = (base: {
      key: string
      id: string
      kind: RowKind
      title: string
      task: DownloadTask | null
      fileName: string | null
      path: string | null
      url: string | null
    }): RowModel => {
      const { task, fileName } = base
      const diskSize = fileName != null ? diskFiles.get(fileName) : undefined
      const hasFile = diskSize !== undefined
      const status: RowStatus = task ? statusFromTask(task) : hasFile ? 'done' : 'idle'

      let bytesDownloaded = task?.bytesDownloaded ?? 0
      let bytesTotal = task?.bytesTotal ?? 0
      if (status === 'done' && hasFile && (!task || bytesDownloaded <= 0)) {
        bytesDownloaded = diskSize
        bytesTotal = diskSize
      }

      const subtitleParts = [base.id]
      if (fileName && fileName !== base.id && fileName !== base.title) subtitleParts.push(fileName)

      return {
        key: base.key,
        id: base.id,
        kind: base.kind,
        title: base.title,
        subtitle: subtitleParts.join(' · '),
        status,
        bytesDownloaded,
        bytesTotal,
        hasTask: !!task,
        hasFile,
        fileName,
        path: base.path,
        url: base.url,
        errorCode: task && status === 'failed' && task.errorCode ? task.errorCode : null,
      }
    }

    urlList.forEach(urlItem => {
      const task = downloadTasks.get(urlItem.id) ?? null
      const destination = destinations.get(urlItem.id)
      const fileName = destination ? baseName(destination) : getFileNameFromUrl(urlItem.url, urlItem.id)
      claimedFiles.add(fileName)
      result.push(buildRow({
        key: urlItem.id,
        id: urlItem.id,
        kind: 'url',
        title: urlItem.title ?? fileName,
        task,
        fileName,
        path: destination ?? `${downloadsPath}/${fileName}`,
        url: urlItem.url,
      }))
    })

    downloadTasks.forEach(task => {
      if (urlIds.has(task.id)) return
      const destination = destinations.get(task.id) ||
        (task as { destination?: string }).destination ||
        (task.metadata as { destination?: string } | undefined)?.destination
      const fileName = destination ? baseName(destination) : null
      if (fileName) claimedFiles.add(fileName)
      result.push(buildRow({
        key: `task:${task.id}`,
        id: task.id,
        kind: 'task',
        title: fileName ?? task.id,
        task,
        fileName,
        path: destination ?? null,
        url: task.downloadParams?.url ?? null,
      }))
    })

    Array.from(diskFiles.keys())
      .filter(name => !claimedFiles.has(name))
      .sort()
      .forEach(name => {
        result.push(buildRow({
          key: `file:${name}`,
          id: name,
          kind: 'file',
          title: name,
          task: null,
          fileName: name,
          path: `${downloadsPath}/${name}`,
          url: null,
        }))
      })

    return result
  }, [urlList, downloadTasks, destinations, diskFiles, downloadsPath, getFileNameFromUrl])

  const rowsRef = useRef<Map<string, RowModel>>(new Map())
  rowsRef.current = useMemo(() => new Map(rows.map(row => [row.key, row])), [rows])
  const urlListRef = useRef(urlList)
  urlListRef.current = urlList

  // Stable across renders: everything it needs is read from refs
  const handleRowAction = useCallback((action: RowAction, key: string) => {
    const row = rowsRef.current.get(key)
    if (!row) return

    switch (action) {
      case 'start': {
        const urlItem = urlListRef.current.find(item => item.id === row.id)
        if (urlItem) startDownload(urlItem)
        break
      }
      case 'pause': pauseDownload(row.id); break
      case 'resume': resumeDownload(row.id); break
      case 'stop': stopDownload(row.id); break
      case 'delete': deleteRowFile(row); break
      case 'remove': removeTask(row.id); break
      case 'details': showDetails(row); break
    }
  }, [startDownload, pauseDownload, resumeDownload, stopDownload, deleteRowFile, removeTask, showDetails])

  const keyExtractor = useCallback((row: RowModel) => row.key, [])

  const renderItem = useCallback(({ item }: ListRenderItemInfo<RowModel>) => (
    <DownloadRow row={item} onAction={handleRowAction} />
  ), [handleRowAction])

  const activeCount = useMemo(() => rows.filter(row => isActiveStatus(row.status)).length, [rows])

  // Pass an element, not a render function: a new function identity on every
  // progress update would be a new component type to React and remount the header
  const headerElement = (
    <Header
      rowCount={rows.length}
      activeCount={activeCount}
      taskCount={downloadTasks.size}
      fileCount={diskFiles.size}
      onBatchDownload={startBatchDownload}
      onClearTasks={reset}
      onDeleteFiles={clearStorage}
      showNotificationsEnabled={showNotificationsEnabled}
      onShowNotificationsEnabledChange={handleShowNotificationsChange}
      notificationGroupingEnabled={notificationGroupingEnabled}
      onNotificationGroupingChange={handleNotificationGroupingChange}
      summaryOnlyMode={summaryOnlyMode}
      onSummaryOnlyModeChange={handleSummaryOnlyModeChange}
      showCancelAction={showCancelAction}
      onShowCancelActionChange={handleShowCancelActionChange}
      showCompletionNotification={showCompletionNotification}
      onShowCompletionNotificationChange={handleShowCompletionNotificationChange}
    />
  )

  return (
    <FlatList
      style={styles.list}
      data={rows}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      ListHeaderComponent={headerElement}
      contentContainerStyle={{
        paddingTop: 12,
        paddingBottom: insets.bottom + 16,
        paddingLeft: insets.left + 16,
        paddingRight: insets.right + 16,
      }}
    />
  )
}

export default BasicExampleScreen

const styles = StyleSheet.create({
  list: {
    flex: 1,
    backgroundColor: colors.background,
  },
  pressed: {
    opacity: 0.6,
  },

  // Header
  header: {
    gap: 12,
    marginBottom: 12,
  },
  card: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    overflow: 'hidden',
  },
  toolbarSummary: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    gap: 12,
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 12,
  },
  toolbarTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: colors.text,
  },
  toolbarStats: {
    flexShrink: 1,
    fontSize: 13,
    color: colors.textSecondary,
    fontVariant: ['tabular-nums'],
  },
  toolbarActions: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 12,
    paddingBottom: 12,
  },
  toolbarButton: {
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
  toolbarButtonFilled: {
    backgroundColor: colors.primary,
  },
  toolbarButtonPlain: {
    backgroundColor: '#F1F5F9',
  },
  toolbarButtonDisabled: {
    backgroundColor: colors.disabledBg,
  },
  toolbarButtonText: {
    fontSize: 14,
    fontWeight: '600',
  },

  // Settings
  settingsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 60,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  settingsTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.text,
  },
  settingsBody: {
    paddingHorizontal: 16,
    paddingBottom: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 56,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.divider,
  },
  settingRowDisabled: {
    opacity: 0.5,
  },
  settingInfo: {
    flex: 1,
    marginRight: 12,
  },
  settingLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: colors.text,
  },
  settingDescription: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 2,
  },
  settingsHint: {
    fontSize: 12,
    color: colors.textSecondary,
    marginTop: 12,
  },

  // Row
  row: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    paddingHorizontal: 12,
    paddingTop: 12,
    paddingBottom: 12,
    marginBottom: 8,
  },
  rowHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 44,
  },
  rowInfo: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    height: 44,
  },
  badge: {
    width: 40,
    height: 40,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeSmall: {
    width: 36,
    height: 36,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowText: {
    flex: 1,
    minWidth: 0,
  },
  rowTitle: {
    fontSize: 15,
    lineHeight: 20,
    fontWeight: '600',
    color: colors.text,
  },
  rowSubtitle: {
    fontSize: 12,
    lineHeight: 16,
    color: colors.textSecondary,
  },
  rowActions: {
    flexDirection: 'row',
    gap: 8,
    marginLeft: 8,
  },
  iconButton: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconBadge: {
    position: 'absolute',
    right: 2,
    bottom: 2,
    width: 18,
    height: 18,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
  },
  progressTrack: {
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.track,
    overflow: 'hidden',
    marginTop: 12,
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
  },
  progressIndeterminate: {
    width: '100%',
    opacity: 0.35,
  },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: 12,
    marginTop: 8,
    height: 16,
  },
  metaState: {
    fontSize: 12,
    lineHeight: 16,
    fontWeight: '600',
  },
  metaBytes: {
    flexShrink: 1,
    fontSize: 12,
    lineHeight: 16,
    color: colors.textSecondary,
    fontVariant: ['tabular-nums'],
    textAlign: 'right',
  },
  metaPercent: {
    color: colors.text,
    fontWeight: '600',
  },
})
