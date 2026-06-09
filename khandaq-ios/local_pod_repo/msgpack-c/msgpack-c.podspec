#
# Be sure to run `pod lib lint toxcore.podspec' to ensure this is a
# valid spec and remove all comments before submitting the spec.
#
# Any lines starting with a # are optional, but encouraged
#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#

Pod::Spec.new do |s|
  s.name             = "msgpack-c"
  s.version          = "4.0.0"
  s.summary          = "Cocoapods wrapper for msgpack-c"
  s.homepage         = "https://github.com/Zoxcore/msgpack-c"
  s.license          = 'GPLv3'
  s.author           = "Zoff"
  s.source           = {
      :git => "https://github.com/Zoxcore/msgpack-c.git",
      :tag => s.version.to_s,
      :submodules => true
  }

  s.pod_target_xcconfig = { 'ENABLE_BITCODE' => 'NO', 'OTHER_LDFLAGS' => '-read_only_relocs suppress' }

  s.ios.deployment_target = '8.0'
  s.requires_arc = true

  # Preserve the layout of headers in the msgpack-c directory
  #s.header_mappings_dir = 'msgpack-c/include'

  s.source_files = 'msgpack-c/include/*h', 'msgpack-c/include/msgpack/*h', 'msgpack-c/src/*.c'
  #s.public_header_files = 'msgpack-c/include/*.h', 'msgpack-c/include/msgpack/*.h'
  #s.xcconfig = { 'FRAMEWORK_SEARCH_PATHS' => '"${PODS_ROOT}"'}

end
