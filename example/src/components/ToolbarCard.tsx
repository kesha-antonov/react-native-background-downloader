import React from 'react'
import { Text, View } from 'react-native'
import Card from './Card'
import { createThemedStyles } from '../theme'

interface ToolbarCardProps {
  title: string
  // One line of counts, e.g. "7 items · 1 active · 2 on disk"
  stats: string
  // Optional second line under the title, e.g. where uploads go
  caption?: string
  // ToolbarButtons, laid out in one row of equal widths
  children: React.ReactNode
}

const ToolbarCard = ({ title, stats, caption, children }: ToolbarCardProps) => {
  const styles = useStyles()
  return (
    <Card>
      <View style={styles.summary}>
        <View style={styles.titleRow}>
          <Text style={styles.title}>{title}</Text>
          <Text style={styles.stats} numberOfLines={1}>{stats}</Text>
        </View>
        {caption ? <Text style={styles.caption} numberOfLines={1}>{caption}</Text> : null}
      </View>
      <View style={styles.actions}>{children}</View>
    </Card>
  )
}

export default ToolbarCard

const useStyles = createThemedStyles(theme => ({
  summary: {
    paddingHorizontal: 16,
    paddingTop: 16,
    paddingBottom: 12,
    gap: 2,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    gap: 12,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: theme.colors.text,
  },
  stats: {
    flexShrink: 1,
    fontSize: 13,
    color: theme.colors.textSecondary,
    fontVariant: ['tabular-nums'],
  },
  caption: {
    fontSize: 12,
    lineHeight: 16,
    color: theme.colors.textSecondary,
  },
  actions: {
    flexDirection: 'row',
    gap: 8,
    paddingHorizontal: 12,
    paddingBottom: 12,
  },
}))
