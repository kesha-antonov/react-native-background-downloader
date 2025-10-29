import { directories } from '@kesha-antonov/react-native-background-downloader';
import RNFS from 'react-native-fs';

import { toast } from './toast';
import { useDownloadsStateStore } from './useDownloadsStateStore';

export const DEFAULT_DIR = directories.documents;

export const readStorage = async () => {
  const files = await RNFS.readdir(DEFAULT_DIR);
  for (const file of files) {
    useDownloadsStateStore.getState().updateDownloadStatus(file, true);
  }
  toast('Check logs');
  console.log(`Downloaded files: ${files}`);
};

export const clearStorage = async () => {
  const files = await RNFS.readdir(DEFAULT_DIR);

  if (files.length > 0)
    await Promise.all(files.map(file => RNFS.unlink(DEFAULT_DIR + '/' + file)));

  useDownloadsStateStore.getState().reset();
  toast('Check logs');
  console.log(`Deleted file count: ${files.length}`);
};

export const doesItemExist = async (id: string) => {
  try {
    const result = await RNFS.stat(DEFAULT_DIR + '/' + id);
    useDownloadsStateStore.getState().updateDownloadStatus(id, true);
    return result;
  } catch (err) {
    return false;
  }
};

export const deleteItemById = async (id: string) => {
  await RNFS.unlink(DEFAULT_DIR + '/' + id);
};
