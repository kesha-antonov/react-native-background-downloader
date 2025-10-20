import { useEffect, useState } from 'react';
import {
  StyleSheet,
  View,
  Text,
  FlatList,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import RNFS from 'react-native-fs';
import {
  completeHandler,
  directories,
  checkForExistingDownloads,
  download,
  setConfig,
  // @ts-expect-error: "@kesha-antonov/react-native-background-downloader" has no exported member named 'DownloadTask'
  DownloadTask,
} from '@kesha-antonov/react-native-background-downloader';
import Slider from '@react-native-community/slider';
import { ExButton, ExWrapper } from '../../components/commons';
import { toast, uuid } from '../../utils';

const defaultDir = directories.documents;

setConfig({
  isLogsEnabled: true,
});

type FooterProps = StyleProp<ViewStyle> & {
  onStart: () => void;
  onStop: () => void;
  onReset: () => void;
  onClear: () => void;
  onRead: () => void;
  isStarted: boolean;
};

const Footer = ({
  onStart,
  onStop,
  onReset,
  onClear,
  onRead,
  isStarted,
  ...props
}: FooterProps) => {
  return (
    <View style={styles.headerWrapper} {...props}>
      {isStarted ? (
        <ExButton title={'Stop'} onPress={onStop} />
      ) : (
        <ExButton title={'Start'} onPress={onStart} />
      )}

      <ExButton title={'Reset'} onPress={onReset} />
      <ExButton title={'Delete files'} onPress={onClear} />
      <ExButton title={'List files'} onPress={onRead} />
    </View>
  );
};

const filesToDownload = [
  {
    id: uuid(),
    url: 'https://sabnzbd.org/tests/internetspeed/20MB.bin',
  },
  {
    id: uuid(),
    url: 'https://sabnzbd.org/tests/internetspeed/50MB.bin',
  },
  {
    id: uuid(),
    url: 'https://proof.ovh.net/files/100Mb.dat',
  },
  {
    id: uuid(),
    url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/CRMDA/claritaspod.com/measure/pscrb.fm/rss/p/stitcher.simplecastaudio.com/9aa1e238-cbed-4305-9808-c9228fc6dd4f/episodes/b0c9a72a-1cb7-4ac9-80a0-36996fc6470f/audio/128/default.mp3?aid=rss_feed&awCollectionId=9aa1e238-cbed-4305-9808-c9228fc6dd4f&awEpisodeId=b0c9a72a-1cb7-4ac9-80a0-36996fc6470f&feed=dxZsm5kX',
    // This is an example of a URL with many redirects that would cause ERROR_TOO_MANY_REDIRECTS
    // We use maxRedirects to handle this case
    maxRedirects: 10,
    title: 'Podcast with redirects',
  },
];

const BasicExampleScreen = () => {
  const [isStarted, setIsStarted] = useState(false);
  const [downloadTasks, setDownloadTasks] = useState<DownloadTask[]>([]);

  /**
   * It is used to resume your incomplete or unfinished downloads.
   */
  const resumeExistingTasks = async () => {
    try {
      const tasks = await checkForExistingDownloads();

      if (tasks.length > 0) {
        tasks.map((task) => process(task));
        setDownloadTasks((currentTasks) => [...currentTasks, ...tasks]);
        setIsStarted(true);
      }
    } catch (e) {
      console.warn('checkForExistingDownloads e', e);
    }
  };

  const readStorage = async () => {
    const files = await RNFS.readdir(defaultDir);
    toast('Check logs');
    console.log(`Downloaded files: ${files}`);
  };

  const clearStorage = async () => {
    const files = await RNFS.readdir(defaultDir);

    if (files.length > 0)
      await Promise.all(
        files.map((file) => RNFS.unlink(defaultDir + '/' + file))
      );

    toast('Check logs');
    console.log(`Deleted file count: ${files.length}`);
  };

  const process = (task: DownloadTask) => {
    const { index } = getTask(task.id);
    console.log('will download task with id:', task.id, task);

    return task
      .begin(
        ({
          expectedBytes,
          headers,
        }: {
          expectedBytes: number;
          headers: Record<string, string>;
        }) => {
          console.log('task: begin', { id: task.id, expectedBytes, headers });
          setDownloadTasks((currentTasks) => {
            currentTasks[index] = task;
            return [...currentTasks];
          });
        }
      )
      .progress(
        ({
          bytesDownloaded,
          bytesTotal,
        }: {
          bytesDownloaded: number;
          bytesTotal: number;
        }) => {
          console.log('task: progress', {
            id: task.id,
            bytesDownloaded,
            bytesTotal,
          });
          setDownloadTasks((currentTasks) => {
            currentTasks[index] = task;
            return [...currentTasks];
          });
        }
      )
      .done(() => {
        console.log('task: done', { id: task.id });
        setDownloadTasks((currentTasks) => {
          currentTasks[index] = task;
          return [...currentTasks];
        });

        completeHandler(task.id);
      })
      .error((e: any) => {
        console.error('task: error', { id: task.id, e });
        setDownloadTasks((currentTasks) => {
          currentTasks[index] = task;
          return [...currentTasks];
        });

        completeHandler(task.id);
      });
  };

  const reset = () => {
    stop();
    setDownloadTasks([]);
    setIsStarted(false);
  };

  const start = () => {
    /**
     * You need to provide the extension of the file in the destination section below.
     * If you cannot provide this, you may experience problems while using your file.
     * For example; Path + File Name + .png
     */
    const taskAttributes = filesToDownload.map((item) => {
      const destination = defaultDir + '/' + item.id;
      const taskAttribute = {
        id: item.id,
        url: item.url,
        destination,
        maxRedirects: 0,
      };

      // Add maxRedirects if specified for this URL
      if (item.maxRedirects) {
        taskAttribute.maxRedirects = item.maxRedirects;
        console.log(
          `Setting maxRedirects=${item.maxRedirects} for URL: ${item.url}`
        );
      }

      return taskAttribute;
    });

    const tasks = taskAttributes.map((taskAttribute) =>
      process(download(taskAttribute))
    );

    setDownloadTasks((currentTasks) => [...currentTasks, ...tasks]);
    setIsStarted(true);
  };

  const stop = () => {
    const tasks = downloadTasks.map((task: DownloadTask) => {
      task.stop();
      return task;
    });

    setDownloadTasks(tasks);
    setIsStarted(false);
  };

  const pause = (id: string) => {
    const { index, task } = getTask(id);

    task.pause();
    setDownloadTasks((currentTasks) => {
      currentTasks[index] = task;
      return [...currentTasks];
    });
  };

  const resume = (id: string) => {
    const { index, task } = getTask(id);

    task.resume();
    setDownloadTasks((currentTasks) => {
      currentTasks[index] = task;
      return [...currentTasks];
    });
  };

  const cancel = (id: string) => {
    const { index, task } = getTask(id);

    task.stop();
    setDownloadTasks((currentTasks) => {
      currentTasks[index] = task;
      return [...currentTasks];
    });
  };

  const getTask = (id: string) => {
    const index = downloadTasks.findIndex((task) => task.id === id);
    const task = downloadTasks[index];
    return { index, task };
  };

  useEffect(() => {
    resumeExistingTasks();
    /* eslint-disable-next-line react-hooks/exhaustive-deps */
  }, []);

  return (
    <ExWrapper>
      <View>
        <FlatList
          data={filesToDownload}
          keyExtractor={(item) => `url-${item.id}`}
          renderItem={({ item }) => (
            <View style={styles.item}>
              <View style={styles.itemContent}>
                <Text>Id: {item.id}</Text>
                <Text numberOfLines={2}>Url: {item.url}</Text>
                {item.maxRedirects && (
                  <Text style={styles.redirectInfo}>
                    Max redirects: {item.maxRedirects}
                    {item.title && ` (${item.title})`}
                  </Text>
                )}
              </View>
            </View>
          )}
          ListFooterComponent={
            <Footer
              isStarted={isStarted}
              onStart={start}
              onStop={stop}
              onReset={reset}
              onClear={clearStorage}
              onRead={readStorage}
            />
          }
        />
      </View>
      <FlatList
        style={styles.flatList}
        data={downloadTasks}
        renderItem={({ item }) => {
          const isEnded = ['STOPPED', 'DONE', 'FAILED'].includes(item.state);
          const isDownloading = item.state === 'DOWNLOADING';

          return (
            <View style={styles.item}>
              <View style={styles.itemContent}>
                <Text>{item?.id}</Text>
                <Text>{item?.state}</Text>
                <Slider
                  disabled={true}
                  value={item?.bytesDownloaded}
                  minimumValue={0}
                  maximumValue={item?.bytesTotal}
                  minimumTrackTintColor="blue"
                  maximumTrackTintColor="gray"
                  thumbTintColor="red"
                />
              </View>
              <View>
                {!isEnded &&
                  (isDownloading ? (
                    <ExButton title={'Pause'} onPress={() => pause(item.id)} />
                  ) : (
                    <ExButton
                      title={'Resume'}
                      onPress={() => resume(item.id)}
                    />
                  ))}
                <ExButton title={'Cancel'} onPress={() => cancel(item.id)} />
              </View>
            </View>
          );
        }}
        keyExtractor={(item) => item.id}
      />
    </ExWrapper>
  );
};

const styles = StyleSheet.create({
  headerWrapper: {
    flex: 1,
    justifyContent: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    columnGap: 4,
  },
  item: {
    padding: 8,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  itemContent: {
    flex: 1,
    flexShrink: 1,
  },
  redirectInfo: {
    fontStyle: 'italic',
    color: '#666',
    fontSize: 12,
    marginTop: 4,
  },
  flatList: {
    flex: 1,
    flexGrow: 1,
  },
});

export default BasicExampleScreen;
