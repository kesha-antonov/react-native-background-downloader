import {
  TouchableOpacity,
  type TouchableOpacityProps,
  Text,
  StyleSheet,
} from 'react-native';

type ExButtonProps = TouchableOpacityProps & {
  title: string;
};

const ExButton = ({ title, style, ...props }: ExButtonProps) => {
  return (
    <TouchableOpacity
      style={[styles.wrapper, style]}
      activeOpacity={0.5}
      {...props}
    >
      <Text style={styles.title}>{title}</Text>
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'white',
    borderWidth: 1,
    borderRadius: 16,
    borderColor: 'black',
    marginTop: 8,
    padding: 12,
  },
  title: {
    fontSize: 16,
  },
});

export default ExButton;
