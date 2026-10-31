import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, FlatList, ListRenderItemInfo, Platform, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { Directory, File, Paths } from 'expo-file-system'
import {
  getExistingUploadTasks,
  createUploadTask,
  setConfig,
  directories,
} from '@kesha-antonov/react-native-background-downloader'
import type { UploadTask } from '@kesha-antonov/react-native-background-downloader'
import { formatBytes, toast, uuid } from '../../utils'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import {
  Card,
  ToolbarButton,
  ToolbarCard,
  TransferRow,
  describeProgress,
  type ActionSpec,
  type IoniconName,
} from '../../components'
import { createThemedStyles, useTheme } from '../../theme'

const UPLOADS_SUBDIR = 'uploads'

// Use httpbin.org for testing - it accepts POST with files
// The response will contain the uploaded file info
const UPLOAD_URL = 'https://httpbin.org/post'

// Details show the start of the response, not all of it
const RESPONSE_PREVIEW_LENGTH = 600

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

interface UploadResponse {
  code: number
  body: string
}

// ---------------------------------------------------------------------------
// Row status
// ---------------------------------------------------------------------------

type RowStatus = 'idle' | 'pending' | 'uploading' | 'paused' | 'done' | 'failed' | 'stopped'

// Colors come from the theme (statusColors); this only names and draws each state
const STATUS_STYLES: Record<RowStatus, { label: string, icon: IoniconName }> = {
  idle: { label: 'Not uploaded', icon: 'cloud-upload-outline' },
  pending: { label: 'Starting…', icon: 'hourglass-outline' },
  uploading: { label: 'Uploading', icon: 'arrow-up' },
  paused: { label: 'Paused', icon: 'pause' },
  done: { label: 'Uploaded', icon: 'checkmark' },
  failed: { label: 'Failed', icon: 'alert' },
  stopped: { label: 'Stopped', icon: 'stop' },
}

const isActiveStatus = (status: RowStatus) =>
  status === 'pending' || status === 'uploading' || status === 'paused'

const statusFromTask = (task: UploadTask | null): RowStatus => {
  switch (task?.state) {
    case 'PENDING': return 'pending'
    case 'UPLOADING': return 'uploading'
    case 'PAUSED': return 'paused'
    case 'DONE': return 'done'
    case 'FAILED': return 'failed'
    case 'STOPPED': return 'stopped'
    default: return 'idle'
  }
}

// ---------------------------------------------------------------------------
// Row model: one row per test file, plus restored tasks that no file claims
// ---------------------------------------------------------------------------

type RowKind =
  | 'file' // a generated test file, can be (re)uploaded
  | 'task' // a task restored by getExistingUploadTasks that no test file claims

// Only primitives, so React.memo can compare rows field by field
interface RowModel {
  key: string
  id: string
  kind: RowKind
  title: string
  subtitle: string
  status: RowStatus
  bytesUploaded: number
  bytesTotal: number
  hasTask: boolean
  fileName: string | null
  fileSize: number
  errorCode: number | null
  responseCode: number | null
}

type RowAction = 'start' | 'pause' | 'resume' | 'stop' | 'delete' | 'remove' | 'details'

// Two action slots per row, swapped in place so the row keeps its footprint
const getRowActions = (row: RowModel): [ActionSpec<RowAction>, ActionSpec<RowAction>] => {
  const active = isActiveStatus(row.status)

  let primary: ActionSpec<RowAction>
  if (row.status === 'uploading')
    // Upload pause is iOS-only in this example
    primary = { action: 'pause', label: 'Pause', icon: 'pause', tone: 'warning', disabled: Platform.OS !== 'ios' }
  else if (row.status === 'pending')
    primary = { action: 'pause', label: 'Pause', icon: 'pause', tone: 'warning', disabled: true }
  else if (row.status === 'paused')
    primary = { action: 'resume', label: 'Resume', icon: 'play', tone: 'primary' }
  else if (row.kind === 'file' && row.status !== 'done')
    primary = {
      action: 'start',
      label: 'Start',
      icon: 'cloud-upload-outline',
      mciIcon: 'upload',
      badge: row.status === 'idle' ? undefined : 'sync',
      tone: 'primary',
    }
  else
    // Done: the response is in the details
    primary = { action: 'details', label: 'Details', icon: 'information', tone: 'neutral' }

  let secondary: ActionSpec<RowAction>
  if (active)
    secondary = { action: 'stop', label: 'Stop', icon: 'stop', tone: 'neutral' }
  else if (row.kind === 'file')
    secondary = { action: 'delete', label: 'Delete', icon: 'trash-outline', tone: 'danger' }
  else
    secondary = { action: 'remove', label: 'Remove', icon: 'close', tone: 'neutral' }

  return [primary, secondary]
}

