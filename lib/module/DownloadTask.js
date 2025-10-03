"use strict";

import RNBackgroundDownloader from "./NativeRNBackgroundDownloader.js";
function validateHandler(handler) {
  const type = typeof handler;
  if (type !== 'function') throw new TypeError(`[RNBackgroundDownloader] expected argument to be a function, got: ${type}`);
}
export default class DownloadTask {
  id = '';
  state = 'PENDING';
  metadata = {};
  bytesDownloaded = 0;
  bytesTotal = 0;
  constructor(taskInfo, originalTask) {
    this.id = taskInfo.id;
    this.bytesDownloaded = taskInfo.bytesDownloaded ?? 0;
    this.bytesTotal = taskInfo.bytesTotal ?? 0;
    const metadata = this.tryParseJson(taskInfo.metadata);
    if (metadata) this.metadata = metadata;
    if (originalTask) {
      this.beginHandler = originalTask.beginHandler;
      this.progressHandler = originalTask.progressHandler;
      this.doneHandler = originalTask.doneHandler;
      this.errorHandler = originalTask.errorHandler;
    }
  }
  begin(handler) {
    validateHandler(handler);
    this.beginHandler = handler;
    return this;
  }
  progress(handler) {
    validateHandler(handler);
    this.progressHandler = handler;
    return this;
  }
  done(handler) {
    validateHandler(handler);
    this.doneHandler = handler;
    return this;
  }
  error(handler) {
    validateHandler(handler);
    this.errorHandler = handler;
    return this;
  }
  onBegin(params) {
    this.state = 'DOWNLOADING';
    this.beginHandler?.(params);
  }
  onProgress({
    bytesDownloaded,
    bytesTotal
  }) {
    this.bytesDownloaded = bytesDownloaded;
    this.bytesTotal = bytesTotal;
    this.progressHandler?.({
      bytesDownloaded,
      bytesTotal
    });
  }
  onDone(params) {
    this.state = 'DONE';
    this.bytesDownloaded = params.bytesDownloaded;
    this.bytesTotal = params.bytesTotal;
    this.doneHandler?.(params);
  }
  onError(params) {
    this.state = 'FAILED';
    this.errorHandler?.(params);
  }
  pause() {
    this.state = 'PAUSED';
    RNBackgroundDownloader.pauseTask?.(this.id);
  }
  resume() {
    this.state = 'DOWNLOADING';
    RNBackgroundDownloader.resumeTask?.(this.id);
  }
  stop() {
    this.state = 'STOPPED';
    RNBackgroundDownloader.stopTask?.(this.id);
  }
  tryParseJson(element) {
    try {
      if (typeof element === 'string') element = JSON.parse(element);
      return element;
    } catch (e) {
      console.warn('DownloadTask tryParseJson', e);
      return null;
    }
  }
}
//# sourceMappingURL=DownloadTask.js.map