import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo, SectionList, SectionListData, Animated } from 'react-native'
import { Directory, File, Paths } from 'expo-file-system'
import {
  completeHandler,
  getExistingDownloadTasks,
  createDownloadTask,
  setConfig,
  directories,
} from '@kesha-antonov/react-native-background-downloader'
import type { DownloadTask } from '@kesha-antonov/react-native-background-downloader'
import { ExButton } from '../../components/commons'
import { toast, uuid } from '../../utils'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

const defaultDir = new Directory(Paths.document)

setConfig({
  isLogsEnabled: true,
  progressMinBytes: 1024 * 100, // 100 KB
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
  destination: string | null
}

interface DownloadItemProps {
  item: DownloadItemData
  onStart: (urlItem: UrlItem) => void
  onStop: (id: string) => void
  onPause: (id: string) => void
  onResume: (id: string) => void
  onDelete: (id: string) => void
}

const DownloadItem = React.memo(({ item, onStart, onStop, onPause, onResume, onDelete }: DownloadItemProps) => {
  const { urlItem, task, destination } = item
  const state = task?.state ?? 'IDLE'
  const isDownloading = state === 'DOWNLOADING'
  const isPaused = state === 'PAUSED'
  const isDone = state === 'DONE'
  const isFailed = state === 'FAILED'
  const isStopped = state === 'STOPPED'
  const isEnded = isDone || isFailed || isStopped

  const bytesDownloaded = task?.bytesDownloaded ?? 0
  const bytesTotal = task?.bytesTotal ?? 0
  // bytesTotal can be -1 when server doesn't send Content-Length header
  const isTotalUnknown = bytesTotal <= 0
  const progress = isTotalUnknown ? 0 : bytesDownloaded / bytesTotal
  const progressPercent = isTotalUnknown ? 0 : Math.round(progress * 100)

  // Animated progress bar
  const progressAnim = useRef(new Animated.Value(0)).current

  useEffect(() => {
    Animated.timing(progressAnim, {
      toValue: progress,
      duration: 300,
      useNativeDriver: false,
    }).start()
  }, [progress, progressAnim])

  const animatedWidth = progressAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0%', '100%'],
  })

  const stateColor = useMemo(() => {
    switch (state) {
      case 'DOWNLOADING': return '#4CAF50'
      case 'PAUSED': return '#FF9800'
      case 'DONE': return '#2196F3'
      case 'FAILED': return '#F44336'
      case 'STOPPED': return '#9E9E9E'
      default: return '#666'
    }
  }, [state])

  return (
    <View style={styles.downloadItem}>
      <Text style={styles.itemId}>{urlItem.id}</Text>
      <Text style={styles.itemUrl} numberOfLines={2}>{urlItem.url}</Text>
      {destination && (
        <Text style={styles.itemDestination} numberOfLines={1}>📁 {destination}</Text>
      )}
      {urlItem.title && (
        <Text style={styles.itemTitle}>{urlItem.title}</Text>
      )}

      {/* Progress Section - always show when task exists */}
      {task && (
        <View style={styles.progressContainer}>
          <View style={styles.progressHeader}>
            <Text style={[styles.stateText, { color: stateColor }]}>{state}</Text>
            <Text style={styles.progressPercent}>{isTotalUnknown ? '—' : `${progressPercent}%`}</Text>
          </View>

          {/* Progress Bar */}
          <View style={styles.progressBarContainer}>
            <Animated.View style={[styles.progressBarFill, { width: isTotalUnknown ? '100%' : animatedWidth, backgroundColor: stateColor, opacity: isTotalUnknown ? 0.3 : 1 }]} />
          </View>

          <Text style={styles.progressText}>
            {formatBytes(bytesDownloaded)}{isTotalUnknown ? '' : ` / ${formatBytes(bytesTotal)}`}
          </Text>
        </View>
      )}

      <View style={styles.buttonRow}>
        {(!task || isFailed || isStopped) && (
          <ExButton title="Start" onPress={() => onStart(urlItem)} />
        )}
        {(isDone || isStopped || isFailed) && bytesDownloaded > 0 && (
          <ExButton title="Delete File" onPress={() => onDelete(urlItem.id)} />
        )}
        {task && !isEnded && (
          <>
            <ExButton title="Stop" onPress={() => onStop(urlItem.id)} />
            {isDownloading && (
              <ExButton title="Pause" onPress={() => onPause(urlItem.id)} />
            )}
            {isPaused && (
              <ExButton title="Resume" onPress={() => onResume(urlItem.id)} />
            )}
          </>
        )}
      </View>
    </View>
  )
})

