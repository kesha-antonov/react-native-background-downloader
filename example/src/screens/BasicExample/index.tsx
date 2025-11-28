import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo } from 'react-native'
import { Directory, Paths } from 'expo-file-system'
import {
  completeHandler,
  getExistingDownloadTasks,
  createDownloadTask,
  setConfig,
  directories,
} from '@kesha-antonov/react-native-background-downloader'
// Progress bar is implemented with View components
import { ExButton, ExWrapper } from '../../components/commons'
import { toast, uuid } from '../../utils'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

type DownloadTask = ReturnType<typeof createDownloadTask>

const defaultDir = new Directory(Paths.document)

setConfig({
  isLogsEnabled: true,
})

interface UrlItem {
  id: string
  url: string
  maxRedirects?: number
  title?: string
}

interface DownloadItemData {
  urlItem: UrlItem
  task: DownloadTask | null
}

interface DownloadItemProps {
  item: DownloadItemData
  onStart: (urlItem: UrlItem) => void
  onStop: (id: string) => void
  onPause: (id: string) => void
  onResume: (id: string) => void
}

const DownloadItem = React.memo(({ item, onStart, onStop, onPause, onResume }: DownloadItemProps) => {
  const { urlItem, task } = item
  const state = task?.state ?? 'IDLE'
  const isIdle = !task
  const isPending = task?.state === 'PENDING'
  const isDownloading = state === 'DOWNLOADING'
  const isPaused = state === 'PAUSED'
  const isDone = state === 'DONE'
  const isFailed = state === 'FAILED'
  const isStopped = state === 'STOPPED'
  const isEnded = isDone || isFailed || isStopped

  const bytesDownloaded = task?.bytesDownloaded ?? 0
  const bytesTotal = task?.bytesTotal ?? 0
  const progress = bytesTotal > 0 ? bytesDownloaded / bytesTotal : 0
  const progressPercent = Math.round(progress * 100)

  const getStateColor = () => {
    switch (state) {
      case 'DOWNLOADING': return '#4CAF50'
      case 'PAUSED': return '#FF9800'
      case 'DONE': return '#2196F3'
      case 'FAILED': return '#F44336'
      case 'STOPPED': return '#9E9E9E'
      default: return '#666'
    }
  }

  return (
    <View style={styles.downloadItem}>
      <Text style={styles.itemId}>{urlItem.id}</Text>
      <Text style={styles.itemUrl} numberOfLines={2}>{urlItem.url}</Text>
      {urlItem.title && (
        <Text style={styles.itemTitle}>{urlItem.title}</Text>
      )}

      {/* Progress Section - always show when task exists */}
      {task && (
        <View style={styles.progressContainer}>
          <View style={styles.progressHeader}>
            <Text style={[styles.stateText, { color: getStateColor() }]}>{state}</Text>
            <Text style={styles.progressPercent}>{progressPercent}%</Text>
          </View>

          {/* Progress Bar */}
          <View style={styles.progressBarContainer}>
            <View style={[styles.progressBarFill, { width: `${progressPercent}%`, backgroundColor: getStateColor() }]} />
          </View>

          <Text style={styles.progressText}>
            {formatBytes(bytesDownloaded)} / {formatBytes(bytesTotal)}
          </Text>
        </View>
      )}

      <View style={styles.buttonRow}>
        {(!task || isEnded) && (
          <ExButton title="Start" onPress={() => onStart(urlItem)} />
        )}
        {task && !isEnded && (
          <>
            <ExButton title="Stop" onPress={() => onStop(urlItem.id)} />
            {isDownloading && (
              <ExButton title="Pause" onPress={() => onPause(urlItem.id)} />
            )}
            {(isPaused || isPending) && (
              <ExButton title="Resume" onPress={() => onResume(urlItem.id)} />
            )}
          </>
        )}
      </View>
    </View>
  )
})

