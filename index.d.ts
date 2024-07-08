// Type definitions for @kesha-antonov/react-native-background-downloader 2.6
// Project: https://github.com/kesha-antonov/react-native-background-downloader
// Definitions by: Philip Su <https://github.com/fivecar>,
//                 Adam Hunter <https://github.com/adamrhunter>,
//                 Junseong Park <https://github.com/Kweiza>
// Definitions: https://github.com/DefinitelyTyped/DefinitelyTyped

export interface DownloadHeaders {
  [key: string]: string | null;
}

export interface Config {
  headers: DownloadHeaders,
  progressInterval: number,
  isLogsEnabled: boolean
}

type SetConfig = (config: Partial<Config>) => void;

export interface BeginHandlerObject {
  expectedBytes: number;
  headers: { [key: string]: string };
}
export type BeginHandler = ({
  expectedBytes,
  headers,
}: BeginHandlerObject) => void;

export interface ProgressHandlerObject {
  bytesDownloaded: number
  bytesTotal: number
}
export type ProgressHandler = ({
  bytesDownloaded,
  bytesTotal,
}: ProgressHandlerObject) => void;

export interface DoneHandlerObject {
  bytesDownloaded: number
  bytesTotal: number
}
export type DoneHandler = ({
  bytesDownloaded,
  bytesTotal,
}: DoneHandlerObject) => void;

export interface ErrorHandlerObject {
  error: string
  errorCode: number
}
export type ErrorHandler = ({
  error,
  errorCode,
}: ErrorHandlerObject) => void;

export interface TaskInfoObject {
  id: string;
  metadata: object | string;

  bytesDownloaded?: number;
  bytesTotal?: number;

  beginHandler?: BeginHandler;
  progressHandler?: ProgressHandler;
  doneHandler?: DoneHandler;
  errorHandler?: ErrorHandler;
}
export type TaskInfo = TaskInfoObject;

export type DownloadTaskState =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED';

export interface DownloadTask {
  constructor: (taskInfo: TaskInfo) => DownloadTask;

  id: string;
  state: DownloadTaskState;
  metadata: Record<string, any>;
  bytesDownloaded: number;
  bytesTotal: number;

  begin: (handler: BeginHandler) => DownloadTask;
  progress: (handler: ProgressHandler) => DownloadTask;
  done: (handler: DoneHandler) => DownloadTask;
  error: (handler: ErrorHandler) => DownloadTask;

  _beginHandler: BeginHandler;
  _progressHandler: ProgressHandler;
  _doneHandler: DoneHandler;
  _errorHandler: ErrorHandler;

  pause: () => void;
  resume: () => void;
  stop: () => void;
}

export type CheckForExistingDownloads = () => Promise<DownloadTask[]>;
export type EnsureDownloadsAreRunning = () => Promise<void>;

export interface DownloadOption {
  id: string;
  url: string;
  destination: string;
  headers?: DownloadHeaders | undefined;
  metadata?: object;
  isAllowedOverRoaming?: boolean;
  isAllowedOverMetered?: boolean;
  isNotificationVisible?: boolean;
}

export type Download = (options: DownloadOption) => DownloadTask;
export type CompleteHandler = (id: string) => void;

export interface Directories {
  documents: string;
}

export const setConfig: SetConfig
export const checkForExistingDownloads: CheckForExistingDownloads
export const ensureDownloadsAreRunning: EnsureDownloadsAreRunning
export const download: Download
export const completeHandler: CompleteHandler
export const directories: Directories

export interface RNBackgroundDownloader {
  setConfig: SetConfig;
  checkForExistingDownloads: CheckForExistingDownloads;
  ensureDownloadsAreRunning: EnsureDownloadsAreRunning;
  download: Download;
  completeHandler: CompleteHandler;
  directories: Directories;
}

declare const RNBackgroundDownloader: RNBackgroundDownloader
export default RNBackgroundDownloader
