#   Copyright © 2019 by The qTox Project Contributors
#
#   This file is part of qTox, a Qt-based graphical interface for Tox.
#   qTox is libre software: you can redistribute it and/or modify
#   it under the terms of the GNU General Public License as published by
#   the Free Software Foundation, either version 3 of the License, or
#   (at your option) any later version.
#
#   qTox is distributed in the hope that it will be useful,
#   but WITHOUT ANY WARRANTY; without even the implied warranty of
#   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#   GNU General Public License for more details.
#
#   You should have received a copy of the GNU General Public License
#   along with qTox.  If not, see <http://www.gnu.org/licenses/>

################################################################################
#
# :: Testing
#
################################################################################

enable_testing()

function(auto_test subsystem module extra_res extra_libs)
  add_executable(test_${module}
    test/${subsystem}/${module}_test.cpp ${extra_res})
  target_link_libraries(test_${module}
    ${PROJECT_NAME}_static
    ${CHECK_LIBRARIES}
    Qt5::Test
    ${extra_libs})
  add_test(
    NAME test_${module}
    COMMAND ${TEST_CROSSCOMPILING_EMULATOR} test_${module})
endfunction()

add_subdirectory(test/mock)
add_subdirectory(test/dbutility)

auto_test(core core "${${PROJECT_NAME}_RESOURCES}" "mock_library")
auto_test(core chatid "" "")
auto_test(core toxid "" "")
auto_test(core toxstring "" "")
auto_test(core ngchistsig "" "")
auto_test(core networkdiagnostics "" "")
auto_test(core fileprogress "" "")
auto_test(chatlog textformatter "" "")
auto_test(net bsu "${${PROJECT_NAME}_RESOURCES}" "") # needs nodes list
auto_test(chatlog chatlinestorage "" "")
auto_test(chatlog chatlinecontentproxy "" "")
auto_test(persistence paths "" "")
auto_test(persistence dbschema "" "dbutility_library")
auto_test(persistence/dbupgrade dbTo11 "" "dbutility_library")
auto_test(persistence offlinemsgengine "" "")
auto_test(persistence outbox "" "")
auto_test(persistence profilefile "" "")
auto_test(persistence profileremove "" "")
if(NOT "${SMILEYS}" STREQUAL "DISABLED")
  auto_test(persistence smileypack "${SMILEY_RESOURCES}" "") # needs emojione
endif()
auto_test(model airconversation "" "")
auto_test(model airgroup "" "")
auto_test(model airgroupleave "" "")
auto_test(model airmodel "" "")
auto_test(model airnetwork "" "")
auto_test(model airmedia "" "")
auto_test(model airmessagemarks "" "")
auto_test(model airpreferences "" "")
auto_test(model airstart "" "")
auto_test(model airvoicedecoder "" "")
auto_test(model airyou "" "")
# The generated design files (airtokens.h, icons, fonts, palettes) must match design/system.
find_package(Python3 COMPONENTS Interpreter QUIET)
if(Python3_Interpreter_FOUND AND EXISTS "${CMAKE_SOURCE_DIR}/../design/system/tokens.json")
  add_test(NAME test_design_tokens
    COMMAND ${Python3_EXECUTABLE} ${CMAKE_SOURCE_DIR}/tools/gen-design-tokens.py --check)
endif()
auto_test(model friendlistmanager "" "")
auto_test(model friendmessagedispatcher "" "")
auto_test(model groupmessagedispatcher "" "mock_library")
auto_test(model messageprocessor "" "")
auto_test(model sessionchatlog "" "")
auto_test(model notes "" "")
auto_test(model exiftransform "" "")
auto_test(model notificationgenerator "" "mock_library")
auto_test(widget filesform "" "")
auto_test(widget airdossier "" "")
auto_test(widget airlistview "res/air.qrc" "") # the Plex faces
auto_test(widget airnewcontact "" "")
auto_test(widget airgroupviews "" "")
auto_test(widget airoverlay "" "")
auto_test(widget airwidgets "" "")
auto_test(widget airtray "res/air.qrc" "") # the Plex faces
auto_test(widget airmediaparts "" "")
auto_test(widget airyoupage "" "")
auto_test(widget chatquotepreview "res/air.qrc" "") # the x icon
auto_test(widget removechatdialog "" "mock_library")
auto_test(widget chattextedit "" "")

if (UNIX)
  auto_test(platform posixsignalnotifier "" "")
endif()
