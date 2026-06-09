#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "ToxExt::ToxExt" for configuration "Release"
set_property(TARGET ToxExt::ToxExt APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(ToxExt::ToxExt PROPERTIES
  IMPORTED_LINK_INTERFACE_LANGUAGES_RELEASE "C"
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libtoxext.a"
  )

list(APPEND _cmake_import_check_targets ToxExt::ToxExt )
list(APPEND _cmake_import_check_files_for_ToxExt::ToxExt "${_IMPORT_PREFIX}/lib/libtoxext.a" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
