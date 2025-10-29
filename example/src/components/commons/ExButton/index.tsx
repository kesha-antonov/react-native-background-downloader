import React from 'react';
import {
  TouchableOpacity,
  TouchableOpacityProps,
  Text,
  StyleSheet,
} from 'react-native';

interface ExButtonProps extends TouchableOpacityProps {
  title: string;
}

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
    backgroundColor: 'skyblue',
    borderRadius: 16,
    marginTop: 8,
    paddingVertical: 6,
    paddingHorizontal: 12,
  },
  title: {
    fontSize: 15,
  },
});

export default ExButton;
