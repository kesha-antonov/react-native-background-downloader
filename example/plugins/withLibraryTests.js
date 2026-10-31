const { withPodfile } = require('@expo/config-plugins')

// Declares the library pod with its unit tests (the podspec's `Tests` test spec).
// Autolinking skips a pod the Podfile already declares, so this replaces its entry.
const POD_LINE = "  pod 'react-native-background-downloader', :path => '../..', :testspecs => ['Tests']"

module.exports = config => withPodfile(config, config => {
  config.modResults.contents = config.modResults.contents
    // Prebuild can reuse an existing Podfile, with this or an older line in it
    .replace(/^ *pod 'react-native-background-downloader'.*\n\n?/gm, '')
    .replace(/^(\s*config = use_native_modules!)/m, `${POD_LINE}\n\n$1`)
  return config
})
