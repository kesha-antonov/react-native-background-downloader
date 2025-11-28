import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo } from 'react-native'
import { Directory, Paths } from 'expo-file-system'
import {
  completeHandler,
  getExistingDownloadTasks,
  createDownloadTask,
  setConfig,
} from '@kesha-antonov/react-native-background-downloader'
import Slider from '@react-native-community/slider'
import { ExButton, ExWrapper } from '../../components/commons'
import { toast, uuid } from '../../utils'

type DownloadTask = ReturnType<typeof createDownloadTask>

const defaultDir = new Directory(Paths.document)

setConfig({
  isLogsEnabled: true,
})

interface FooterProps {
  onStart: () => void
  onStop: () => void
  onReset: () => void
  onClear: () => void
  onRead: () => void
  isStarted: boolean
}

const Footer = React.memo(({
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
  )
})

interface UrlItem {
  id: string
  url: string
  maxRedirects?: number
  title?: string
}

interface TaskItemProps {
  task: DownloadTask
  onPause: (id: string) => void
  onResume: (id: string) => void
  onCancel: (id: string) => void
}

const ENDED_STATES = ['STOPPED', 'DONE', 'FAILED'] as const

const TaskItem = React.memo(({ task, onPause, onResume, onCancel }: TaskItemProps) => {
  const isEnded = ENDED_STATES.includes(task.state as typeof ENDED_STATES[number])
  const isDownloading = task.state === 'DOWNLOADING'

  return (
    <View style={styles.item}>
      <View style={styles.itemContent}>
        <Text>{task.id}</Text>
        <Text>{task.state}</Text>
        <Slider
          disabled
          value={task.bytesDownloaded}
          minimumValue={0}
          maximumValue={task.bytesTotal}
        />
      </View>
      <View>
        {!isEnded &&
          (isDownloading ? (
            <ExButton title="Pause" onPress={() => onPause(task.id)} />
          ) : (
            <ExButton title="Resume" onPress={() => onResume(task.id)} />
          ))}
        <ExButton title="Cancel" onPress={() => onCancel(task.id)} />
      </View>
    </View>
  )
})

