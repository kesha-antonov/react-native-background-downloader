const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config')
const path = require('path')

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  watchFolders: [path.resolve(__dirname, '..')],
  resolver: {
    extraNodeModules: {
      '@kesha-antonov/react-native-background-downloader': path.resolve(__dirname, '../src'),
    },
  },
}

module.exports = mergeConfig(getDefaultConfig(__dirname), config)
