Pod::Spec.new do |s|
  s.name           = 'CompanionSnapshot'
  s.version        = '1.0.0'
  s.summary        = 'Writes WidgetSnapshot to the Fud AI App Group.'
  s.description    = 'Mirrors WidgetSnapshotWriter / WatchSnapshotSync: App Group file + UserDefaults + WidgetKit reload + WatchConnectivity.'
  s.author         = 'Apoorv Darshan'
  s.homepage       = 'https://github.com/apoorvdarshan/fud-ai'
  s.license        = { :type => 'MIT' }
  s.platforms      = { :ios => '15.1' }
  s.source         = { :git => '' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.frameworks = 'WidgetKit', 'WatchConnectivity'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }
  s.source_files = '**/*.{h,m,mm,swift}'
end
