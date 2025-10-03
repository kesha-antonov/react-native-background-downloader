import { type BeginHandler, type BeginHandlerObject, type DoneHandler, type DoneHandlerObject, type ErrorHandler, type ErrorHandlerObject, type ProgressHandler, type ProgressHandlerObject, type TaskInfo } from './index.d';
export default class DownloadTask {
    id: string;
    state: string;
    metadata: {};
    bytesDownloaded: number;
    bytesTotal: number;
    beginHandler: BeginHandler | undefined;
    progressHandler: ProgressHandler | undefined;
    doneHandler: DoneHandler | undefined;
    errorHandler: ErrorHandler | undefined;
    constructor(taskInfo: TaskInfo, originalTask?: TaskInfo);
    begin(handler: BeginHandler): this;
    progress(handler: ProgressHandler): this;
    done(handler: DoneHandler): this;
    error(handler: ErrorHandler): this;
    onBegin(params: BeginHandlerObject): void;
    onProgress({ bytesDownloaded, bytesTotal }: ProgressHandlerObject): void;
    onDone(params: DoneHandlerObject): void;
    onError(params: ErrorHandlerObject): void;
    pause(): void;
    resume(): void;
    stop(): void;
    tryParseJson(element: any): any;
}
//# sourceMappingURL=DownloadTask.d.ts.map