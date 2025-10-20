import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import { RootSiblingParent } from 'react-native-root-siblings';
import Router from './Router';

const style = { flex: 1 };

const App = () => {
  return (
    <GestureHandlerRootView style={style}>
      <SafeAreaProvider>
        <RootSiblingParent>
          <NavigationContainer>
            <Router />
          </NavigationContainer>
        </RootSiblingParent>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
};

export default App;
