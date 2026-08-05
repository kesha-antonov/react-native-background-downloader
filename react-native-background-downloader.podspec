require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

folly_compiler_flags = '-DFOLLY_NO_CONFIG -DFOLLY_MOBILE=1 -DFOLLY_USE_LIBCPP=1 -Wno-comma -Wno-shorten-64-to-32'

Pod::Spec.new do |s|
  s.name         = package['name'].split('/')[1..-1].join('/')
  s.version      = package['version']
  s.summary      = package['description']
  s.description  = package['description']
  s.homepage     = package['repository']['url']
  s.license      = package['license']
  s.platform     = :ios, '15.1'
  s.author       = package['author']
  s.source       = { git: 'https://github.com/kesha-antonov/react-native-background-downloader.git', tag: 'main' }

  s.source_files = 'ios/**/*.{h,m,mm,swift}'
  # React Native Core dependency
  install_modules_dependencies(s)

  # MMKV is used for persistent download state storage on iOS
  # Using MMKV (Objective-C wrapper) which depends on MMKVCore.
  # We only use MMKV's basic key/value APIs (initializeMMKV:, mmkvWithID:,
  # getDataForKey:/setData:forKey:, typed getters/setters) which have been stable
  # since MMKV 1.x, so we require only that minimum. CocoaPods is still free to
  # resolve a newer MMKV when another pod (e.g. react-native-mmkv, which pins an
  # exact MMKVCore) requires one - we just don't impose our own higher floor.
  # See https://github.com/kesha-antonov/react-native-background-downloader/issues/162
  #
  # 2.4.1 is excluded: MMKVCore 2.4.1 fails to compile on Apple platforms with
  # "use of undeclared identifier 'memset_s'" in Core/aes/AESCrypt.cpp. It defines
  # __STDC_WANT_LIB_EXT1__ at the top of the .cpp, but CocoaPods force-includes the
  # generated prefix header (-include ...-prefix.pch) first, so <string.h> is already
  # parsed by then and Darwin never declares memset_s. Only that single release is
  # blocked, so a fixed 2.4.2+ will be picked up without another release here.
  # See https://github.com/Tencent/MMKV/issues/1675
  # and https://github.com/kesha-antonov/react-native-background-downloader/issues/175
  s.dependency 'MMKV', '>= 1.2.0', '!= 2.4.1'
  # MMKVCore must be excluded explicitly as well: the broken source file lives in
  # MMKVCore, and MMKV 2.4.0 depends on 'MMKVCore (~> 2.4.0)', which would still
  # resolve to the broken 2.4.1 on its own.
  s.dependency 'MMKVCore', '!= 2.4.1'

  # Enable codegen for new architecture
  if ENV['RCT_NEW_ARCH_ENABLED'] == '1'
    s.compiler_flags = folly_compiler_flags + " -DRCT_NEW_ARCH_ENABLED=1"
    s.pod_target_xcconfig = {
      "HEADER_SEARCH_PATHS" => "\"$(PODS_ROOT)/boost\"",
      "CLANG_CXX_LANGUAGE_STANDARD" => "c++17"
    }
  end
end
