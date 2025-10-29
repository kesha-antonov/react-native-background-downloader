import React from 'react';
import { StyleSheet, View, Text } from 'react-native';
import Slider from '@react-native-community/slider';

import { ExButton } from '../../components/commons';
import { deleteItem, downloadItem, DownloadItem } from '../../utils';
import { useDownloadForItem } from '../../hooks';

export const DownloadCard = ({ item }: { item: DownloadItem }) => {
  const { task, isDownloaded, progress, status, isEnded } =
    useDownloadForItem(item);

  const isDownloading = status === 'DOWNLOADING';

  return (
    <View style={styles.item}>
      <Text>Id: {item.id}</Text>
      <Text>Url: {item.url}</Text>
      {item.maxRedirects && (
        <Text style={styles.redirectInfo}>
          Max redirects: {item.maxRedirects}
          {item.title && ` (${item.title})`}
        </Text>
      )}
      {task && <Text>Download Status: {status}</Text>}
      {task && !isEnded ? (
        <View style={styles.progress}>
          <Slider
            disabled={true}
            value={progress.progress || 0}
            minimumValue={0}
            maximumValue={progress.total || 0}
          />
        </View>
      ) : null}
      <View style={styles.buttons}>
        {task && !isEnded && isDownloading ? (
          <ExButton
            title="Pause"
            style={styles.button}
            onPress={() => {
              task.pause();
            }}
          />
        ) : null}
        {task && !isEnded && !isDownloading ? (
          <ExButton
            title="Resume"
            style={styles.button}
            onPress={() => {
              task.resume();
            }}
          />
        ) : null}
        {task && !isEnded ? (
          <ExButton
            title="Cancel"
            style={styles.button}
            onPress={() => {
              task.stop();
            }}
          />
        ) : null}
        {isDownloaded ? (
          <ExButton
            title="Delete"
            style={[styles.button, styles.buttonDelete]}
            onPress={async () => deleteItem(item)}
          />
        ) : null}
        {!isDownloaded && !task ? (
          <ExButton
            title="Download"
            style={[styles.button, styles.buttonDownload]}
            onPress={async () => downloadItem(item)}
          />
        ) : null}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  item: {
    flexDirection: 'column',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    borderBottomColor: 'gray',
    borderBottomWidth: 1,
    paddingVertical: 10,
  },
  progress: {
    flex: 1,
    flexGrow: 1,
    width: '100%',
  },
  buttons: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    flex: 1,
    flexGrow: 1,
    width: '100%',
  },
  button: {
    flexGrow: 1,
  },
  buttonDownload: {
    backgroundColor: '#65a30d',
  },
  buttonDelete: {
    backgroundColor: '#ef4444',
  },
  redirectInfo: {
    fontStyle: 'italic',
    color: '#666',
    fontSize: 12,
    marginTop: 4,
  },
});