const BasicExampleScreen = () => {
  const urlList = useMemo<UrlItem[]>(() => [
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
  ], [])

  const [isStarted, setIsStarted] = useState(false)

  const [downloadTasks, setDownloadTasks] = useState<Map<string, DownloadTask>>(new Map())

  const getTask = useCallback((id: string) => downloadTasks.get(id), [downloadTasks])

  const updateTask = useCallback((task: DownloadTask) => {
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
  }, [])

  const process = useCallback((task: DownloadTask) => {
    return task
      .begin(({ expectedBytes, headers }) => {
        console.log('task: begin', { id: task.id, expectedBytes, headers })
        updateTask(task)
      })
      .progress(({ bytesDownloaded, bytesTotal }) => {
        console.log('task: progress', { id: task.id, bytesDownloaded, bytesTotal })
        updateTask(task)
      })
      .done(() => {
        console.log('task: done', { id: task.id })
        updateTask(task)
        completeHandler(task.id)
      })
      .error(({ error, errorCode }) => {
        console.error('task: error', { id: task.id, error, errorCode })
        updateTask(task)
        completeHandler(task.id)
      })
  }, [updateTask])

  /**
   * It is used to resume your incomplete or unfinished downloads.
   */
  const resumeExistingTasks = useCallback(async () => {
    try {
      const tasks = await getExistingDownloadTasks()

      console.log(tasks)

      if (tasks.length > 0) {
        tasks.forEach(task => process(task))
        setDownloadTasks(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => newMap.set(task.id, task))
          return newMap
        })
        setIsStarted(true)
      }
    } catch (e) {
      console.warn('getExistingDownloadTasks e', e)
    }
  }, [process])

  const readStorage = useCallback(() => {
    try {
      const contents = defaultDir.list()
      const fileNames = contents.map(item => item.name)
      toast('Check logs')
      console.log('Downloaded files:', fileNames)
    } catch (error) {
      console.warn('readStorage error:', error)
      toast('Error reading files')
    }
  }, [])

  const clearStorage = useCallback(() => {
    try {
      const contents = defaultDir.list()
      contents.forEach(item => item.delete())
      toast('Check logs')
      console.log('Deleted file count:', contents.length)
    } catch (error) {
      console.warn('clearStorage error:', error)
      toast('Error clearing files')
    }
  }, [])

  const reset = useCallback(() => {
    downloadTasks.forEach(task => task.stop())
    setDownloadTasks(new Map())
    setIsStarted(false)
  }, [downloadTasks])

  const start = useCallback(() => {
    /**
     * You need to provide the extension of the file in the destination section below.
     * If you cannot provide this, you may experience problems while using your file.
     * For example; Path + File Name + .png
     */
    const taskAttributes = urlList.map(item => {
      const destination = defaultDir.uri + '/' + item.id
      const taskAttribute: any = {
        id: item.id,
        url: item.url,
        destination,
      }

      // Add maxRedirects if specified for this URL
      if (item.maxRedirects) {
        taskAttribute.maxRedirects = item.maxRedirects
        console.log(`Setting maxRedirects=${item.maxRedirects} for URL: ${item.url}`)
      }

      return taskAttribute
    })

    // Create download tasks using the new API
    setDownloadTasks(prev => {
      const newMap = new Map(prev)
      taskAttributes.forEach(taskAttribute => {
        const task = createDownloadTask(taskAttribute)
        process(task)
        task.start() // Start the download
        newMap.set(task.id, task)
      })
      return newMap
    })
    setIsStarted(true)
  }, [urlList, process])

  const stop = useCallback(() => {
    downloadTasks.forEach(task => task.stop())
    setIsStarted(false)
  }, [downloadTasks])

  const pause = useCallback((id: string) => {
    const task = getTask(id)
    if (task) {
      task.pause()
      updateTask(task)
    }
  }, [getTask, updateTask])

  const resume = useCallback((id: string) => {
    const task = getTask(id)
    if (task) {
      task.resume()
      updateTask(task)
    }
  }, [getTask, updateTask])

  const cancel = useCallback((id: string) => {
    const task = getTask(id)
    if (task) {
      task.stop()
      updateTask(task)
    }
  }, [getTask, updateTask])

  useEffect(() => {
    resumeExistingTasks()
  }, [])

  const urlKeyExtractor = useCallback((item: UrlItem) => item.id, [])

  const renderUrlItem = useCallback(({ item }: ListRenderItemInfo<UrlItem>) => (
    <View style={styles.item}>
      <View style={styles.itemContent}>
        <Text>Id: {item.id}</Text>
        <Text>Url: {item.url}</Text>
        {item.maxRedirects && (
          <Text style={styles.redirectInfo}>
            Max redirects: {item.maxRedirects}
            {item.title && ` (${item.title})`}
          </Text>
        )}
      </View>
    </View>
  ), [])

  const renderFooter = useCallback(() => (
    <Footer
      isStarted={isStarted}
      onStart={start}
      onStop={stop}
      onReset={reset}
      onClear={clearStorage}
      onRead={readStorage}
    />
  ), [isStarted, start, stop, reset, clearStorage, readStorage])

  const taskKeyExtractor = useCallback((item: DownloadTask) => item.id, [])

  const downloadTasksArray = useMemo(() => Array.from(downloadTasks.values()), [downloadTasks])

  const renderTaskItem = useCallback(
    ({ item }: ListRenderItemInfo<DownloadTask>) => (
      <TaskItem task={item} onPause={pause} onResume={resume} onCancel={cancel} />
    ),
    [pause, resume, cancel]
  )

  return (
    <ExWrapper>
      <Text style={styles.title}>Basic Example</Text>
      <View>
        <FlatList
          data={urlList}
          keyExtractor={urlKeyExtractor}
          renderItem={renderUrlItem}
          ListFooterComponent={renderFooter}
        />
      </View>
      <FlatList
        style={styles.taskList}
        data={downloadTasksArray}
        renderItem={renderTaskItem}
        keyExtractor={taskKeyExtractor}
      />
    </ExWrapper>
  )
}

export default BasicExampleScreen

const styles = StyleSheet.create({
  headerWrapper: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    padding: 6,
  },
  title: {
    fontSize: 24,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginTop: 16,
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
  taskList: {
    flex: 1,
    flexGrow: 1,
  },
})
