import React from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import StateBadge from './StateBadge'
import IconButton, { type ActionSpec } from './IconButton'
import ProgressBar from './ProgressBar'
import { createThemedStyles, statusColors, useTheme, type TransferStatus } from '../theme'
import type { IoniconName } from './ToolbarButton'
import { formatBytes } from '../utils'

interface TransferRowProps<A extends string> {
  // Used in accessibility labels: "<Action> <id>", "Details <id>"
  id: string
  title: string
  subtitle: string
  status: TransferStatus
  statusIcon: IoniconName
  // Left of the status line, in the status color
  stateLabel: string
  // Right of the status line
  bytesText: string
  percentText: string
  progress: number
  indeterminate: boolean
  // Two slots that never appear or disappear: their content swaps in place
  actions: [ActionSpec<A>, ActionSpec<A>]
  onAction: (action: A) => void
  onDetails: () => void
  detailsHint?: string
}

// One transfer: state badge, title/subtitle, two round action buttons, a thin
// progress bar and a status line. The same height in every state: the progress area
// is always rendered and the buttons swap content in place.
// Not memoized itself: the screens wrap it in a row component memoized on their row model
const TransferRow = <A extends string>({
  id,
  title,
  subtitle,
  status,
  statusIcon,
  stateLabel,
  bytesText,
  percentText,
  progress,
  indeterminate,
  actions: [primary, secondary],
  onAction,
  onDetails,
  detailsHint,
}: TransferRowProps<A>) => {
  const theme = useTheme()
  const styles = useStyles()
  const colors = statusColors(theme, status)

  return (
    <View style={styles.row}>
      <View style={styles.header}>
        <Pressable
          style={styles.info}
          onPress={onDetails}
          accessibilityRole='button'
          accessibilityLabel={`Details ${id}`}
          accessibilityHint={detailsHint}
        >
          <StateBadge status={status} icon={statusIcon} />
          <View style={styles.text}>
            <Text style={styles.title} numberOfLines={1}>{title}</Text>
            <Text style={styles.subtitle} numberOfLines={1}>{subtitle}</Text>
          </View>
        </Pressable>
        <View style={styles.actions}>
          <IconButton spec={primary} rowId={id} onPress={() => onAction(primary.action)} />
          <IconButton spec={secondary} rowId={id} onPress={() => onAction(secondary.action)} />
        </View>
      </View>

      <ProgressBar
        progress={progress}
        indeterminate={indeterminate}
        active={status === 'downloading' || status === 'uploading'}
        color={status === 'idle' ? theme.colors.track : colors.strong}
      />
      <View style={styles.meta}>
        <Text style={[styles.metaState, { color: colors.strong }]} numberOfLines={1}>
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
}

export default TransferRow

const useStyles = createThemedStyles(theme => ({
  row: {
    backgroundColor: theme.colors.card,
    borderRadius: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.colors.border,
    paddingHorizontal: 12,
    paddingTop: 12,
    paddingBottom: 12,
    marginBottom: 8,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 44,
  },
  info: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    height: 44,
  },
  text: {
    flex: 1,
    minWidth: 0,
  },
  title: {
    fontSize: 15,
    lineHeight: 20,
    fontWeight: '600',
    color: theme.colors.text,
  },
  subtitle: {
    fontSize: 12,
    lineHeight: 16,
    color: theme.colors.textSecondary,
  },
  actions: {
    flexDirection: 'row',
    gap: 8,
    marginLeft: 8,
  },
  meta: {
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
    color: theme.colors.textSecondary,
    fontVariant: ['tabular-nums'],
    textAlign: 'right',
  },
  metaPercent: {
    color: theme.colors.text,
    fontWeight: '600',
  },
}))

// What a row's bar and status line show for a transfer's bytes. Shared, so download
// and upload rows read the same
export const describeProgress = (status: TransferStatus, transferred: number, total: number) => {
  const isTotalUnknown = total <= 0
  const showsBytes = status !== 'idle' && status !== 'pending'
  const progress = status === 'done'
    ? 1
    : isTotalUnknown ? 0 : Math.min(1, transferred / total)
  const indeterminate = (status === 'downloading' || status === 'uploading') && isTotalUnknown

  let bytesText = ''
  if (showsBytes)
    bytesText = status === 'done' || isTotalUnknown
      ? formatBytes(transferred)
      : `${formatBytes(transferred)} / ${formatBytes(total)}`

  const percentText = showsBytes && (status === 'done' || !isTotalUnknown)
    ? `${Math.round(progress * 100)}%`
    : ''

  return { progress, indeterminate, bytesText, percentText }
}
