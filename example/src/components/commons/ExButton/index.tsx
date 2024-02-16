import React from 'react';
import {
  TouchableOpacity,
  TouchableOpacityProps,
  Text,
  StyleSheet,
} from 'react-native';

const ExButton = ({title, style, ...props}: TouchableOpacityProps) => {
  return (
    <TouchableOpacity
      style={[styles.wrapper, style]}
      activeOpacity={0.5}
      {...props}>
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
    fontSize: 18,
  },
});

export default ExButton;
