import { NativeEventEmitter } from 'react-native';
import DownloadTask from './DownloadTask';
import RNBackgroundDownloader, {
  Constants,
} from './NativeRNBackgroundDownloader';
import {
  type BeginListenerObject,
  type DoneListenerObject,
  type DownloadOptions,
  type ErrorListenerObject,
  type SetConfigParams,
} from './index.d';

const MIN_PROGRESS_INTERVAL = 250;
const tasksMap = new Map();

const config = {
  headers: {},
  progressInterval: 1000,
  progressMinBytes: 1024 * 1024, // 1MB default
  isLogsEnabled: false,
};

function log(...args: any[]) {
  if (config.isLogsEnabled) console.log('[RNBackgroundDownloader]', ...args);
}

const RNBackgroundDownloaderEmitter = new NativeEventEmitter();

RNBackgroundDownloaderEmitter.addListener('downloadBegin', (event) => {
  const { id, ...rest } = event as BeginListenerObject;
  log('[RNBackgroundDownloader] downloadBegin', id, rest);
  const task = tasksMap.get(id);
  task?.onBegin(rest);
});

RNBackgroundDownloaderEmitter.addListener('downloadProgress', (events) => {
  log('[RNBackgroundDownloader] downloadProgress-1', events, tasksMap);
  const eventsArray = Array.isArray(events) ? events : [];
  for (const event of eventsArray) {
    const { id, ...rest } = event;
    const task = tasksMap.get(id);
    log('[RNBackgroundDownloader] downloadProgress-2', id, task);
    task?.onProgress(rest);
  }
});

RNBackgroundDownloaderEmitter.addListener('downloadComplete', (event) => {
  const { id, ...rest } = event as DoneListenerObject;
  log('[RNBackgroundDownloader] downloadComplete', id, rest);
  const task = tasksMap.get(id);
  task?.onDone(rest);

  tasksMap.delete(id);
});

RNBackgroundDownloaderEmitter.addListener('downloadFailed', (event) => {
  const { id, ...rest } = event as ErrorListenerObject;
  log('[RNBackgroundDownloader] downloadFailed', id, rest);
  const task = tasksMap.get(id);
  task?.onError(rest);

  tasksMap.delete(id);
});

export function setConfig({
  headers,
  progressInterval,
  progressMinBytes,
  isLogsEnabled,
}: SetConfigParams) {
  if (typeof headers === 'object') config.headers = headers;

  if (progressInterval != null)
    if (
      typeof progressInterval === 'number' &&
      progressInterval >= MIN_PROGRESS_INTERVAL
    )
      config.progressInterval = progressInterval;
    else
      console.warn(
        `[RNBackgroundDownloader] progressInterval must be a number >= ${MIN_PROGRESS_INTERVAL}. You passed ${progressInterval}`
      );

  if (progressMinBytes != null)
    if (typeof progressMinBytes === 'number' && progressMinBytes >= 0)
      config.progressMinBytes = progressMinBytes;
    else
      console.warn(
        `[RNBackgroundDownloader] progressMinBytes must be a number >= 0. You passed ${progressMinBytes}`
      );

  if (typeof isLogsEnabled === 'boolean') config.isLogsEnabled = isLogsEnabled;
}

