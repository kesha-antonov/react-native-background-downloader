import { create } from 'zustand';
import { DownloadTask } from '@kesha-antonov/react-native-background-downloader';

export type DownloadTasksStateStore = {
  tasks: Record<string, DownloadTask>;
  addTask: (task: DownloadTask) => void;
  removeTask: (task: DownloadTask) => void;

  downloads: Record<string, boolean>;
  updateDownloadStatus: (id: string, status: boolean) => void;

  reset: () => void;
};

export const useDownloadsStateStore = create<DownloadTasksStateStore>(set => ({
  tasks: {},
  addTask: (task: DownloadTask) =>
    set(state => {
      return {
        tasks: {
          ...state.tasks,
          [task.id.toString()]: task,
        },
      };
    }),
  removeTask: (task: DownloadTask) =>
    set(state => {
      delete state.tasks[task.id.toString()];
      return {
        tasks: {
          ...state.tasks,
        },
      };
    }),

  downloads: {},
  updateDownloadStatus: (id: string, status: boolean) =>
    set(state => {
      return {
        downloads: {
          ...state.downloads,
          [id]: status,
        },
      };
    }),

  reset: () =>
    set(() => {
      return {
        tasks: {},
        downloads: {},
      };
    }),
}));
