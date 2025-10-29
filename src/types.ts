// Type definitions for @kesha-antonov/react-native-background-downloader 2.6
// Project: https://github.com/kesha-antonov/react-native-background-downloader
// Definitions by: Philip Su <https://github.com/fivecar>,
//                 Adam Hunter <https://github.com/adamrhunter>,
//                 Junseong Park <https://github.com/Kweiza>
// Definitions: https://github.com/DefinitelyTyped/DefinitelyTyped
import type DownloadTask from './DownloadTask'

export interface DownloadHeaders {
  [key: string]: string | null;
}

export interface Config {
  headers: DownloadHeaders,
  progressInterval: number,
  isLogsEnabled: boolean
}

export type DownloadTaskState =
  | 'PENDING'
  | 'DOWNLOADING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'STOPPED';

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

export interface StateHandlerObject {
  oldState: DownloadTaskState
  newState: DownloadTaskState
}
export type StateHandler = ({
  oldState,
  newState,
}: StateHandlerObject) => void;

export interface TaskInfoObject {
  id: string;
  metadata: object | string;

  bytesDownloaded?: number;
  bytesTotal?: number;
  errorCode?: number | null;
  state?: number | null

  beginHandler?: BeginHandler;
  progressHandler?: ProgressHandler;
  doneHandler?: DoneHandler;
  errorHandler?: ErrorHandler;
  stateHandler?: StateHandler;
}
export type TaskInfo = TaskInfoObject;

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
  notificationTitle?: string;
}

export type Download = (options: DownloadOption) => DownloadTask;
export type CompleteHandler = (id: string) => void;

export interface Directories {
  documents: string;
}

export interface RNBackgroundDownloader {
  setConfig: (config: Partial<Config>) => void;
  checkForExistingDownloads: CheckForExistingDownloads;
  ensureDownloadsAreRunning: EnsureDownloadsAreRunning;
  download: Download;
  completeHandler: CompleteHandler;
  directories: Directories;
}

declare const RNBackgroundDownloader: RNBackgroundDownloader
export default RNBackgroundDownloader
