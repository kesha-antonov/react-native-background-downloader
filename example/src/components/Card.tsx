import React from 'react'
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native'
import { createThemedStyles } from '../theme'

interface CardProps {
  style?: StyleProp<ViewStyle>
  children?: React.ReactNode
}

// A white (dark: raised slate) rounded surface
const Card = ({ style, children }: CardProps) => {
  const styles = useStyles()
  return <View style={[styles.card, style]}>{children}</View>
}

export default Card

const useStyles = createThemedStyles(theme => ({
  card: {
    backgroundColor: theme.colors.card,
    borderRadius: 16,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: theme.colors.border,
    overflow: 'hidden',
  },
}))