const formatBytes = (bytes: number): string => {
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`
}

interface HeaderProps {
  onClear: () => void
  onRead: () => void
  onReset: () => void
}

const Header = React.memo(({ onClear, onRead, onReset }: HeaderProps) => (
  <View style={styles.headerWrapper}>
    <ExButton title="Reset All" onPress={onReset} />
    <ExButton title="Delete Files" onPress={onClear} />
    <ExButton title="List Files" onPress={onRead} />
  </View>
))

const BasicExampleScreen = () => {
  const insets = useSafeAreaInsets()

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
      maxRedirects: 10,
      title: 'Podcast with redirects',
    },
  ], [])

  const [downloadTasks, setDownloadTasks] = useState<Map<string, DownloadTask>>(new Map())

  const updateTask = useCallback((task: DownloadTask) => {
    setDownloadTasks(prev => new Map(prev).set(task.id, { ...task } as DownloadTask))
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

  const resumeExistingTasks = useCallback(async () => {
    try {
      const tasks = await getExistingDownloadTasks()
      console.log('Existing tasks:', tasks)

      if (tasks.length > 0) {
        tasks.forEach(task => process(task))
        setDownloadTasks(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => newMap.set(task.id, task))
          return newMap
        })
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
  }, [downloadTasks])

  const startDownload = useCallback((urlItem: UrlItem) => {
    // Use library's documents directory - expo-file-system paths may not be compatible
    const destination = directories.documents + '/' + urlItem.id
    console.log('Starting download with destination:', destination)
    const taskAttribute: any = {
      id: urlItem.id,
      url: urlItem.url,
      destination,
    }

    if (urlItem.maxRedirects) {
      taskAttribute.maxRedirects = urlItem.maxRedirects
      console.log(`Setting maxRedirects=${urlItem.maxRedirects} for URL: ${urlItem.url}`)
    }

    const task = createDownloadTask(taskAttribute)
    process(task)
    task.start()
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
  }, [process])

  const stopDownload = useCallback((id: string) => {
    const task = downloadTasks.get(id)
    if (task) {
      task.stop()
      updateTask(task)
    }
  }, [downloadTasks, updateTask])

  const pauseDownload = useCallback((id: string) => {
    const task = downloadTasks.get(id)
    if (task) {
      task.pause()
      updateTask(task)
    }
  }, [downloadTasks, updateTask])

  const resumeDownload = useCallback((id: string) => {
    const task = downloadTasks.get(id)
    if (task) {
      task.resume()
      updateTask(task)
    }
  }, [downloadTasks, updateTask])

  useEffect(() => {
    resumeExistingTasks()
  }, [])

  const downloadItems = useMemo<DownloadItemData[]>(() =>
    urlList.map(urlItem => ({
      urlItem,
      task: downloadTasks.get(urlItem.id) || null,
    }))
  , [urlList, downloadTasks])

  const keyExtractor = useCallback((item: DownloadItemData) => item.urlItem.id, [])

  const renderItem = useCallback(({ item }: ListRenderItemInfo<DownloadItemData>) => (
    <DownloadItem
      item={item}
      onStart={startDownload}
      onStop={stopDownload}
      onPause={pauseDownload}
      onResume={resumeDownload}
    />
  ), [startDownload, stopDownload, pauseDownload, resumeDownload])

  const renderHeader = useCallback(() => (
    <Header onReset={reset} onClear={clearStorage} onRead={readStorage} />
  ), [reset, clearStorage, readStorage])

  return (
    <FlatList
      style={styles.list}
      data={downloadItems}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      ListHeaderComponent={renderHeader}
      contentContainerStyle={{ paddingBottom: insets.bottom + 20 }}
    />
  )
}

export default BasicExampleScreen

const styles = StyleSheet.create({
  headerWrapper: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-evenly',
    padding: 12,
    backgroundColor: '#f5f5f5',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  title: {
    fontSize: 24,
    fontWeight: '500',
    textAlign: 'center',
    alignSelf: 'center',
    marginVertical: 16,
  },
  list: {
    flex: 1,
  },
  downloadItem: {
    padding: 16,
    marginHorizontal: 12,
    marginVertical: 6,
    backgroundColor: '#fff',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#e0e0e0',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
    elevation: 2,
  },
  itemId: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
    marginBottom: 4,
  },
  itemUrl: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  itemTitle: {
    fontSize: 12,
    fontStyle: 'italic',
    color: '#888',
    marginBottom: 8,
  },
  progressContainer: {
    marginVertical: 12,
  },
  progressHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  stateText: {
    fontSize: 14,
    fontWeight: '600',
  },
  progressPercent: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  progressBarContainer: {
    height: 8,
    backgroundColor: '#E0E0E0',
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 6,
  },
  progressBarFill: {
    height: '100%',
    borderRadius: 4,
  },
  progressText: {
    fontSize: 11,
    color: '#888',
    textAlign: 'right',
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'flex-start',
    gap: 8,
    marginTop: 8,
  },
})
