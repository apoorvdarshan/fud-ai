Pod::Spec.new do |s|
  s.name           = 'NativeStorage'
  s.version        = '1.0.0'
  s.summary        = 'Read-only bridge to native Fud AI UserDefaults.'
  s.description    = 'Reads UserDefaults.standard JSON blobs and prefs so Expo can migrate an existing store install.'
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
