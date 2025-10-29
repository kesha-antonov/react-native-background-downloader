import { download } from '@kesha-antonov/react-native-background-downloader';

import { uuid } from './uuid';
import { DEFAULT_DIR, deleteItemById } from './storage';
import { useDownloadsStateStore } from './useDownloadsStateStore';

export interface DownloadItem {
  url: string;
  id: string;
  maxRedirects?: number;
  title?: string;
}

export const downloadList: DownloadItem[] = [
  {
    url: 'https://sabnzbd.org/tests/internetspeed/20MB.bin',
  },
  {
    url: 'https://sabnzbd.org/tests/internetspeed/50MB.bin',
  },
  {
    url: 'https://proof.ovh.net/files/100Mb.dat',
  },
  {
    url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/CRMDA/claritaspod.com/measure/pscrb.fm/rss/p/stitcher.simplecastaudio.com/9aa1e238-cbed-4305-9808-c9228fc6dd4f/episodes/b0c9a72a-1cb7-4ac9-80a0-36996fc6470f/audio/128/default.mp3?aid=rss_feed&awCollectionId=9aa1e238-cbed-4305-9808-c9228fc6dd4f&awEpisodeId=b0c9a72a-1cb7-4ac9-80a0-36996fc6470f&feed=dxZsm5kX',
    // This is an example of a URL with many redirects that would cause ERROR_TOO_MANY_REDIRECTS
    // We use maxRedirects to handle this case
    maxRedirects: 10,
    title: 'Podcast with redirects',
  },
].map(item => ({
  id: uuid(item.url),
  ...item,
}));

export function downloadItem(item: DownloadItem) {
  const task = download({
    id: item.id,
    url: item.url,
    destination: DEFAULT_DIR + '/' + item.id,
    maxRedirects: item.maxRedirects,
  });

  useDownloadsStateStore.getState().addTask(task);

  return task;
}

export async function deleteItem(item: DownloadItem) {
  await deleteItemById(item.id);
  useDownloadsStateStore.getState().updateDownloadStatus(item.id, false);
}
