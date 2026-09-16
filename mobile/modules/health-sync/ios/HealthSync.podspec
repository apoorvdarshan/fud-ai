Pod::Spec.new do |s|
  s.name           = 'HealthSync'
  s.version        = '1.0.0'
  s.summary        = 'HealthKit bridge for the Fud AI React Native app.'
  s.description    = 'Mirrors HealthKitManager.swift: authorization, nutrition, weight, body fat, steps.'
  s.author         = 'Apoorv Darshan'
  s.homepage       = 'https://github.com/apoorvdarshan/fud-ai'
  s.license        = { :type => 'MIT' }
  s.platforms      = { :ios => '15.1' }
  s.source         = { :git => '' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  s.frameworks = 'HealthKit'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }
  s.source_files = '**/*.{h,m,mm,swift}'
end
