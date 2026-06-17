Pod::Spec.new do |s|
  s.name             = 'libopus-static'
  s.version          = '1.3.1'
  s.summary          = 'Opus encoder/decoder library (Khandaq: device+simulator universal static lib)'
  s.homepage         = 'http://opus-codec.org/downloads/'
  s.license          = { :type => 'BSD', :file => 'LICENSE' }
  s.author           = { 'Xiph.Org' => 'opus-codec.org' }
  s.source           = { :path => '.' }
  s.source_files     = 'libopus/*.h'
  s.public_header_files = 'libopus/*.h'
  s.ios.vendored_frameworks = 'libopus.xcframework'
  s.osx.vendored_libraries = 'libopus-macos.a'
  s.ios.deployment_target = '10.0'
  s.osx.deployment_target = '10.13'
  s.pod_target_xcconfig = { 'ENABLE_BITCODE' => 'NO' }
end
