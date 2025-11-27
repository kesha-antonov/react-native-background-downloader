/* eslint-disable */

// Mock for NativeEventEmitter used in tests
module.exports = jest.fn().mockImplementation(() => ({
  addListener: jest.fn(),
  removeListeners: jest.fn(),
  emit: jest.fn(),
  removeAllListeners: jest.fn(),
}));
