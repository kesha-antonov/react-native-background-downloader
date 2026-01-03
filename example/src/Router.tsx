import React from 'react'
import { createStackNavigator } from '@react-navigation/stack'
import WelcomeScreen from './screens/Welcome'
import BasicExampleScreen from './screens/BasicExample'
import UploadExampleScreen from './screens/UploadExample'

const RootStack = createStackNavigator()

const Router = () => {
  return (
    <RootStack.Navigator>
      <RootStack.Screen
        name={'root.welcome'}
        options={{
          headerTitle: 'Welcome',
          headerTitleAlign: 'center',
          headerBackButtonDisplayMode: 'minimal',
        }}
        component={WelcomeScreen}
      />

      <RootStack.Screen
        name={'root.basic_example'}
        options={{
          headerTitle: 'Download Example',
          headerTitleAlign: 'center',
          headerBackButtonDisplayMode: 'minimal',
        }}
        component={BasicExampleScreen}
      />

      <RootStack.Screen
        name={'root.upload_example'}
        options={{
          headerTitle: 'Upload Example',
          headerTitleAlign: 'center',
          headerBackButtonDisplayMode: 'minimal',
        }}
        component={UploadExampleScreen}
      />
    </RootStack.Navigator>
  )
}

export default Router
