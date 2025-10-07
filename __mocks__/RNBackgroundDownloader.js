/* eslint-disable */

import { NativeModules } from 'react-native';

// Create a shared mock instance for Nitro modules
const mockNitroInstance = {
  addListener: jest.fn(),
  removeListeners: jest.fn(),
  download: jest.fn(),
  pauseTask: jest.fn(),
  resumeTask: jest.fn(),
  stopTask: jest.fn(),
  completeHandler: jest.fn(),
  checkForExistingDownloads: jest.fn().mockImplementation(() => {
    const foundDownloads = [
      {
        id: 'taskRunning',
        state: 0, // TaskRunning
        bytesDownloaded: 50,
        bytesTotal: 100
      },
      {
        id: 'taskPaused',
        state: 1, // TaskSuspended
        bytesDownloaded: 70,
        bytesTotal: 100
      },
      {
        id: 'taskCancelled',
        state: 2, // TaskCanceling
        bytesDownloaded: 90,
        bytesTotal: 100
      },
      {
        id: 'taskCompletedExplicit',
        state: 3, // TaskCompleted
        bytesDownloaded: 100,
        bytesTotal: 100
      },
      {
        id: 'taskCompletedImplicit',
        state: 3, // TaskCompleted
        bytesDownloaded: 100,
        bytesTotal: 100
      },
      {
        id: 'taskFailed',
        state: 3, // TaskCompleted
        bytesDownloaded: 90,
        bytesTotal: 100
      }
    ];
    return Promise.resolve(foundDownloads);
  }),
};

// Mock react-native-nitro-modules
jest.mock('react-native-nitro-modules', () => ({
  createHybridObject: jest.fn(() => mockNitroInstance),
  HybridObject: jest.fn(),
}), { virtual: true });

// states:
// 0 - Running
// 1 - Suspended / Paused
// 2 - Cancelled / Failed
// 3 - Completed (not necessarily successfully)

// Keep the old NativeModules mock pointing to the same instance for backwards compatibility with tests
NativeModules.RNBackgroundDownloader = mockNitroInstance;
NativeModules.RNBackgroundDownloader.TaskRunning = 0;
NativeModules.RNBackgroundDownloader.TaskSuspended = 1;
NativeModules.RNBackgroundDownloader.TaskCanceling = 2;
NativeModules.RNBackgroundDownloader.TaskCompleted = 3;
NativeModules.RNBackgroundDownloader.documents = '/tmp/documents';

