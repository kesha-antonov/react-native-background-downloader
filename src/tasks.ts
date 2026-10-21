import type { DownloadTask } from './DownloadTask'
import type { UploadTask } from './UploadTask'

// Tasks that native events are delivered to, by id. In their own module so a task can remove
// itself on stop without a circular import of index.
export const tasksMap = new Map<string, DownloadTask>()
export const uploadTasksMap = new Map<string, UploadTask>()
