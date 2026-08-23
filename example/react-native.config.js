const oldArchitecture = process.env.RNBD_IOS_OLD_ARCH === '1'

module.exports = {
  dependencies: oldArchitecture
    ? {
        'react-native-reanimated': { platforms: { ios: null } },
        'react-native-worklets': { platforms: { ios: null } },
      }
    : {},
}
