import React, { useEffect } from 'react';
import { StyleSheet, Text, FlatList, View } from 'react-native';
import {
  checkForExistingDownloads,
  setConfig,
} from '@kesha-antonov/react-native-background-downloader';
import { ExWrapper } from '../../components/commons';
import { downloadList } from '../../utils';
import { BulkControls } from './BulkControls';
import { DownloadCard } from './DownloadCard';
import { useDownloadsStateStore } from '../../utils';

setConfig({
  headers: {},
  progressInterval: 1000,
  isLogsEnabled: true,
});

const Header = () => (
  <View style={styles.header}>
    <Text style={styles.title}>Basic Example</Text>
    <BulkControls />
  </View>
);

/**
 * It is used to resume your incomplete or unfinished downloads.
 */
const resumeExistingTasks = async () => {
  try {
    const tasks = await checkForExistingDownloads();
    for (const task of tasks) {
      useDownloadsStateStore.getState().addTask(task);
    }
  } catch (e) {
    console.warn('checkForExistingDownloads e', e);
  }
};

const BasicExampleScreen = () => {
  useEffect(() => {
    resumeExistingTasks();
  }, []);

  return (
    <ExWrapper>
      <FlatList
        data={downloadList}
        keyExtractor={(_, index) => `url-${index}`}
        renderItem={({ item }) => <DownloadCard item={item} />}
        ListHeaderComponent={Header}
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
    marginTop: 16,
  },
  header: {
    borderBottomColor: 'gray',
    borderBottomWidth: 1,
    marginBottom: 15,
    paddingBottom: 15,
  },
});

export default BasicExampleScreen;
