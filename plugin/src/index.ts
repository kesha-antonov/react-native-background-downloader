import { ConfigPlugin, createRunOncePlugin, WarningAggregator } from '@expo/config-plugins'

const pkg = require('../../package.json') // eslint-disable-line @typescript-eslint/no-require-imports

const withRNBackgroundDownloader: ConfigPlugin<void> = (config) => {
  // Add a warning that this library requires custom development client
  WarningAggregator.addWarningIOS(
    'react-native-background-downloader',
    'react-native-background-downloader uses native libraries that require a custom development client. This will not work with Expo Go.'
  )

  WarningAggregator.addWarningAndroid(
    'react-native-background-downloader',
    'react-native-background-downloader uses native libraries that require a custom development client. This will not work with Expo Go.'
  )

  return config
}

export default createRunOncePlugin(withRNBackgroundDownloader, pkg.name, pkg.version)
