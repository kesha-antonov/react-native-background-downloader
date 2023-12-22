// Type definitions for @kesha-antonov/react-native-background-downloader 2.6
// Project: https://github.com/kesha-antonov/react-native-background-downloader
// Definitions by: Philip Su <https://github.com/fivecar>,
//                 Adam Hunter <https://github.com/adamrhunter>,
//                 Junseong Park <https://github.com/Kweiza>
// Definitions: https://github.com/DefinitelyTyped/DefinitelyTyped

export interface DownloadHeaders {
  [key: string]: string | null;
}

type SetHeaders = (h: DownloadHeaders) => void;

export interface TaskInfoObject {
  id: string;
  metadata: object | string;

  percent?: number;
  bytesDownloaded?: number;
  bytesTotal?: number;

  beginHandler?: Function;
  progressHandler?: Function;
  doneHandler?: Function;
  errorHandler?: Function;
}
export type TaskInfo = TaskInfoObject;

export interface BeginHandlerObject {
  expectedBytes: number;
  headers: { [key: string]: string };
}

export type BeginHandler = ({
  expectedBytes,
  headers,
}: BeginHandlerObject) => any;
export type ProgressHandler = (
  percent: number,
  bytesDownloaded: number,
  bytesTotal: number
) => any;
export type DoneHandler = () => any;
export type ErrorHandler = (error: any, errorCode: any) => any;
export type DownloadTaskState =
  | "DOWNLOADING"
  | "PAUSED"
  | "DONE"
  | "FAILED"
  | "STOPPED";

export interface DownloadTask {
  constructor: (taskInfo: TaskInfo) => DownloadTask;

  id: string;
  state: DownloadTaskState;
  percent: number;
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

  pause: () => any;
  resume: () => any;
  stop: () => any;
}

export type CheckForExistingDownloads = () => Promise<DownloadTask[]>;
export type EnsureDownloadsAreRunning = () => Promise<void>;

export interface InitDownloaderOptions {
}
export type InitDownloader = (options: InitDownloaderOptions) => undefined;

export interface DownloadOption {
  id: string;
  url: string;
  destination: string;
  headers?: DownloadHeaders | undefined;
  metadata?: object;
  isAllowedOverRoaming?: boolean;
  isAllowedOverMetered?: boolean;
}

export type Download = (options: DownloadOption) => DownloadTask;
export type CompleteHandler = (id: string) => void;

export interface Directories {
  documents: string;
}

export const setHeaders: SetHeaders;
export const checkForExistingDownloads: CheckForExistingDownloads;
export const ensureDownloadsAreRunning: EnsureDownloadsAreRunning;
export const initDownloader: InitDownloader;
export const download: Download;
export const completeHandler: CompleteHandler;
export const directories: Directories;

export interface RNBackgroundDownloader {
  setHeaders: SetHeaders;
  initDownloader: InitDownloader;
  checkForExistingDownloads: CheckForExistingDownloads;
  ensureDownloadsAreRunning: EnsureDownloadsAreRunning;
  download: Download;
  completeHandler: CompleteHandler;
  directories: Directories;
}

declare const RNBackgroundDownloader: RNBackgroundDownloader;
export default RNBackgroundDownloader;
