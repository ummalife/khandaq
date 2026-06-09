project(tox_extension_messages)
cmake_minimum_required(VERSION 2.8.12)


if(COMMAND cmake_policy)
  cmake_policy(SET CMP0003 NEW)
endif(COMMAND cmake_policy)

find_package(ToxExt REQUIRED)

add_library(ToxExtensionMessages tox_extension_messages.c)
target_compile_options(ToxExtensionMessages PRIVATE -Wall -Wextra -Werror -std=gnu11)
target_link_libraries(ToxExtensionMessages ToxExt::ToxExt)
target_include_directories(ToxExtensionMessages PUBLIC "$<INSTALL_INTERFACE:$<INSTALL_PREFIX>/include>")
set_target_properties(ToxExtensionMessages PROPERTIES PUBLIC_HEADER "tox_extension_messages.h")
set_target_properties(ToxExtensionMessages PROPERTIES OUTPUT_NAME "tox_extension_messages")

install(TARGETS ToxExtensionMessages EXPORT ToxExtensionMessagesConfig ARCHIVE DESTINATION lib PUBLIC_HEADER DESTINATION include)
install(EXPORT ToxExtensionMessagesConfig DESTINATION lib/cmake/ToxExtensionMessages NAMESPACE ToxExtensionMessages::)

if (TARGET ToxExt::Mock)
  include (CTest)
  add_subdirectory(test)
else()
  message("ToxExt::Mock not installed. Disabling tests")
endif()