// ---------------------------------------------------------------------------
// Components
// ---------------------------------------------------------------------------

const rowsEqual = (a: RowModel, b: RowModel) => {
  for (const key of Object.keys(a) as (keyof RowModel)[])
    if (a[key] !== b[key]) return false

  return true
}

interface UploadRowProps {
  row: RowModel
  onAction: (action: RowAction, key: string) => void
}

const UploadRow = React.memo(({ row, onAction }: UploadRowProps) => {
  const status = STATUS_STYLES[row.status]
  const { progress, indeterminate, bytesText, percentText } =
    describeProgress(row.status, row.bytesUploaded, row.bytesTotal)

  let stateLabel = status.label
  if (row.status === 'failed' && row.errorCode)
    stateLabel = `${status.label} · ${row.errorCode}`
  else if (row.status === 'done' && row.responseCode)
    stateLabel = `${status.label} · HTTP ${row.responseCode}`

  return (
    <TransferRow
      id={row.id}
      title={row.title}
      subtitle={row.subtitle}
      status={row.status}
      statusIcon={status.icon}
      stateLabel={stateLabel}
      bytesText={bytesText}
      percentText={percentText}
      progress={progress}
      indeterminate={indeterminate}
      actions={getRowActions(row)}
      onAction={action => onAction(action, row.key)}
      onDetails={() => onAction('details', row.key)}
      detailsHint='Shows the file, the server response and errors'
    />
  )
}, (prev, next) => prev.onAction === next.onAction && rowsEqual(prev.row, next.row))

interface HeaderProps {
  onCreateFiles: () => void
  onClearFiles: () => void
  onResetTasks: () => void
  filesCount: number
  tasksCount: number
  activeCount: number
  doneCount: number
}

const Header = React.memo(({ onCreateFiles, onClearFiles, onResetTasks, filesCount, tasksCount, activeCount, doneCount }: HeaderProps) => {
  const styles = useStyles()
  return (
    <View style={styles.header}>
      <ToolbarCard
        title='Uploads'
        stats={`${filesCount} files · ${activeCount} active · ${doneCount} uploaded`}
        caption='POST to httpbin.org/post (test server)'
      >
        <ToolbarButton
          label='Create files'
          accessibilityLabel='Create test files'
          icon='add-circle-outline'
          variant='filled'
          onPress={onCreateFiles}
        />
        <ToolbarButton
          label='Clear tasks'
          accessibilityLabel='Clear all tasks'
          // Forgets every task (finished ones too), stopping any still running: a clear
          // icon, not a stop one, which read as "tasks are running"
          icon='close-circle-outline'
          onPress={onResetTasks}
          disabled={tasksCount === 0}
        />
        <ToolbarButton
          label='Delete files'
          accessibilityLabel='Delete all files'
          icon='trash-outline'
          onPress={onClearFiles}
          disabled={filesCount === 0}
        />
      </ToolbarCard>
    </View>
  )
})

