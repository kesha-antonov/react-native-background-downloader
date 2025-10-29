import { useCallback, useEffect, useState } from 'react';
import {
  DownloadTask,
  DownloadTaskState,
  completeHandler,
} from '@kesha-antonov/react-native-background-downloader';

import { doesItemExist, DownloadItem } from '../utils';
import { useDownloadsStateStore } from '../utils';

type Progress = {
  progress?: number;
  total?: number;
};

export const useDownloadForItem = (item: DownloadItem) => {
  const [progress, setProgress] = useState<Progress>({});
  const [status, setStatus] = useState<DownloadTaskState | undefined>();

  const isDownloaded: boolean = useDownloadsStateStore(state => {
    return state.downloads[item.id] || false;
  });

  const task: DownloadTask | undefined = useDownloadsStateStore(state => {
    return state.tasks[item.id];
  });

  const checkDownload = useCallback(() => {
    doesItemExist(item.id).then(r => {
      useDownloadsStateStore.getState().updateDownloadStatus(item.id, !!r);
    });
  }, [item.id]);

  useEffect(() => {
    checkDownload();

    if (!task) return;

    task
      .begin(({ expectedBytes, headers }) => {
        console.log('task: begin', {
          id: task.id,
          expectedBytes,
          headers,
        });
        setStatus(task.state);
      })
      .progress(({ bytesDownloaded, bytesTotal }) => {
        console.log('task: progress', {
          id: task.id,
          bytesDownloaded,
          bytesTotal,
        });
        setProgress({
          progress: bytesDownloaded,
          total: bytesTotal,
        });
      })
      .stateChange(({ newState }) => {
        setStatus(newState);
      })
      .done(() => {
        console.log('task: done', { id: task.id });
        setStatus(task.state);
        completeHandler(task.id);
        useDownloadsStateStore.getState().removeTask(task);
      })
      .error(e => {
        console.error('task: error', {
          id: task.id,
          e,
        });
        setStatus(task.state);
        useDownloadsStateStore.getState().removeTask(task);
        completeHandler(task.id);
      });
  }, [task, task?.id, item.id, checkDownload]);

  return {
    task,
    isDownloaded,
    progress,
    status,
    isEnded: ['STOPPED', 'DONE', 'FAILED'].includes(status || ''),
    checkDownload,
  };
};
