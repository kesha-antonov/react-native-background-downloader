import { Text, StyleSheet } from 'react-native';
import { ExButton, ExWrapper } from '../../components/commons';

import type { StackNavigationProp } from '@react-navigation/stack';

type WelcomeScreenProps = {
  navigation: StackNavigationProp<any, any>;
};

const WelcomeScreen = ({ navigation }: WelcomeScreenProps) => {
  return (
    <ExWrapper>
      <Text style={styles.title}>React Native Background Downloader</Text>

      <ExButton
        title={'Basic Example'}
        onPress={() => navigation.navigate('root.basic_example')}
      />
    </ExWrapper>
  );
};

const styles = StyleSheet.create({
  title: {
    fontSize: 24,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 12,
  },
  description: {
    fontSize: 18,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 6,
  },
});

export default WelcomeScreen;
