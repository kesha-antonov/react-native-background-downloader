import { DownloadTask } from './DownloadTask'
import {
  GroupDoneHandler,
  GroupErrorHandler,
  GroupErrorHandlerParams,
  GroupProgressHandler,
  GroupProgressHandlerParams,
} from './types'

/**
 * Aggregates a set of DownloadTasks bound by a groupId into a single logical unit.
 * Reports combined progress (bytesDownloaded/bytesTotal across all tasks) and
 * fires `done` once every task in the group reaches the DONE state.
 *
 * On Android, when notification grouping is enabled with mode 'summaryOnly',
 * the native side already collapses the group's notifications into a single one
 * with aggregate progress - this class mirrors that aggregation on the JS side
 * so apps can drive their own UI (and works on iOS too, where there's no native grouping).
 */
export class GroupTask {
  groupId: string
  groupName?: string
  tasks: DownloadTask[]

  bytesDownloaded: number = 0
  bytesTotal: number = 0

  progressHandler?: GroupProgressHandler
  doneHandler?: GroupDoneHandler
  errorHandler?: GroupErrorHandler

  /** @internal Set by groupingApi.createGroup to remove from registry once all tasks stop */
  _onStop?: () => void

  constructor (groupId: string, tasks: DownloadTask[], groupName?: string) {
    this.groupId = groupId
    this.groupName = groupName
    this.tasks = tasks

    for (const task of tasks)
      task._groupObserver = {
        onProgress: () => this._recalculate(),
        onDone: () => this._recalculate(),
        onError: params => { this._onTaskError(task, params); this._recalculate() },
      }

    this._recalculate()
  }

  // event listener setters

  progress (handler: GroupProgressHandler) {
    if (typeof handler !== 'function')
      throw new Error('progress handler must be a function')

    this.progressHandler = handler
    return this
  }

  done (handler: GroupDoneHandler) {
    if (typeof handler !== 'function')
      throw new Error('done handler must be a function')

    this.doneHandler = handler
    return this
  }

  error (handler: GroupErrorHandler) {
    if (typeof handler !== 'function')
      throw new Error('error handler must be a function')

    this.errorHandler = handler
    return this
  }

  // group-wide controls

  start () {
    for (const task of this.tasks)
      task.start()
  }

  async pause (): Promise<void> {
    await Promise.all(this.tasks.map(task => task.pause()))
  }

  async resume (): Promise<void> {
    await Promise.all(this.tasks.map(task => task.resume()))
  }

  async stop (): Promise<void> {
    await Promise.all(this.tasks.map(task => task.stop()))
    this._onStop?.()
  }

  addTask (task: DownloadTask) {
    task._groupObserver = {
      onProgress: () => this._recalculate(),
      onDone: () => this._recalculate(),
      onError: params => { this._onTaskError(task, params); this._recalculate() },
    }
    this.tasks.push(task)
    this._recalculate()
  }

  private _recalculate () {
    let bytesDownloaded = 0
    let bytesTotal = 0
    let completedTasks = 0
    let failedTasks = 0

    for (const task of this.tasks) {
      bytesDownloaded += task.bytesDownloaded
      bytesTotal += task.bytesTotal
      if (task.state === 'DONE')
        completedTasks++
      else if (task.state === 'FAILED')
        failedTasks++
    }

    this.bytesDownloaded = bytesDownloaded
    this.bytesTotal = bytesTotal

    const params: GroupProgressHandlerParams = {
      bytesDownloaded,
      bytesTotal,
      completedTasks,
      failedTasks,
      totalTasks: this.tasks.length,
    }
    this.progressHandler?.(params)

    if (this.tasks.length > 0 && completedTasks + failedTasks === this.tasks.length)
      this.doneHandler?.()
  }

  private _onTaskError (task: DownloadTask, params: { error: string, errorCode: number }) {
    const errorParams: GroupErrorHandlerParams = { id: task.id, ...params }
    this.errorHandler?.(errorParams)
  }
}
