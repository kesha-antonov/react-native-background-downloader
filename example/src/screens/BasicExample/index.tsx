import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo, SectionList, SectionListData, TouchableOpacity } from 'react-native'
import Animated, { useSharedValue, useAnimatedStyle, withTiming, FadeIn, FadeOut, LinearTransition } from 'react-native-reanimated'
import { Ionicons } from '@expo/vector-icons'
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
import { createMMKV } from 'react-native-mmkv'

const DOWNLOADS_SUBDIR = 'downloads'
const defaultDir = new Directory(Paths.document)

// Storage helper for persisting task IDs between app restarts
const storage = createMMKV({ id: 'download-example-storage' })
const TASK_IDS_KEY = 'taskIds'

const TaskIdStorage = {
  load: (): Record<string, string> => {
    try {
      const json = storage.getString(TASK_IDS_KEY)
      return json ? JSON.parse(json) : {}
    } catch (e) {
      console.warn('Failed to load persisted task IDs:', e)
      return {}
    }
  },

  save: (mapping: Record<string, string>) => {
    storage.set(TASK_IDS_KEY, JSON.stringify(mapping))
  },

  getOrCreate: (url: string): string => {
    const mapping = TaskIdStorage.load()
    if (mapping[url]) return mapping[url]

    const newId = uuid()
    mapping[url] = newId
    TaskIdStorage.save(mapping)
    return newId
  },

  clear: (url: string) => {
    const mapping = TaskIdStorage.load()
    delete mapping[url]
    TaskIdStorage.save(mapping)
  },

  clearAll: () => {
    storage.remove(TASK_IDS_KEY)
  },
}

