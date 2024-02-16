import React from 'react';
import {createStackNavigator} from '@react-navigation/stack';
import WelcomeScreen from './screens/Welcome';
import BasicExampleScreen from './screens/BasicExample';

const RootStack = createStackNavigator();

const Router = () => {
  return (
    <RootStack.Navigator>
      <RootStack.Screen
        name={'root.welcome'}
        options={{
          headerTitle: 'Welcome',
          headerTitleAlign: 'center',
          headerLeftLabelVisible: false,
        }}
        component={WelcomeScreen}
      />

      <RootStack.Screen
        name={'root.basic_example'}
        options={{
          headerTitle: 'Basic Example',
          headerTitleAlign: 'center',
          headerLeftLabelVisible: false,
        }}
        component={BasicExampleScreen}
      />
    </RootStack.Navigator>
  );
};

export default Router;
