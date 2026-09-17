Pod::Spec.new do |s|
  s.name           = 'NativeMenu'
  s.version        = '1.0.0'
  s.summary        = 'System UIMenu attached to a view for the Fud AI React Native app (iOS).'
  s.description    = 'Wraps UIButton.menu / UIMenu so the Home + button and row actions use the system menu instead of a bottom sheet.'
  s.author         = 'Apoorv Darshan'
  s.homepage       = 'https://github.com/apoorvdarshan/fud-ai'
  s.license        = { :type => 'MIT' }
  s.platforms      = { :ios => '15.1' }
  s.source         = { :git => '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }

  s.source_files = '**/*.{h,m,mm,swift}'
end
