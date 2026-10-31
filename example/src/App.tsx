import React, { useEffect } from 'react'
import { GestureHandlerRootView } from 'react-native-gesture-handler'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { NavigationContainer } from '@react-navigation/native'
import { RootSiblingParent } from 'react-native-root-siblings'
import { StatusBar } from 'expo-status-bar'
import * as SystemUI from 'expo-system-ui'
import Router from './Router'
import { getNavigationTheme, useTheme } from './theme'

const App = () => {
  const theme = useTheme()

  // The root view's color shows behind screens during transitions and keyboard
  // animations, so it follows the theme too
  useEffect(() => {
    SystemUI.setBackgroundColorAsync(theme.colors.background).catch(() => {})
  }, [theme])

  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: theme.colors.background }}>
      <SafeAreaProvider>
        <RootSiblingParent>
          <NavigationContainer theme={getNavigationTheme(theme)}>
            <Router />
          </NavigationContainer>
          <StatusBar style='auto' />
        </RootSiblingParent>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  )
}

export default App
