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

  s.dependency 'MMKV', '>= 1.0.0'

  # Enable codegen for new architecture
  if ENV['RCT_NEW_ARCH_ENABLED'] == '1'
    s.compiler_flags = folly_compiler_flags + " -DRCT_NEW_ARCH_ENABLED=1"
    s.pod_target_xcconfig = {
      "HEADER_SEARCH_PATHS" => "\"$(PODS_ROOT)/boost\"",
      "CLANG_CXX_LANGUAGE_STANDARD" => "c++17"
    }
  end
end
