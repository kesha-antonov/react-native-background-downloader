/* eslint-disable */

import { NativeModules, TurboModuleRegistry } from 'react-native';

// states:
// 0 - Running
// 1 - Suspended / Paused
// 2 - Cancelled / Failed
// 3 - Completed (not necessarily successfully)

// Store event callbacks so tests can trigger them
const eventCallbacks = {
    downloadBegin: null,
    downloadProgress: null,
    downloadComplete: null,
    downloadFailed: null,
};

const mockModule = {
    addListener: jest.fn(),
    removeListeners: jest.fn(),
    download: jest.fn(),
    pauseTask: jest.fn(),
    resumeTask: jest.fn(),
    stopTask: jest.fn(),
    setLogsEnabled: jest.fn(),
    setMaxParallelDownloads: jest.fn(),
    setAllowsCellularAccess: jest.fn(),
    TaskRunning: 0,
    TaskSuspended: 1,
    TaskCanceling: 2,
    TaskCompleted: 3,
    getConstants: jest.fn().mockReturnValue({
        documents: '/tmp/documents',
        TaskRunning: 0,
        TaskSuspended: 1,
        TaskCanceling: 2,
        TaskCompleted: 3,
    }),
    getExistingDownloadTasks: jest.fn().mockImplementation(() => {
        const foundDownloads = [
            {
                id: 'taskRunning',
                metadata: '{}',
                state: 0, // TaskRunning
                bytesDownloaded: 50,
                bytesTotal: 100
            },
            {
                id: 'taskPaused',
                metadata: '{}',
                state: 1, // TaskSuspended
                bytesDownloaded: 70,
                bytesTotal: 100
            },
            {
                id: 'taskCancelled',
                metadata: '{}',
                state: 2, // TaskCanceling
                bytesDownloaded: 90,
                bytesTotal: 100
            },
            {
                id: 'taskCompletedExplicit',
                metadata: '{}',
                state: 3, // TaskCompleted
                bytesDownloaded: 100,
                bytesTotal: 100
            },
            {
                id: 'taskCompletedImplicit',
                metadata: '{}',
                state: 3, // TaskCompleted
                bytesDownloaded: 100,
                bytesTotal: 100
            },
            {
                id: 'taskFailed',
                metadata: '{}',
                state: 3, // TaskCompleted
                bytesDownloaded: 90,
                bytesTotal: 100
            }
        ]
        return Promise.resolve(foundDownloads);
    }),
    completeHandler: jest.fn(),
    documents: '/tmp/documents',
    // Event emitter methods for new architecture - store callbacks
    onDownloadBegin: jest.fn().mockImplementation((callback) => {
        eventCallbacks.downloadBegin = callback;
        return { remove: jest.fn() };
    }),
    onDownloadProgress: jest.fn().mockImplementation((callback) => {
        eventCallbacks.downloadProgress = callback;
        return { remove: jest.fn() };
    }),
    onDownloadComplete: jest.fn().mockImplementation((callback) => {
        eventCallbacks.downloadComplete = callback;
        return { remove: jest.fn() };
    }),
    onDownloadFailed: jest.fn().mockImplementation((callback) => {
        eventCallbacks.downloadFailed = callback;
        return { remove: jest.fn() };
    }),
};

// Mock TurboModuleRegistry.get to return our mock module
jest.spyOn(TurboModuleRegistry, 'get').mockReturnValue(mockModule);

// Also set up NativeModules for fallback
NativeModules.RNBackgroundDownloader = mockModule;

// Export helper to trigger events in tests
export const emitEvent = (eventName, data) => {
    const callback = eventCallbacks[eventName];
    if (callback) {
        callback(data);
    }
};

// Also make emitEvent available globally for tests
global.__RNBackgroundDownloaderEmitEvent = emitEvent;
