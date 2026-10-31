import React from 'react'
import { Platform, Pressable, ScrollView, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import type { ParamListBase } from '@react-navigation/native'
import type { StackScreenProps } from '@react-navigation/stack'
import { Card, type IoniconName } from '../../components'
import { createThemedStyles, useTheme, type StatusColors } from '../../theme'

interface ExampleCardProps {
  title: string
  description: string
  icon: IoniconName
  colors: StatusColors
  onPress: () => void
}

// A large tappable card that opens one example
const ExampleCard = ({ title, description, icon, colors, onPress }: ExampleCardProps) => {
  const theme = useTheme()
  const styles = useStyles()
  return (
    <Card>
      <Pressable
        onPress={onPress}
        accessibilityRole='button'
        accessibilityLabel={title}
        accessibilityHint={description}
        android_ripple={{ color: theme.colors.ripple }}
        style={({ pressed }) => [styles.exampleCard, pressed && Platform.OS === 'ios' && styles.pressed]}
      >
        <View style={[styles.exampleIcon, { backgroundColor: colors.soft }]}>
          <Ionicons name={icon} size={24} color={colors.strong} />
        </View>
        <View style={styles.exampleText}>
          <Text style={styles.exampleTitle} numberOfLines={1}>{title}</Text>
          <Text style={styles.exampleDescription} numberOfLines={1}>{description}</Text>
        </View>
        <Ionicons name='chevron-forward' size={20} color={theme.colors.textMuted} />
      </Pressable>
    </Card>
  )
}

const WelcomeScreen = ({ navigation }: StackScreenProps<ParamListBase>) => {
  const theme = useTheme()
  const styles = useStyles()
  const insets = useSafeAreaInsets()

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, {
        paddingBottom: insets.bottom + 16,
        paddingLeft: insets.left + 16,
        paddingRight: insets.right + 16,
      }]}
    >
      <View style={styles.hero}>
        <View style={styles.heroIcon}>
          <Ionicons name='swap-vertical' size={28} color={theme.colors.primaryText} />
        </View>
        <Text style={styles.title} accessibilityRole='header'>Background Downloader</Text>
        <Text style={styles.packageName}>@kesha-antonov/react-native-background-downloader</Text>
        <Text style={styles.subtitle}>
          Downloads and uploads that keep going while the app is in the background or closed.
        </Text>
      </View>

      <Text style={styles.sectionLabel}>Examples</Text>
      <View style={styles.cards}>
        <ExampleCard
          title='Download Example'
          description='Start, pause, resume and restore downloads'
          icon='cloud-download-outline'
          colors={theme.status.downloading}
          onPress={() => navigation.navigate('root.basic_example')}
        />
        <ExampleCard
          title='Upload Example'
          description='Upload generated files to a test server'
          icon='cloud-upload-outline'
          colors={theme.status.done}
          onPress={() => navigation.navigate('root.upload_example')}
        />
      </View>
    </ScrollView>
  )
}

export default WelcomeScreen

const useStyles = createThemedStyles(theme => ({
  screen: {
    flex: 1,
    backgroundColor: theme.colors.background,
  },
  content: {
    paddingTop: 24,
  },
  hero: {
    alignItems: 'center',
    paddingHorizontal: 8,
    marginBottom: 28,
  },
  heroIcon: {
    width: 56,
    height: 56,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.primarySoft,
    marginBottom: 14,
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: theme.colors.text,
    textAlign: 'center',
  },
  packageName: {
    fontSize: 12,
    color: theme.colors.textSecondary,
    textAlign: 'center',
    marginTop: 4,
    fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }),
  },
  subtitle: {
    fontSize: 15,
    lineHeight: 21,
    color: theme.colors.textSecondary,
    textAlign: 'center',
    marginTop: 12,
    maxWidth: 340,
  },
  sectionLabel: {
    fontSize: 13,
    fontWeight: '600',
    color: theme.colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: 8,
    marginLeft: 4,
  },
  cards: {
    gap: 12,
  },
  exampleCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    minHeight: 76,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  pressed: {
    opacity: 0.6,
  },
  exampleIcon: {
    width: 48,
    height: 48,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  exampleText: {
    flex: 1,
    minWidth: 0,
    gap: 2,
  },
  exampleTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: theme.colors.text,
  },
  exampleDescription: {
    fontSize: 13,
    color: theme.colors.textSecondary,
  },
}))