export async function checkForExistingDownloads() {
  log('[RNBackgroundDownloader] checkForExistingDownloads-1');

  // Validate that the native module is available
  if (!RNBackgroundDownloader) {
    console.warn(
      '[RNBackgroundDownloader] Native module not available, returning empty array'
    );
    return [];
  }

  if (typeof RNBackgroundDownloader.checkForExistingDownloads !== 'function') {
    console.warn(
      '[RNBackgroundDownloader] checkForExistingDownloads method not available on native module, returning empty array'
    );
    return [];
  }

  try {
    const foundTasks = await RNBackgroundDownloader.checkForExistingDownloads();
    log('[RNBackgroundDownloader] checkForExistingDownloads-2', foundTasks);

    // Ensure foundTasks is an array
    if (!Array.isArray(foundTasks)) {
      console.warn(
        '[RNBackgroundDownloader] checkForExistingDownloads returned non-array, returning empty array:',
        foundTasks
      );
      return [];
    }

    return foundTasks
      .map((taskInfo) => {
        // SECOND ARGUMENT RE-ASSIGNS EVENT HANDLERS
        const task = new DownloadTask(taskInfo, tasksMap.get(taskInfo.id));
        log('[RNBackgroundDownloader] checkForExistingDownloads-3', taskInfo);

        if (taskInfo.savedTaskState === Constants.TaskRunning) {
          task.state = 'DOWNLOADING';
        } else if (taskInfo.savedTaskState === Constants.TaskSuspended) {
          task.state = 'PAUSED';
        } else if (taskInfo.savedTaskState === Constants.TaskCanceling) {
          task.stop();
          return null;
        } else if (taskInfo.savedTaskState === Constants.TaskCompleted) {
          if (taskInfo.bytesDownloaded === taskInfo.bytesTotal)
            task.state = 'DONE';
          else
            // IOS completed the download but it was not done.
            return null;
        }
        tasksMap.set(taskInfo.id, task);
        return task;
      })
      .filter((task) => !!task);
  } catch (error) {
    console.error(
      '[RNBackgroundDownloader] Error in checkForExistingDownloads:',
      error
    );
    return [];
  }
}

export async function ensureDownloadsAreRunning() {
  log('[RNBackgroundDownloader] ensureDownloadsAreRunning');
  const tasks = await checkForExistingDownloads();
  for (const task of tasks)
    if (task.state === 'DOWNLOADING') {
      task.pause();
      task.resume();
    }
}

export function completeHandler(jobId: string) {
  if (jobId == null) {
    console.warn('[RNBackgroundDownloader] completeHandler: jobId is empty');
    return;
  }

  if (!RNBackgroundDownloader) {
    console.warn(
      '[RNBackgroundDownloader] Module not available for completeHandler'
    );
    return;
  }

  if (typeof RNBackgroundDownloader.completeHandler !== 'function') {
    console.warn(
      '[RNBackgroundDownloader] completeHandler method not available on native module'
    );
    return;
  }

  try {
    RNBackgroundDownloader.completeHandler(jobId);
  } catch (error) {
    console.error('[RNBackgroundDownloader] Error in completeHandler:', error);
  }
}

export function download(options: DownloadOptions) {
  log('[RNBackgroundDownloader] download', options);
  if (!options.id || !options.url || !options.destination)
    throw new Error(
      '[RNBackgroundDownloader] id, url and destination are required'
    );

  options.headers = { ...config.headers, ...options.headers };

  if (!(options.metadata && typeof options.metadata === 'object'))
    options.metadata = {};

  options.destination = options.destination.replace('file://', '');

  if (options.isAllowedOverRoaming == null) options.isAllowedOverRoaming = true;
  if (options.isAllowedOverMetered == null) options.isAllowedOverMetered = true;
  if (options.isNotificationVisible == null)
    options.isNotificationVisible = false;

  const task = new DownloadTask({
    id: options.id,
    metadata: options.metadata,
  });
  tasksMap.set(options.id, task);

  if (!RNBackgroundDownloader) {
    console.error('[RNBackgroundDownloader] Module not available for download');
    task.onError({ error: 'Module not available' });
    return task;
  }

  if (typeof RNBackgroundDownloader.download !== 'function') {
    console.error(
      '[RNBackgroundDownloader] download method not available on native module'
    );
    task.onError({ error: 'Download method not available' });
    return task;
  }

  try {
    RNBackgroundDownloader.download({
      ...options,
      metadata: JSON.stringify(options.metadata),
      progressInterval: config.progressInterval,
      progressMinBytes: config.progressMinBytes,
    });
  } catch (error) {
    console.error('[RNBackgroundDownloader] Error in download:', error);
    task.onError({ error: error.message || 'Download failed to start' });
  }

  return task;
}

export const directories = {
  documents: Constants?.documents || '/tmp/documents',
};

export const storageInfo = {
  isMMKVAvailable: Constants?.isMMKVAvailable || false,
  storageType: Constants?.storageType || 'Unknown',
};

export default {
  download,
  checkForExistingDownloads,
  ensureDownloadsAreRunning,
  completeHandler,

  setConfig,

  directories,
  storageInfo,

  DownloadTask,
};
