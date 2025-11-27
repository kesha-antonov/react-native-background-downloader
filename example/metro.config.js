const { getDefaultConfig } = require('expo/metro-config')
const path = require('path')

// Get the root directory of the library
const libraryRoot = path.resolve(__dirname, '..')

const config = getDefaultConfig(__dirname)

// Watch both the example and the library source
config.watchFolders = [libraryRoot]

// Make sure Metro can resolve modules from both the example and the library
config.resolver.nodeModulesPaths = [
  path.resolve(__dirname, 'node_modules'),
  path.resolve(libraryRoot, 'node_modules'),
]

// Extra node_modules to look for
config.resolver.extraNodeModules = {
  '@kesha-antonov/react-native-background-downloader': libraryRoot,
}

module.exports = config
