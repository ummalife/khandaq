if (COMMAND cmake_policy)
	cmake_policy(SET CMP0003 NEW)
endif()

include(CTest)

set(REPORT_COVERAGE OFF CACHE BOOL "Generate coverage reports")

# I'm sure I could do this better but I don't want to spend my time on cmake really
function(tox_extension_messages_test test_name)
	add_executable(${test_name} ${ARGN})
	# we use gnu11 for the tests so we can use the container_of macro for inheritance a la linux
	# Force _DEBUG for tests since we use the assert macro for verification
if(REPORT_COVERAGE)
	target_compile_options(${test_name} PRIVATE -Wall -Wextra -Werror -std=gnu11 -ftest-coverage -fprofile-arcs -D_DEBUG -UNDEBUG)
	target_link_libraries(${test_name} gcov ToxExt::Mock)
else()
	target_compile_options(${test_name} PRIVATE -Wall -Wextra -Werror -std=gnu11 -D_DEBUG -UNDEBUG)
	target_link_libraries(${test_name} ToxExt::Mock)
endif()
	target_include_directories(${test_name} PRIVATE "${TOXCORE_INCLUDEDIR}")
	add_test(${test_name} ${test_name})
endfunction(tox_extension_messages_test)

tox_extension_messages_test(sanity_test sanity_test.c)
tox_extension_messages_test(max_message_test max_message_test.c)
