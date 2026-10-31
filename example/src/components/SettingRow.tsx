import React from 'react'
import { StyleSheet, Switch, Text, View } from 'react-native'
import { createThemedStyles, useTheme } from '../theme'

interface SettingRowProps {
  label: string
  description: string
  value: boolean
  onValueChange: (value: boolean) => void
  disabled?: boolean
}

// A labelled switch with a one-line description
const SettingRow = ({ label, description, value, onValueChange, disabled }: SettingRowProps) => {
  const theme = useTheme()
  const styles = useStyles()
  return (
    <View style={[styles.row, disabled && styles.disabled]}>
      <View style={styles.info}>
        <Text style={styles.label}>{label}</Text>
        <Text style={styles.description}>{description}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onValueChange}
        disabled={disabled}
        accessibilityLabel={label}
        trackColor={{ false: theme.colors.switchTrackOff, true: theme.colors.switchTrackOn }}
        thumbColor={value ? theme.colors.switchThumbOn : theme.colors.switchThumbOff}
        ios_backgroundColor={theme.colors.switchTrackOff}
      />
    </View>
  )
}

export default SettingRow

const useStyles = createThemedStyles(theme => ({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 56,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: theme.colors.divider,
  },
  disabled: {
    opacity: 0.5,
  },
  info: {
    flex: 1,
    marginRight: 12,
  },
  label: {
    fontSize: 14,
    fontWeight: '500',
    color: theme.colors.text,
  },
  description: {
    fontSize: 12,
    color: theme.colors.textSecondary,
    marginTop: 2,
  },
}))
