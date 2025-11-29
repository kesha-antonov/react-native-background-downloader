import React from 'react'
import {
  Text,
  StyleSheet,
} from 'react-native'
import {
  RectButton,
} from 'react-native-gesture-handler'

interface ExButtonProps extends React.ComponentProps<typeof RectButton> {
  title?: string
}

const ExButton = ({ title, style, ...props }: ExButtonProps) => {
  return (
    <RectButton
      style={[styles.wrapper, style]}
      activeOpacity={0.5}
      {...props}>
      <Text style={styles.title}>{title}</Text>
    </RectButton>
  )
}

const styles = StyleSheet.create({
  wrapper: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'skyblue',
    borderRadius: 16,
    marginTop: 8,
    paddingVertical: 6,
    paddingHorizontal: 12,
  },
  title: {
    fontSize: 18,
  },
})

export default ExButton
