// Mock for react-native-nitro-modules
// This mock simulates Nitro modules for testing purposes

const createMockHybridObject = (name) => {
  // Return a mock object that implements the Spec interface
  return {
    checkForExistingDownloads: jest.fn().mockResolvedValue([]),
    completeHandler: jest.fn(),
    download: jest.fn(),
    pauseTask: jest.fn(),
    resumeTask: jest.fn(),
    stopTask: jest.fn(),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  }
}

module.exports = {
  createHybridObject: createMockHybridObject,
  HybridObject: jest.fn(),
}
