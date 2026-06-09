Pod::Spec.new do |s|
  s.name             = 'libopus-static'
  s.version          = '1.3.1'
  s.summary          = 'Opus encoder/decoder library (Khandaq: device+simulator universal static lib)'
  s.homepage         = 'http://opus-codec.org/downloads/'
  s.license          = { :type => 'BSD', :file => 'LICENSE' }
  s.author           = { 'Xiph.Org' => 'opus-codec.org' }
  s.platform         = :ios, '10.0'
  s.source           = { :path => '.' }
  s.source_files     = 'libopus/*.h'
  s.public_header_files = 'libopus/*.h'
  s.vendored_frameworks = 'libopus.xcframework'
  s.pod_target_xcconfig = { 'ENABLE_BITCODE' => 'NO' }
end