const EmptyState = () => {
  const theme = useTheme()
  const styles = useStyles()
  return (
    <Card style={styles.empty}>
      <View style={styles.emptyIcon}>
        <Ionicons name='document-outline' size={22} color={theme.colors.textSecondary} />
      </View>
      <Text style={styles.emptyTitle}>No test files yet</Text>
      <Text style={styles.emptyText}>Tap Create files to generate 4 files (1 KB – 800 KB) to upload</Text>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

const UploadExampleScreen = () => {
  const insets = useSafeAreaInsets()
  const styles = useStyles()

  const [uploadTasks, setUploadTasks] = useState<Map<string, UploadTask>>(new Map())
  const [testFiles, setTestFiles] = useState<UploadItemData[]>([])
  // Server responses of finished uploads, shown in the row's details
  const [responses, setResponses] = useState<Map<string, UploadResponse>>(new Map())

  // Latest values for the row action handler, which stays stable so
  // rows don't all re-render on every progress tick
  const tasksRef = useRef(uploadTasks)
  tasksRef.current = uploadTasks
  const responsesRef = useRef(responses)
  responsesRef.current = responses

  const updateTask = useCallback((task: UploadTask) => {
    setUploadTasks(prev => new Map(prev).set(task.id, task))
  }, [])

  const forgetTask = useCallback((id: string) => {
    setUploadTasks(prev => {
      const newMap = new Map(prev)
      newMap.delete(id)
      return newMap
    })
    setResponses(prev => {
      if (!prev.has(id)) return prev
      const newMap = new Map(prev)
      newMap.delete(id)
      return newMap
    })
  }, [])

  const getUploadsDir = useCallback(() => {
    return new Directory(Paths.document, UPLOADS_SUBDIR)
  }, [])

  const getUploadsDirPath = useCallback(() => {
    if (directories.documents)
      return directories.documents + '/' + UPLOADS_SUBDIR

    return Paths.document.uri.replace('file://', '') + '/' + UPLOADS_SUBDIR
  }, [])

  const ensureUploadsDirExists = useCallback(() => {
    const uploadsDir = getUploadsDir()
    if (!uploadsDir.exists)
      uploadsDir.create()

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
      smallFile.writeSync(smallContent)
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
      mediumFile.writeSync(mediumContent)
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
      largeFile.writeSync(largeContent)
      files.push({
        id: largeFileId,
        fileName: `${largeFileId}.txt`,
        fileSize: largeContent.length,
        task: null,
      })

      // Create the largest test file. 800 KB: httpbin.org cuts off bodies of 1 MB
      // (connection reset), so a 1 MB file could never upload
      const xlFileId = uuid()
      const xlFile = new File(uploadsDir, `${xlFileId}.bin`)
      // Create binary-like content
      const xlContent = 'X'.repeat(800 * 1000) // 800 KB
      xlFile.writeSync(xlContent)
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
      setResponses(new Map())
      toast('All files deleted')
    } catch (error) {
      console.warn('clearFiles error:', error)
      toast('Error clearing files')
    }
  }, [getUploadsDir])

  const resetTasks = useCallback(() => {
    tasksRef.current.forEach(task => task.stop())
    setUploadTasks(new Map())
    setResponses(new Map())
    // Reset task references in testFiles
    setTestFiles(prev => prev.map(item => ({ ...item, task: null })))
  }, [])

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
        setResponses(prev => new Map(prev).set(task.id, { code: responseCode, body: responseBody ?? '' }))
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

    const task = createUploadTask({
      id: item.id,
      url: UPLOAD_URL,
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
    const task = tasksRef.current.get(id)
    if (task) {
      task.stop()
      updateTask(task)
    }
  }, [updateTask])

  const pauseUpload = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task) {
      task.pause()
      updateTask(task)
    }
  }, [updateTask])

  const resumeUpload = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task) {
      task.resume()
      updateTask(task)
    }
  }, [updateTask])

  // Deletes one test file from disk and drops its row
  const deleteFile = useCallback((row: RowModel) => {
    try {
      if (row.fileName) {
        const file = new File(getUploadsDir(), row.fileName)
        if (file.exists) {
          file.delete()
          toast('File deleted')
        } else {
          toast('File not found')
        }
      }
      setTestFiles(prev => prev.filter(item => item.id !== row.id))
      forgetTask(row.id)
    } catch (error) {
      console.warn('deleteFile error:', error)
      toast('Error deleting file')
    }
  }, [getUploadsDir, forgetTask])

  // Stops a restored task and drops it from the list
  const removeTask = useCallback((id: string) => {
    const task = tasksRef.current.get(id)
    if (task)
      task.stop()
    forgetTask(id)
  }, [forgetTask])

  const showDetails = useCallback((row: RowModel) => {
    const response = responsesRef.current.get(row.id)
    let body: string | null = null
    if (response?.body)
      body = response.body.length > RESPONSE_PREVIEW_LENGTH
        ? `${response.body.slice(0, RESPONSE_PREVIEW_LENGTH)}… (${formatBytes(response.body.length)})`
        : response.body

    const lines = [
      `ID: ${row.id}`,
      `State: ${STATUS_STYLES[row.status].label}`,
      row.fileName ? `File: ${row.fileName} · ${formatBytes(row.fileSize)}` : null,
      row.fileName ? `Source: ${getUploadsDirPath()}/${row.fileName}` : null,
      `URL: ${UPLOAD_URL}`,
      row.status !== 'idle'
        ? `Bytes: ${formatBytes(row.bytesUploaded)}${row.bytesTotal > 0 ? ` / ${formatBytes(row.bytesTotal)}` : ''}`
        : null,
      response ? `Response: HTTP ${response.code}` : null,
      body,
      row.errorCode ? `Error code: ${row.errorCode}` : null,
    ].filter(Boolean)
    Alert.alert(row.title, lines.join('\n\n'))
  }, [getUploadsDirPath])

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
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Test files first (fixed order), then restored tasks no file claims
  const rows = useMemo<RowModel[]>(() => {
    const fileIds = new Set(testFiles.map(item => item.id))

    const buildRow = (base: {
      key: string
      id: string
      kind: RowKind
      title: string
      task: UploadTask | null
      fileName: string | null
      fileSize: number
    }): RowModel => {
      const { task } = base
      const status = statusFromTask(task)
      const bytesTotal = task && task.bytesTotal > 0 ? task.bytesTotal : base.fileSize

      const subtitleParts = [base.id]
      if (base.fileSize > 0) subtitleParts.push(formatBytes(base.fileSize))

      return {
        key: base.key,
        id: base.id,
        kind: base.kind,
        title: base.title,
        subtitle: subtitleParts.join(' · '),
        status,
        bytesUploaded: task?.bytesUploaded ?? 0,
        bytesTotal,
        hasTask: !!task,
        fileName: base.fileName,
        fileSize: base.fileSize,
        errorCode: task && status === 'failed' && task.errorCode ? task.errorCode : null,
        responseCode: status === 'done' ? responses.get(base.id)?.code ?? null : null,
      }
    }

    const result: RowModel[] = testFiles.map(item => buildRow({
      key: item.id,
      id: item.id,
      kind: 'file',
      title: item.fileName,
      task: uploadTasks.get(item.id) || item.task,
      fileName: item.fileName,
      fileSize: item.fileSize,
    }))

    uploadTasks.forEach(task => {
      if (fileIds.has(task.id)) return
      const fileName = (task.metadata as { fileName?: string } | undefined)?.fileName ?? null
      result.push(buildRow({
        key: `task:${task.id}`,
        id: task.id,
        kind: 'task',
        title: fileName ?? task.id,
        task,
        fileName,
        fileSize: 0,
      }))
    })

    return result
  }, [testFiles, uploadTasks, responses])

  const rowsRef = useRef<Map<string, RowModel>>(new Map())
  rowsRef.current = useMemo(() => new Map(rows.map(row => [row.key, row])), [rows])
  const testFilesRef = useRef(testFiles)
  testFilesRef.current = testFiles

  // Stable across renders: everything it needs is read from refs
  const handleRowAction = useCallback((action: RowAction, key: string) => {
    const row = rowsRef.current.get(key)
    if (!row) return

    switch (action) {
      case 'start': {
        const item = testFilesRef.current.find(file => file.id === row.id)
        if (item) startUpload(item)
        break
      }
      case 'pause': pauseUpload(row.id); break
      case 'resume': resumeUpload(row.id); break
      case 'stop': stopUpload(row.id); break
      case 'delete': deleteFile(row); break
      case 'remove': removeTask(row.id); break
      case 'details': showDetails(row); break
    }
  }, [startUpload, pauseUpload, resumeUpload, stopUpload, deleteFile, removeTask, showDetails])

  const keyExtractor = useCallback((row: RowModel) => row.key, [])

  const renderItem = useCallback(({ item }: ListRenderItemInfo<RowModel>) => (
    <UploadRow row={item} onAction={handleRowAction} />
  ), [handleRowAction])

  const activeCount = useMemo(() => rows.filter(row => isActiveStatus(row.status)).length, [rows])
  const doneCount = useMemo(() => rows.filter(row => row.status === 'done').length, [rows])

  // Pass an element, not a render function: a new function identity on every
  // progress update would be a new component type to React and remount the header
  const headerElement = (
    <Header
      onCreateFiles={createTestFiles}
      onClearFiles={clearFiles}
      onResetTasks={resetTasks}
      filesCount={testFiles.length}
      tasksCount={uploadTasks.size}
      activeCount={activeCount}
      doneCount={doneCount}
    />
  )

  return (
    <FlatList
      style={styles.list}
      data={rows}
      keyExtractor={keyExtractor}
      renderItem={renderItem}
      ListHeaderComponent={headerElement}
      ListEmptyComponent={EmptyState}
      contentContainerStyle={{
        paddingTop: 12,
        paddingBottom: insets.bottom + 16,
        paddingLeft: insets.left + 16,
        paddingRight: insets.right + 16,
      }}
    />
  )
}

export default UploadExampleScreen

const useStyles = createThemedStyles(theme => ({
  list: {
    flex: 1,
    backgroundColor: theme.colors.background,
  },
  header: {
    gap: 12,
    marginBottom: 12,
  },
  empty: {
    alignItems: 'center',
    paddingHorizontal: 24,
    paddingVertical: 28,
    gap: 6,
  },
  emptyIcon: {
    width: 44,
    height: 44,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.colors.control,
    marginBottom: 6,
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: theme.colors.text,
  },
  emptyText: {
    fontSize: 13,
    lineHeight: 18,
    color: theme.colors.textSecondary,
    textAlign: 'center',
  },
}))