const formatBytes = (bytes: number): string => {
  if (bytes < 0) return 'Unknown'
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`
}

interface HeaderProps {
  onClear: () => void
  onReset: () => void
  filesCount: number
  tasksCount: number
}

const Header = React.memo(({ onClear, onReset, filesCount, tasksCount }: HeaderProps) => {
  const hasActions = filesCount > 0 || tasksCount > 0
  if (!hasActions)
    return null

  return (
    <View style={styles.headerWrapper}>
      {tasksCount > 0 && (
        <ExButton title="Remove all tasks" onPress={onReset} />
      )}
      {filesCount > 0 && (
        <ExButton title="Delete Files" onPress={onClear} />
      )}
    </View>
  )
})

interface FileItemProps {
  fileName: string
  onDelete: (fileName: string) => void
}

const FileItem = React.memo(({ fileName, onDelete }: FileItemProps) => (
  <View style={styles.fileItem}>
    <Text style={styles.fileName} numberOfLines={1}>📄 {fileName}</Text>
    <ExButton title="Delete" onPress={() => onDelete(fileName)} />
  </View>
))

interface FooterProps {
  files: string[]
  onDeleteFile: (fileName: string) => void
}

const Footer = React.memo(({ files, onDeleteFile }: FooterProps) => {
  if (files.length === 0) return null

  return (
    <View style={styles.footerWrapper}>
      <Text style={styles.footerTitle}>Downloaded Files ({files.length})</Text>
      {files.map(fileName => (
        <FileItem key={fileName} fileName={fileName} onDelete={onDeleteFile} />
      ))}
    </View>
  )
})

const BasicExampleScreen = () => {
  const insets = useSafeAreaInsets()

  const urlList = useMemo<UrlItem[]>(() => [
    {
      id: uuid(),
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
      title: 'MP3 Audio · 6MB',
    },
    {
      id: uuid(),
      url: 'https://filesamples.com/samples/image/jpeg/sample_5184%C3%973456.jpeg',
      title: 'JPEG · 4K · 5.30 MB',
    },
    {
      id: uuid(),
      url: 'https://proof.ovh.net/files/100Mb.dat',
      title: 'Binary file · 100MB',
    },
    {
      id: uuid(),
      url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/CRMDA/claritaspod.com/measure/pscrb.fm/rss/p/stitcher.simplecastaudio.com/9aa1e238-cbed-4305-9808-c9228fc6dd4f/episodes/b0c9a72a-1cb7-4ac9-80a0-36996fc6470f/audio/128/default.mp3?aid=rss_feed&awCollectionId=9aa1e238-cbed-4305-9808-c9228fc6dd4f&awEpisodeId=b0c9a72a-1cb7-4ac9-80a0-36996fc6470f&feed=dxZsm5kX',
      maxRedirects: 10,
      title: 'Podcast with redirects',
    },
    {
      id: uuid(),
      url: 'https://filesamples.com/samples/video/mp4/sample_3840x2160.mp4',
      title: '4K Video · 126MB',
    },
    {
      id: uuid(),
      url: 'https://testfile.org/1.3GBiconpng',
      title: 'Large File · 1GB',
    }
  ], [])

  const [downloadTasks, setDownloadTasks] = useState<Map<string, DownloadTask>>(new Map())
  const [destinations, setDestinations] = useState<Map<string, string>>(new Map())
  const [downloadedFiles, setDownloadedFiles] = useState<string[]>([])

  const updateTask = useCallback((task: DownloadTask) => {
    // Store the actual task instance, not a copy, to preserve methods
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
        readStorage()
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
      setDownloadedFiles(fileNames)
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
      setDownloadedFiles([])
      toast('All files deleted')
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
    // Use library's documents directory, fall back to expo path if undefined
    const documentsDir = directories.documents ?? defaultDir.uri?.replace('file://', '')
    const destination = documentsDir + '/' + urlItem.id
    console.log('Starting download with destination:', destination, 'directories.documents:', directories.documents)
    const taskAttribute: any = {
      id: urlItem.id,
      url: urlItem.url,
      destination,
    }

    if (urlItem.maxRedirects) {
      taskAttribute.maxRedirects = urlItem.maxRedirects
      console.log(`Setting maxRedirects=${urlItem.maxRedirects} for URL: ${urlItem.url}`)
    }

    let task = createDownloadTask(taskAttribute)
    task = process(task)
    task.start()
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
    setDestinations(prev => new Map(prev).set(urlItem.id, destination))
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

  const deleteFile = useCallback((id: string) => {
    try {
      const documentsDir = directories.documents ?? defaultDir.uri?.replace('file://', '')
      const filePath = documentsDir + '/' + id
      const file = new File(filePath)
      if (file.exists) {
        file.delete()
        toast('File deleted')
      }
      setDownloadTasks(prev => {
        const newMap = new Map(prev)
        newMap.delete(id)
        return newMap
      })
      setDestinations(prev => {
        const newMap = new Map(prev)
        newMap.delete(id)
        return newMap
      })
      setDownloadedFiles(prev => prev.filter(name => name !== id))
    } catch (error) {
      console.warn('deleteFile error:', error)
      toast('Error deleting file')
    }
  }, [])

  const deleteSingleFile = useCallback((fileName: string) => {
    try {
      const documentsDir = directories.documents ?? defaultDir.uri?.replace('file://', '')
      const filePath = documentsDir + '/' + fileName
      const file = new File(filePath)
      if (file.exists) {
        file.delete()
        toast('File deleted')
      }
      setDownloadedFiles(prev => prev.filter(name => name !== fileName))
    } catch (error) {
      console.warn('deleteSingleFile error:', error)
      toast('Error deleting file')
    }
  }, [])

  useEffect(() => {
    resumeExistingTasks()
    readStorage()
  }, [])

  const downloadItems = useMemo<DownloadItemData[]>(() =>
    urlList.map(urlItem => ({
      urlItem,
      task: downloadTasks.get(urlItem.id) || null,
      destination: destinations.get(urlItem.id) || null,
    }))
  , [urlList, downloadTasks, destinations])

  const keyExtractor = useCallback((item: DownloadItemData) => item.urlItem.id, [])

  const renderItem = useCallback(({ item }: ListRenderItemInfo<DownloadItemData>) => (
    <DownloadItem
      item={item}
      onStart={startDownload}
      onStop={stopDownload}
      onPause={pauseDownload}
      onResume={resumeDownload}
      onDelete={deleteFile}
    />
  ), [startDownload, stopDownload, pauseDownload, resumeDownload, deleteFile])

  const renderHeader = useCallback(() => (
    <Header onReset={reset} onClear={clearStorage} filesCount={downloadedFiles.length} tasksCount={downloadTasks.size} />
  ), [reset, clearStorage, downloadedFiles.length, downloadTasks.size])

  const renderFooter = useCallback(() => (
    <Footer files={downloadedFiles} onDeleteFile={deleteSingleFile} />
  ), [downloadedFiles, deleteSingleFile])

  return (
    <FlatList
      style={styles.list}
      data={downloadItems}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      ListHeaderComponent={renderHeader}
      ListFooterComponent={renderFooter}
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
    marginTop: 8,
  },
  downloadItem: {
    padding: 16,
    marginHorizontal: 12,
    marginVertical: 8,
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
  itemDestination: {
    fontSize: 10,
    color: '#4CAF50',
    marginBottom: 4,
  },
  itemTitle: {
    fontSize: 12,
    fontStyle: 'italic',
    color: '#888',
    marginBottom: 8,
  },
  progressContainer: {
    marginVertical: 8,
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
  footerWrapper: {
    marginTop: 20,
    marginHorizontal: 12,
    padding: 12,
    backgroundColor: '#f0f8ff',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#b0d4f1',
  },
  footerTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  fileItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#ddd',
  },
  fileName: {
    flex: 1,
    fontSize: 12,
    color: '#333',
    marginRight: 8,
  },
})
