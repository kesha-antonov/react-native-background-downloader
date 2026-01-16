import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { StyleSheet, View, Text, FlatList, ListRenderItemInfo, Platform } from 'react-native'
import Animated, { useSharedValue, useAnimatedStyle, withTiming } from 'react-native-reanimated'
import { Directory, File, Paths } from 'expo-file-system'
import {
  getExistingUploadTasks,
  createUploadTask,
  setConfig,
  directories,
} from '@kesha-antonov/react-native-background-downloader'
import type { UploadTask } from '@kesha-antonov/react-native-background-downloader'
import { ExButton } from '../../components/commons'
import { toast, uuid } from '../../utils'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

const UPLOADS_SUBDIR = 'uploads'

setConfig({
  isLogsEnabled: true,
  progressMinBytes: 1024 * 10, // 10 KB for faster progress updates on smaller files
})

interface UploadItemData {
  id: string
  fileName: string
  fileSize: number
  task: UploadTask | null
}

interface UploadItemProps {
  item: UploadItemData
  onStart: (item: UploadItemData) => void
  onStop: (id: string) => void
  onPause: (id: string) => void
  onResume: (id: string) => void
}

const UploadItem = React.memo(({ item, onStart, onStop, onPause, onResume }: UploadItemProps) => {
  const { task, fileName, fileSize } = item
  const state = task?.state ?? 'IDLE'
  const isUploading = state === 'UPLOADING'
  const isPaused = state === 'PAUSED'
  const isDone = state === 'DONE'
  const isFailed = state === 'FAILED'
  const isStopped = state === 'STOPPED'
  const isEnded = isDone || isFailed || isStopped

  const bytesUploaded = task?.bytesUploaded ?? 0
  const bytesTotal = task?.bytesTotal ?? fileSize
  const isTotalUnknown = bytesTotal <= 0
  const progress = isTotalUnknown ? 0 : bytesUploaded / bytesTotal
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
      case 'UPLOADING': return '#4CAF50'
      case 'PAUSED': return '#FF9800'
      case 'DONE': return '#2196F3'
      case 'FAILED': return '#F44336'
      case 'STOPPED': return '#9E9E9E'
      default: return '#666'
    }
  }, [state])

  return (
    <View style={styles.uploadItem}>
      <Text style={styles.itemId}>{item.id}</Text>
      <Text style={styles.itemFileName}>📄 {fileName}</Text>
      <Text style={styles.itemFileSize}>{formatBytes(fileSize)}</Text>

      {/* Progress Section */}
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
            {formatBytes(bytesUploaded)}{isTotalUnknown ? '' : ` / ${formatBytes(bytesTotal)}`}
          </Text>
        </View>
      )}

      <View style={styles.buttonRow}>
        {(!task || isFailed || isStopped) && (
          <ExButton title="Upload" onPress={() => onStart(item)} />
        )}
        {task && !isEnded && (
          <>
            <ExButton title="Stop" onPress={() => onStop(item.id)} />
            {isUploading && Platform.OS === 'ios' && (
              <ExButton title="Pause" onPress={() => onPause(item.id)} />
            )}
            {isPaused && (
              <ExButton title="Resume" onPress={() => onResume(item.id)} />
            )}
          </>
        )}
        {isDone && (
          <Text style={styles.successText}>✓ Upload Complete</Text>
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
  onCreateFiles: () => void
  onClearFiles: () => void
  onResetTasks: () => void
  filesCount: number
  tasksCount: number
}

const Header = React.memo(({ onCreateFiles, onClearFiles, onResetTasks, filesCount, tasksCount }: HeaderProps) => {
  return (
    <View style={styles.headerWrapper}>
      <View style={styles.headerInfo}>
        <Text style={styles.headerTitle}>Upload Example</Text>
        <Text style={styles.headerSubtitle}>
          Upload files to httpbin.org (test server)
        </Text>
      </View>
      <View style={styles.headerButtons}>
        <ExButton title="Create Test Files" onPress={onCreateFiles} />
        {filesCount > 0 && (
          <ExButton title="Delete Files" onPress={onClearFiles} />
        )}
        {tasksCount > 0 && (
          <ExButton title="Reset Tasks" onPress={onResetTasks} />
        )}
      </View>
    </View>
  )
})

const UploadExampleScreen = () => {
  const insets = useSafeAreaInsets()

  const [uploadTasks, setUploadTasks] = useState<Map<string, UploadTask>>(new Map())
  const [testFiles, setTestFiles] = useState<UploadItemData[]>([])

  const updateTask = useCallback((task: UploadTask) => {
    setUploadTasks(prev => new Map(prev).set(task.id, task))
  }, [])

  const getUploadsDir = useCallback(() => {
    return new Directory(Paths.document, UPLOADS_SUBDIR)
  }, [])

  const getUploadsDirPath = useCallback(() => {
    if (directories.documents) {
      return directories.documents + '/' + UPLOADS_SUBDIR
    }
    return Paths.document.uri.replace('file://', '') + '/' + UPLOADS_SUBDIR
  }, [])

  const ensureUploadsDirExists = useCallback(() => {
    const uploadsDir = getUploadsDir()
    if (!uploadsDir.exists) {
      uploadsDir.create()
    }
    return uploadsDir
  }, [getUploadsDir])

  const createTestFiles = useCallback(async () => {
    try {
      const uploadsDir = ensureUploadsDirExists()
      const files: UploadItemData[] = []

      // Create small test file (1 KB)
      const smallFileId = uuid()
      const smallFile = new File(uploadsDir, `${smallFileId}.txt`)
      const smallContent = 'Hello, World! '.repeat(64) // ~1 KB
      smallFile.write(smallContent)
      files.push({
        id: smallFileId,
        fileName: `${smallFileId}.txt`,
        fileSize: smallContent.length,
        task: null,
      })

      // Create medium test file (100 KB)
      const mediumFileId = uuid()
      const mediumFile = new File(uploadsDir, `${mediumFileId}.txt`)
      const mediumContent = 'Test data for upload. '.repeat(4500) // ~100 KB
      mediumFile.write(mediumContent)
      files.push({
        id: mediumFileId,
        fileName: `${mediumFileId}.txt`,
        fileSize: mediumContent.length,
        task: null,
      })

      // Create larger test file (500 KB)
      const largeFileId = uuid()
      const largeFile = new File(uploadsDir, `${largeFileId}.txt`)
      const largeContent = 'Large test file content for background upload testing. '.repeat(9000) // ~500 KB
      largeFile.write(largeContent)
      files.push({
        id: largeFileId,
        fileName: `${largeFileId}.txt`,
        fileSize: largeContent.length,
        task: null,
      })

      // Create 1MB test file
      const xlFileId = uuid()
      const xlFile = new File(uploadsDir, `${xlFileId}.bin`)
      // Create binary-like content
      const xlContent = 'X'.repeat(1024 * 1024) // 1 MB
      xlFile.write(xlContent)
      files.push({
        id: xlFileId,
        fileName: `${xlFileId}.bin`,
        fileSize: xlContent.length,
        task: null,
      })

      setTestFiles(files)
      toast(`Created ${files.length} test files`)
      console.log('Created test files:', files)
    } catch (error) {
      console.error('createTestFiles error:', error)
      toast('Error creating test files')
    }
  }, [ensureUploadsDirExists])

  const clearFiles = useCallback(() => {
    try {
      const uploadsDir = getUploadsDir()
      if (uploadsDir.exists) {
        const contents = uploadsDir.list()
        contents.forEach(item => item.delete())
      }
      setTestFiles([])
      setUploadTasks(new Map())
      toast('All files deleted')
    } catch (error) {
      console.warn('clearFiles error:', error)
      toast('Error clearing files')
    }
  }, [getUploadsDir])

  const resetTasks = useCallback(() => {
    uploadTasks.forEach(task => task.stop())
    setUploadTasks(new Map())
    // Reset task references in testFiles
    setTestFiles(prev => prev.map(item => ({ ...item, task: null })))
  }, [uploadTasks])

  const processTask = useCallback((task: UploadTask) => {
    return task
      .begin(({ expectedBytes }) => {
        console.log('upload: begin', { id: task.id, expectedBytes })
        updateTask(task)
      })
      .progress(({ bytesUploaded, bytesTotal }) => {
        console.log('upload: progress', { id: task.id, bytesUploaded, bytesTotal })
        updateTask(task)
      })
      .done(({ responseCode, responseBody, bytesUploaded, bytesTotal }) => {
        console.log('upload: done', { id: task.id, responseCode, bytesUploaded, bytesTotal })
        console.log('upload: responseBody length:', responseBody?.length ?? 0)
        updateTask(task)
        toast(`Upload complete! Status: ${responseCode}`)
      })
      .error(({ error, errorCode }) => {
        console.error('upload: error', { id: task.id, error, errorCode })
        updateTask(task)
        toast(`Upload failed: ${error}`)
      })
  }, [updateTask])

  const startUpload = useCallback((item: UploadItemData) => {
    const uploadsDir = getUploadsDirPath()
    const source = `${uploadsDir}/${item.fileName}`

    console.log('Starting upload with source:', source)

    // Use httpbin.org for testing - it accepts POST with files
    // The response will contain the uploaded file info
    const uploadUrl = 'https://httpbin.org/post'

    const task = createUploadTask({
      id: item.id,
      url: uploadUrl,
      source,
      method: 'POST',
      fieldName: 'file',
      mimeType: 'application/octet-stream',
      headers: {
        'X-Custom-Header': 'test-value',
        'X-Upload-Timestamp': new Date().toISOString(),
      },
      parameters: {
        description: 'Test upload from React Native',
        timestamp: new Date().toISOString(),
      },
      metadata: { fileName: item.fileName },
    })

    processTask(task)
    task.start()

    setUploadTasks(prev => new Map(prev).set(task.id, task))
    setTestFiles(prev => prev.map(f => f.id === item.id ? { ...f, task } : f))
  }, [processTask, getUploadsDirPath])

  const stopUpload = useCallback((id: string) => {
    const task = uploadTasks.get(id)
    if (task) {
      task.stop()
      updateTask(task)
    }
  }, [uploadTasks, updateTask])

  const pauseUpload = useCallback((id: string) => {
    const task = uploadTasks.get(id)
    if (task) {
      task.pause()
      updateTask(task)
    }
  }, [uploadTasks, updateTask])

  const resumeUpload = useCallback((id: string) => {
    const task = uploadTasks.get(id)
    if (task) {
      task.resume()
      updateTask(task)
    }
  }, [uploadTasks, updateTask])

  const resumeExistingTasks = useCallback(async () => {
    try {
      const tasks = await getExistingUploadTasks()
      console.log('Existing upload tasks:', tasks)

      if (tasks.length > 0) {
        tasks.forEach(task => processTask(task))
        setUploadTasks(prev => {
          const newMap = new Map(prev)
          tasks.forEach(task => newMap.set(task.id, task))
          return newMap
        })
      }
    } catch (e) {
      console.warn('getExistingUploadTasks error:', e)
    }
  }, [processTask])

  useEffect(() => {
    resumeExistingTasks()
  }, [])

  const uploadItems = useMemo<UploadItemData[]>(() =>
    testFiles.map(item => ({
      ...item,
      task: uploadTasks.get(item.id) || item.task,
    }))
  , [testFiles, uploadTasks])

  const keyExtractor = useCallback((item: UploadItemData) => item.id, [])

  const renderItem = useCallback(({ item }: ListRenderItemInfo<UploadItemData>) => (
    <UploadItem
      item={item}
      onStart={startUpload}
      onStop={stopUpload}
      onPause={pauseUpload}
      onResume={resumeUpload}
    />
  ), [startUpload, stopUpload, pauseUpload, resumeUpload])

  const renderHeader = useCallback(() => (
    <Header
      onCreateFiles={createTestFiles}
      onClearFiles={clearFiles}
      onResetTasks={resetTasks}
      filesCount={testFiles.length}
      tasksCount={uploadTasks.size}
    />
  ), [createTestFiles, clearFiles, resetTasks, testFiles.length, uploadTasks.size])

  const renderEmpty = useCallback(() => (
    <View style={styles.emptyContainer}>
      <Text style={styles.emptyText}>No test files yet</Text>
      <Text style={styles.emptySubtext}>Tap "Create Test Files" to generate files for upload testing</Text>
    </View>
  ), [])

  return (
    <FlatList
      style={styles.list}
      data={uploadItems}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      ListHeaderComponent={renderHeader}
      ListEmptyComponent={renderEmpty}
      contentContainerStyle={{ paddingBottom: insets.bottom + 20, flexGrow: 1 }}
    />
  )
}

export default UploadExampleScreen

const styles = StyleSheet.create({
  headerWrapper: {
    padding: 16,
    backgroundColor: '#f5f5f5',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  headerInfo: {
    marginBottom: 12,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#333',
    marginBottom: 4,
  },
  headerSubtitle: {
    fontSize: 14,
    color: '#666',
  },
  headerButtons: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  list: {
    flex: 1,
    marginTop: 8,
  },
  uploadItem: {
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
    fontSize: 12,
    fontWeight: '600',
    color: '#999',
    marginBottom: 4,
  },
  itemFileName: {
    fontSize: 16,
    fontWeight: '500',
    color: '#333',
    marginBottom: 4,
  },
  itemFileSize: {
    fontSize: 12,
    color: '#666',
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
    alignItems: 'center',
    gap: 8,
    marginTop: 8,
  },
  successText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#4CAF50',
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 18,
    fontWeight: '500',
    color: '#666',
    marginBottom: 8,
  },
  emptySubtext: {
    fontSize: 14,
    color: '#999',
    textAlign: 'center',
  },
})
