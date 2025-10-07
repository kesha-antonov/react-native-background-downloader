// Mock for react-native-nitro-modules
// This is required when the module is not installed

module.exports = {
  createHybridObject: jest.fn(() => {
    throw new Error('Nitro modules not available')
  }),
  HybridObject: jest.fn(),
}
