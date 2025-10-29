import React from 'react';
import { StyleSheet, View } from 'react-native';
import { ExButton } from '../../components/commons';
import {
  clearStorage,
  downloadItem,
  downloadList,
  readStorage,
  useDownloadsStateStore,
} from '../../utils';

export const BulkControls = () => {
  const tasks = useDownloadsStateStore(state => state.tasks);
  const isStarted = Object.values(tasks).reduce((acc, val) => {
    if (acc === true) return true;

    if (val.state !== 'DONE') return true;

    return acc;
  }, false);

  return (
    <View style={styles.headerWrapper}>
      {isStarted ? (
        <ExButton
          title={'Stop All'}
          onPress={() => {
            Object.values(tasks).forEach(task => task.stop());
          }}
        />
      ) : (
        <ExButton
          title={'Download All'}
          onPress={() => {
            downloadList.forEach(item => {
              downloadItem(item);
            });
          }}
        />
      )}

      <ExButton
        title={'Reset'}
        onPress={() => {
          Object.values(tasks).forEach(task => task.stop());
          useDownloadsStateStore.getState().reset();
        }}
      />
      <ExButton title={'Delete files'} onPress={() => clearStorage()} />
      <ExButton title={'List files'} onPress={() => readStorage()} />
    </View>
  );
};

const styles = StyleSheet.create({
  headerWrapper: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    padding: 6,
  },
});