setConfig({
  isLogsEnabled: true,
  progressMinBytes: 1024 * 100, // 100 KB
  logCallback: (log: string) => {
    console.log('[RNBD]', log)
  }
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

  // Animated progress bar using reanimated
  const progressAnim = useSharedValue(0)

  useEffect(() => {
    progressAnim.value = withTiming(progress, { duration: 300 })
  }, [progress])

  const animatedStyle = useAnimatedStyle(() => ({
    width: `${progressAnim.value * 100}%`,
  }))

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
            <Animated.View style={[styles.progressBarFill, isTotalUnknown ? { width: '100%', backgroundColor: stateColor, opacity: 0.3 } : [animatedStyle, { backgroundColor: stateColor }]]} />
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

// Component to display an existing task (from getExistingDownloadTasks)
const TaskItem = React.memo(({ task, onRemove }: { task: DownloadTask, onRemove: (id: string) => void }) => {
  const state = task.state
  const bytesDownloaded = task.bytesDownloaded ?? 0
  const bytesTotal = task.bytesTotal ?? 0
  const isTotalUnknown = bytesTotal <= 0
  const progress = isTotalUnknown ? 0 : bytesDownloaded / bytesTotal
  const progressPercent = isTotalUnknown ? 0 : Math.round(progress * 100)

  // Animated progress bar using reanimated - initialize with current progress
  const progressAnim = useSharedValue(progress)

  useEffect(() => {
    progressAnim.value = withTiming(progress, { duration: 300 })
  }, [progress])

  const animatedStyle = useAnimatedStyle(() => ({
    width: `${progressAnim.value * 100}%`,
  }))

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
    <Animated.View
      style={styles.taskItem}
      exiting={FadeOut.duration(200)}
      layout={LinearTransition.duration(200)}
    >
      <View style={styles.taskHeader}>
        <Text style={styles.taskId} numberOfLines={1}>{task.id}</Text>
        <Text style={[styles.taskState, { color: stateColor }]}>{state}</Text>
        <TouchableOpacity onPress={() => onRemove(task.id)} style={styles.removeButton} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
          <Ionicons name="close" size={18} color="#999" />
        </TouchableOpacity>
      </View>
      <View style={styles.taskProgressRow}>
        <View style={styles.taskProgressBarContainer}>
          <Animated.View style={[styles.taskProgressBarFill, isTotalUnknown ? { width: '100%', backgroundColor: stateColor, opacity: 0.3 } : [animatedStyle, { backgroundColor: stateColor }]]} />
        </View>
        <Text style={styles.taskProgressText}>
          {isTotalUnknown ? formatBytes(bytesDownloaded) : `${progressPercent}%`}
        </Text>
      </View>
      <Text style={styles.taskBytes}>
        {formatBytes(bytesDownloaded)}{isTotalUnknown ? '' : ` / ${formatBytes(bytesTotal)}`}
      </Text>
    </Animated.View>
  )
})

interface HeaderProps {
  onClear: () => void
  onReset: () => void
  onRemoveTask: (id: string) => void
  onDeleteFile: (fileName: string) => void
  filesCount: number
  files: string[]
  tasks: Map<string, DownloadTask>
  downloadsPath: string
}

const Header = React.memo(({ onClear, onReset, onRemoveTask, onDeleteFile, filesCount, files, tasks, downloadsPath }: HeaderProps) => {
  const tasksCount = tasks.size

  // Convert tasks to array for display
  const tasksList = Array.from(tasks.values())

  return (
    <View>
      {tasksList.length > 0 && (
        <Animated.View
          style={styles.tasksSection}
          exiting={FadeOut.duration(200)}
          layout={LinearTransition.duration(200)}
        >
          <View style={styles.tasksSectionHeader}>
            <Text style={styles.tasksSectionTitle}>Tasks ({tasksList.length})</Text>
            <TouchableOpacity onPress={onReset} style={styles.clearAllButton} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
              <Text style={styles.clearAllText}>Clear all</Text>
            </TouchableOpacity>
          </View>
          {tasksList.map(task => (
            <TaskItem key={task.id} task={task} onRemove={onRemoveTask} />
          ))}
        </Animated.View>
      )}

      {files.length > 0 && (
        <Animated.View
          style={styles.filesSection}
          exiting={FadeOut.duration(200)}
          layout={LinearTransition.duration(200)}
        >
          <View style={styles.filesSectionHeader}>
            <Text style={styles.filesSectionTitle}>Downloaded Files ({files.length})</Text>
            <TouchableOpacity onPress={onClear} style={styles.clearAllButton} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
              <Text style={styles.clearAllText}>Delete all</Text>
            </TouchableOpacity>
          </View>
          {files.map(fileName => (
            <Animated.View
              key={fileName}
              style={styles.fileItem}
              entering={FadeIn.duration(200)}
              exiting={FadeOut.duration(200)}
              layout={LinearTransition.duration(200)}
            >
              <Ionicons name="document" size={20} color="#2196F3" style={styles.fileIcon} />
              <View style={styles.fileInfo}>
                <Text style={styles.fileName}>{fileName}</Text>
                <Text style={styles.filePath}>{downloadsPath}/{fileName}</Text>
              </View>
              <TouchableOpacity onPress={() => onDeleteFile(fileName)} style={styles.removeButton} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
                <Ionicons name="close" size={18} color="#999" />
              </TouchableOpacity>
            </Animated.View>
          ))}
        </Animated.View>
      )}
    </View>
  )
})

const BasicExampleScreen = () => {
  const insets = useSafeAreaInsets()

  // URL definitions - static, no storage access
  const urlDefinitions = useMemo(() => [
    {
      url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
      title: 'MP3 Audio · 6MB',
    },
    {
      url: 'https://filesamples.com/samples/image/jpeg/sample_5184%C3%973456.jpeg',
      title: 'JPEG · 4K · 5.30 MB',
    },
    {
      url: 'https://proof.ovh.net/files/100Mb.dat',
      title: 'Binary file · 100MB',
    },
    {
      url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/CRMDA/claritaspod.com/measure/pscrb.fm/rss/p/stitcher.simplecastaudio.com/9aa1e238-cbed-4305-9808-c9228fc6dd4f/episodes/b0c9a72a-1cb7-4ac9-80a0-36996fc6470f/audio/128/default.mp3?aid=rss_feed&awCollectionId=9aa1e238-cbed-4305-9808-c9228fc6dd4f&awEpisodeId=b0c9a72a-1cb7-4ac9-80a0-36996fc6470f&feed=dxZsm5kX',
      maxRedirects: 10,
      title: 'Podcast with redirects',
    },
    {
      url: 'https://filesamples.com/samples/video/mp4/sample_3840x2160.mp4',
      title: '4K Video · 126MB',
    },
    {
      url: 'https://testfile.org/1.3GBiconpng',
      title: 'Large File · 1GB',
    },
    {
      url: 'https://firebasestorage.googleapis.com/v0/b/higher-self-7603e.appspot.com/o/hypnosis-sounds%2FjiDIGAaGrNOdN3hjb4xemmGRZyu2-Tue,%2018%20Nov%202025%2010:10:30%20GMT?alt=media&token=12152238-c662-4c94-94e6-864396e41534',
      title: 'Firebase Storage Test',
    },
  ], [])

  // State for URL list with IDs - initialized empty, populated after mount
  const [urlList, setUrlList] = useState<UrlItem[]>([])

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
        updateTask(task)
      })
      .progress(({ bytesDownloaded, bytesTotal }) => {
        updateTask(task)
      })
      .done(() => {
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

      if (tasks.length > 0) {
        tasks.forEach(task => process(task))
        setDownloadTasks(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => newMap.set(task.id, task))
          return newMap
        })
        // Restore destinations from task's destination property or metadata
        setDestinations(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => {
            // First try native destination, then metadata
            const dest = task.destination || (task.metadata as any)?.destination
            if (dest) {
              newMap.set(task.id, dest)
            }
          })
          return newMap
        })
      }
    } catch (e) {
      console.warn('getExistingDownloadTasks e', e)
    }
  }, [process])

  const getDownloadsDir = useCallback(() => {
    // Use Paths.document from expo-file-system which has proper permissions
    return new Directory(Paths.document, DOWNLOADS_SUBDIR)
  }, [])

  const getDownloadsDirPath = useCallback(() => {
    // For the download library, we need path without file:// prefix
    // Use directories.documents from our library if available, otherwise extract from expo Paths
    if (directories.documents) {
      return directories.documents + '/' + DOWNLOADS_SUBDIR
    }
    // Paths.document.uri is a URI string, remove file:// prefix
    return Paths.document.uri.replace('file://', '') + '/' + DOWNLOADS_SUBDIR
  }, [])

  const ensureDownloadsDirExists = useCallback(() => {
    const downloadsDir = getDownloadsDir()
    if (!downloadsDir.exists) {
      downloadsDir.create()
    }
    return downloadsDir
  }, [getDownloadsDir])

  const readStorage = useCallback(() => {
    try {
      const downloadsDir = getDownloadsDir()
      if (!downloadsDir.exists) {
        setDownloadedFiles([])
        return
      }
      const contents = downloadsDir.list()
      const fileNames = contents.map(item => item.name)
      setDownloadedFiles(fileNames)
    } catch (error) {
      console.warn('readStorage error:', error)
      toast('Error reading files')
    }
  }, [getDownloadsDir])

  const clearStorage = useCallback(() => {
    try {
      const downloadsDir = getDownloadsDir()
      if (!downloadsDir.exists) {
        setDownloadedFiles([])
        setDestinations(new Map())
        return
      }
      const contents = downloadsDir.list()
      contents.forEach(item => item.delete())
      setDownloadedFiles([])
      setDestinations(new Map())
      toast('All files deleted')
    } catch (error) {
      console.warn('clearStorage error:', error)
      toast('Error clearing files')
    }
  }, [getDownloadsDir])

  const reset = useCallback(() => {
    downloadTasks.forEach(task => task.stop())
    setDownloadTasks(new Map())
  }, [downloadTasks])

  const removeTask = useCallback((id: string) => {
    const task = downloadTasks.get(id)
    if (task) {
      task.stop()
    }
    setDownloadTasks(prev => {
      const newMap = new Map(prev)
      newMap.delete(id)
      return newMap
    })
  }, [downloadTasks])

  const getFileNameFromUrl = useCallback((url: string, fallbackId: string): string => {
    try {
      // Parse the URL and get the pathname
      const urlObj = new URL(url)
      const pathname = decodeURIComponent(urlObj.pathname)
      // Get the last segment of the path
      const segments = pathname.split('/').filter(Boolean)
      const lastSegment = segments[segments.length - 1]
      // Check if it looks like a filename (has an extension)
      if (lastSegment && lastSegment.includes('.')) {
        return lastSegment
      }
    } catch (e) {
      console.warn('Failed to parse URL for filename:', e)
    }
    return fallbackId
  }, [])

  const startDownload = useCallback((urlItem: UrlItem) => {
    // Ensure downloads directory exists
    ensureDownloadsDirExists()
    // Extract filename from URL, fallback to task ID
    const fileName = getFileNameFromUrl(urlItem.url, urlItem.id)
    const destination = getDownloadsDirPath() + '/' + fileName
    const taskAttribute: any = {
      id: urlItem.id,
      url: urlItem.url,
      destination,
      // Store destination in metadata so we can restore it after app restart
      metadata: { destination },
    }

    if (urlItem.maxRedirects)
      taskAttribute.maxRedirects = urlItem.maxRedirects

    let task = createDownloadTask(taskAttribute)
    process(task)
    task.start()
    setDownloadTasks(prev => new Map(prev).set(task.id, task))
    setDestinations(prev => new Map(prev).set(urlItem.id, destination))
  }, [process, ensureDownloadsDirExists, getDownloadsDirPath, getFileNameFromUrl])

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
      // Get the destination path to find the actual filename
      const destination = destinations.get(id)
      if (destination) {
        const fileName = destination.split('/').pop() || id
        const downloadsDir = getDownloadsDir()
        const file = new File(downloadsDir, fileName)
        if (file.exists) {
          file.delete()
          toast('File deleted')
        }
        setDownloadedFiles(prev => prev.filter(name => name !== fileName))
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
    } catch (error) {
      console.warn('deleteFile error:', error)
      toast('Error deleting file')
    }
  }, [getDownloadsDir, destinations])

  const deleteSingleFile = useCallback((fileName: string) => {
    try {
      const downloadsDir = getDownloadsDir()
      const file = new File(downloadsDir, fileName)
      if (file.exists) {
        file.delete()
        toast('File deleted')
      } else {
        toast('File not found')
      }
      // Clear destination for the task that had this file
      setDestinations(prev => {
        const newMap = new Map(prev)
        for (const [id, dest] of newMap) {
          if (dest.endsWith('/' + fileName)) {
            newMap.delete(id)
            break
          }
        }
        return newMap
      })
      // Refresh file list from disk
      readStorage()
    } catch (error) {
      console.warn('deleteSingleFile error:', error)
      toast('Error deleting file')
    }
  }, [getDownloadsDir, readStorage])

  useEffect(() => {
    // Initialize URL list with persisted IDs after mount (when MMKV is ready)
    const initializedUrlList = urlDefinitions.map(def => ({
      id: TaskIdStorage.getOrCreate(def.url),
      ...def,
    }))
    setUrlList(initializedUrlList)

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

  const downloadsPath = getDownloadsDirPath()

  // Filter out files that have an active (non-DONE) download task
  const completedFiles = useMemo(() => {
    return downloadedFiles.filter(fileName => {
      // Check if any active task is downloading this file
      for (const task of downloadTasks.values()) {
        // Check if task's destination matches this file
        const taskDestination = destinations.get(task.id)
        if (taskDestination && taskDestination.endsWith('/' + fileName)) {
          // File has an associated task - only show if task is DONE
          return task.state === 'DONE'
        }
      }
      // No active task for this file - it's a completed download from a previous session
      return true
    })
  }, [downloadedFiles, downloadTasks, destinations])

  const renderHeader = useCallback(() => (
    <Header onReset={reset} onClear={clearStorage} onRemoveTask={removeTask} onDeleteFile={deleteSingleFile} filesCount={completedFiles.length} files={completedFiles} tasks={downloadTasks} downloadsPath={downloadsPath} />
  ), [reset, clearStorage, removeTask, deleteSingleFile, completedFiles, downloadTasks, downloadsPath])

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
  tasksSection: {
    margin: 12,
    padding: 12,
    backgroundColor: '#e8f5e9',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#a5d6a7',
  },
  tasksSectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  tasksSectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#2e7d32',
  },
  filesSection: {
    margin: 12,
    padding: 12,
    backgroundColor: '#e3f2fd',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#90caf9',
  },
  filesSectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  filesSectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1565c0',
  },
  clearAllButton: {
    paddingVertical: 4,
    paddingHorizontal: 8,
  },
  clearAllText: {
    fontSize: 14,
    color: '#666',
  },
  taskItem: {
    backgroundColor: '#fff',
    borderRadius: 6,
    padding: 10,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#c8e6c9',
  },
  taskHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  taskId: {
    fontSize: 12,
    fontWeight: '600',
    color: '#333',
    flex: 1,
  },
  taskState: {
    fontSize: 12,
    fontWeight: '600',
    marginRight: 8,
  },
  removeButton: {
    padding: 4,
  },
  taskProgressRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  taskProgressBarContainer: {
    flex: 1,
    height: 6,
    backgroundColor: '#E0E0E0',
    borderRadius: 3,
    overflow: 'hidden',
  },
  taskProgressBarFill: {
    height: '100%',
    borderRadius: 3,
  },
  taskProgressText: {
    fontSize: 11,
    fontWeight: '600',
    color: '#333',
    width: 40,
    textAlign: 'right',
  },
  taskBytes: {
    fontSize: 10,
    color: '#666',
    marginTop: 4,
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
  fileItem: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 6,
    padding: 10,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#bbdefb',
  },
  fileIcon: {
    marginRight: 10,
  },
  fileInfo: {
    flex: 1,
    marginRight: 8,
  },
  fileName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginBottom: 2,
  },
  filePath: {
    fontSize: 10,
    color: '#888',
  },
})
