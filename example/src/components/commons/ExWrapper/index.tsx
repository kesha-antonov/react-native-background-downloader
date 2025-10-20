import { StyleSheet } from 'react-native';
import {
  type SafeAreaViewProps,
  SafeAreaView,
} from 'react-native-safe-area-context';

const ExWrapper = ({
  edges = ['bottom'],
  style,
  children,
  ...props
}: SafeAreaViewProps) => {
  return (
    <SafeAreaView style={[styles.wrapper, style]} edges={edges} {...props}>
      {children}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    flex: 1,
    padding: 8,
  },
});

export default ExWrapper;
