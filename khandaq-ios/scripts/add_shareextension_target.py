#!/usr/bin/env python3
"""Add shareextension target to Antidote.xcodeproj/project.pbxproj"""

from pathlib import Path

PBX = Path(__file__).resolve().parents[1] / "Antidote.xcodeproj" / "project.pbxproj"
text = PBX.read_text()

if "shareextension.appex" in text:
    print("shareextension target already present")
    raise SystemExit(0)

insertions = [
    (
        "/* Begin PBXBuildFile section */",
        "/* Begin PBXBuildFile section */\n"
        "\t\tKHSHREXT017A1B2C3D4E5F6 /* shareextension.appex in Embed App Extensions */ = {isa = PBXBuildFile; fileRef = KHSHREXT002A1B2C3D4E5F6 /* shareextension.appex */; settings = {ATTRIBUTES = (RemoveHeadersOnCopy, ); }; };\n"
        "\t\tKHSHREXT004A1B2C3D4E5F6 /* ShareViewController.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHSHREXT003A1B2C3D4E5F6 /* ShareViewController.swift */; };\n"
        "\t\tKHSHREXT005A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */; };\n"
        "\t\tKHSHRINBOX002A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */; };\n"
        "\t\tKHGRPSEL002A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHGRPSEL001A1B2C3D4E5F6 /* GroupSelectController.swift */; };\n"
        "\t\tKHSHRINBOX004A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */; };\n"
        "\t\tKHGRPSEL003A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHGRPSEL001A1B2C3D4E5F6 /* GroupSelectController.swift */; };\n"
        "\t\tKHSHRINBOX003A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */; };\n"
        "\t\tKHGRPSEL004A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */ = {isa = PBXBuildFile; fileRef = KHGRPSEL001A1B2C3D4E5F6 /* GroupSelectController.swift */; };\n",
    ),
    (
        "\t\tAF2C929F279AB3F10094C08D /* PBXContainerItemProxy */ = {",
        "\t\tKHSHREXT015A1B2C3D4E5F6 /* PBXContainerItemProxy */ = {\n"
        "\t\t\tisa = PBXContainerItemProxy;\n"
        "\t\t\tcontainerPortal = 1164762719794D3300DB20B8 /* Project object */;\n"
        "\t\t\tproxyType = 1;\n"
        "\t\t\tremoteGlobalIDString = KHSHREXT008A1B2C3D4E5F6;\n"
        "\t\t\tremoteInfo = shareextension;\n"
        "\t\t};\n"
        "\t\tAF2C929F279AB3F10094C08D /* PBXContainerItemProxy */ = {",
    ),
    (
        "\t\t\tfiles = (\n\t\t\t\tAF2C92A1279AB3F10094C08D /* pushextension.appex in Embed App Extensions */,",
        "\t\t\tfiles = (\n\t\t\t\tAF2C92A1279AB3F10094C08D /* pushextension.appex in Embed App Extensions */,\n"
        "\t\t\t\tKHSHREXT017A1B2C3D4E5F6 /* shareextension.appex in Embed App Extensions */,",
    ),
    (
        "\t\tAF2C929E279AB3F10094C08D /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; };",
        "\t\tAF2C929E279AB3F10094C08D /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; };\n"
        "\t\tKHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */ = {isa = PBXFileReference; fileEncoding = 4; lastKnownFileType = sourcecode.swift; path = ShareInboxStorage.swift; sourceTree = \"<group>\"; };\n"
        "\t\tKHGRPSEL001A1B2C3D4E5F6 /* GroupSelectController.swift */ = {isa = PBXFileReference; fileEncoding = 4; lastKnownFileType = sourcecode.swift; path = GroupSelectController.swift; sourceTree = \"<group>\"; };\n"
        "\t\tKHSHREXT002A1B2C3D4E5F6 /* shareextension.appex */ = {isa = PBXFileReference; explicitFileType = \"wrapper.app-extension\"; includeInIndex = 0; path = shareextension.appex; sourceTree = BUILT_PRODUCTS_DIR; };\n"
        "\t\tKHSHREXT003A1B2C3D4E5F6 /* ShareViewController.swift */ = {isa = PBXFileReference; fileEncoding = 4; lastKnownFileType = sourcecode.swift; path = ShareViewController.swift; sourceTree = \"<group>\"; };\n"
        "\t\tKHSHREXT006A1B2C3D4E5F6 /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; };\n"
        "\t\tKHSHREXT007A1B2C3D4E5F6 /* shareextension.entitlements */ = {isa = PBXFileReference; lastKnownFileType = text.plist.entitlements; path = shareextension.entitlements; sourceTree = \"<group>\"; };",
    ),
    (
        "\t\t\t\tAF2C929B279AB3F10094C08D /* pushextension */,",
        "\t\t\t\tAF2C929B279AB3F10094C08D /* pushextension */,\n"
        "\t\t\t\tKHSHREXT001A1B2C3D4E5F6 /* shareextension */,",
    ),
    (
        "\t\t\t\tAF2C929A279AB3F10094C08D /* pushextension.appex */,",
        "\t\t\t\tAF2C929A279AB3F10094C08D /* pushextension.appex */,\n"
        "\t\t\t\tKHSHREXT002A1B2C3D4E5F6 /* shareextension.appex */,",
    ),
    (
        "\t\tAF2C929B279AB3F10094C08D /* pushextension */ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\tAF2C929C279AB3F10094C08D /* NotificationService.swift */,\n\t\t\t\tAF2C929E279AB3F10094C08D /* Info.plist */,\n\t\t\t);\n\t\t\tpath = pushextension;\n\t\t\tsourceTree = \"<group>\";\n\t\t};",
        "\t\tAF2C929B279AB3F10094C08D /* pushextension */ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\tAF2C929C279AB3F10094C08D /* NotificationService.swift */,\n\t\t\t\tAF2C929E279AB3F10094C08D /* Info.plist */,\n\t\t\t);\n\t\t\tpath = pushextension;\n\t\t\tsourceTree = \"<group>\";\n\t\t};\n"
        "\t\tKHSHREXT001A1B2C3D4E5F6 /* shareextension */ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n\t\t\t\tKHSHREXT003A1B2C3D4E5F6 /* ShareViewController.swift */,\n\t\t\t\tKHSHREXT006A1B2C3D4E5F6 /* Info.plist */,\n\t\t\t\tKHSHREXT007A1B2C3D4E5F6 /* shareextension.entitlements */,\n\t\t\t);\n\t\t\tpath = shareextension;\n\t\t\tsourceTree = \"<group>\";\n\t\t};",
    ),
    (
        "\t\t\tdependencies = (\n\t\t\t\tAF2C92A0279AB3F10094C08D /* PBXTargetDependency */,\n\t\t\t);",
        "\t\t\tdependencies = (\n\t\t\t\tAF2C92A0279AB3F10094C08D /* PBXTargetDependency */,\n\t\t\t\tKHSHREXT016A1B2C3D4E5F6 /* PBXTargetDependency */,\n\t\t\t);",
    ),
    (
        "\t\tAF2C9299279AB3F10094C08D /* pushextension */ = {\n\t\t\tisa = PBXNativeTarget;",
        "\t\tKHSHREXT008A1B2C3D4E5F6 /* shareextension */ = {\n\t\t\tisa = PBXNativeTarget;\n\t\t\tbuildConfigurationList = KHSHREXT012A1B2C3D4E5F6 /* Build configuration list for PBXNativeTarget \"shareextension\" */;\n\t\t\tbuildPhases = (\n\t\t\t\tKHSHREXT009A1B2C3D4E5F6 /* Sources */,\n\t\t\t\tKHSHREXT010A1B2C3D4E5F6 /* Frameworks */,\n\t\t\t\tKHSHREXT011A1B2C3D4E5F6 /* Resources */,\n\t\t\t);\n\t\t\tbuildRules = (\n\t\t\t);\n\t\t\tdependencies = (\n\t\t\t);\n\t\t\tname = shareextension;\n\t\t\tproductName = shareextension;\n\t\t\tproductReference = KHSHREXT002A1B2C3D4E5F6 /* shareextension.appex */;\n\t\t\tproductType = \"com.apple.product-type.app-extension\";\n\t\t};\n\t\tAF2C9299279AB3F10094C08D /* pushextension */ = {\n\t\t\tisa = PBXNativeTarget;",
    ),
    (
        "\t\t\t\tAF2C9299279AB3F10094C08D /* pushextension */,\n\t\t\t);",
        "\t\t\t\tAF2C9299279AB3F10094C08D /* pushextension */,\n\t\t\t\tKHSHREXT008A1B2C3D4E5F6 /* shareextension */,\n\t\t\t);",
    ),
    (
        "\t\t\t\t\tAF2C9299279AB3F10094C08D = {\n\t\t\t\t\t\tCreatedOnToolsVersion = 12.5;\n\t\t\t\t\t\tDevelopmentTeam = Y46L589C5C;\n\t\t\t\t\t\tProvisioningStyle = Automatic;\n\t\t\t\t\t};",
        "\t\t\t\t\tAF2C9299279AB3F10094C08D = {\n\t\t\t\t\t\tCreatedOnToolsVersion = 12.5;\n\t\t\t\t\t\tDevelopmentTeam = Y46L589C5C;\n\t\t\t\t\t\tProvisioningStyle = Automatic;\n\t\t\t\t\t};\n\t\t\t\t\tKHSHREXT008A1B2C3D4E5F6 = {\n\t\t\t\t\t\tCreatedOnToolsVersion = 12.5;\n\t\t\t\t\t\tDevelopmentTeam = DQRPWB97AB;\n\t\t\t\t\t\tProvisioningStyle = Automatic;\n\t\t\t\t\t};",
    ),
    (
        "\t\t\t\t11B0A0201C45229500DCE001 /* GroupMembersDrawerView.swift */,",
        "\t\t\t\t11B0A0201C45229500DCE001 /* GroupMembersDrawerView.swift */,\n\t\t\t\tKHSHRINBOX001A1B2C3D4E5F6 /* ShareInboxStorage.swift */,\n\t\t\t\tKHGRPSEL001A1B2C3D4E5F6 /* GroupSelectController.swift */,",
    ),
    (
        "\t\t\t\tKHNDQNETLOG005A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,",
        "\t\t\t\tKHNDQNETLOG005A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,\n\t\t\t\tKHSHRINBOX002A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */,\n\t\t\t\tKHGRPSEL002A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */,",
    ),
    (
        "\t\t\t\tKHNDQNETLOG007A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,",
        "\t\t\t\tKHNDQNETLOG007A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,\n\t\t\t\tKHSHRINBOX003A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */,\n\t\t\t\tKHGRPSEL003A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */,",
    ),
    (
        "\t\t\t\tKHNDQNETLOG008A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,",
        "\t\t\t\tKHNDQNETLOG008A1B2C3D4E5F6 /* NetworkDiagnosticsDetailController.swift in Sources */,\n\t\t\t\tKHSHRINBOX004A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */,\n\t\t\t\tKHGRPSEL004A1B2C3D4E5F6 /* GroupSelectController.swift in Sources */,",
    ),
    (
        "\t\tAF2C9296279AB3F10094C08D /* Sources */ = {",
        "\t\tKHSHREXT009A1B2C3D4E5F6 /* Sources */ = {\n\t\t\tisa = PBXSourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t\tKHSHREXT004A1B2C3D4E5F6 /* ShareViewController.swift in Sources */,\n\t\t\t\tKHSHREXT005A1B2C3D4E5F6 /* ShareInboxStorage.swift in Sources */,\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n\t\tKHSHREXT010A1B2C3D4E5F6 /* Frameworks */ = {\n\t\t\tisa = PBXFrameworksBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n\t\tKHSHREXT011A1B2C3D4E5F6 /* Resources */ = {\n\t\t\tisa = PBXResourcesBuildPhase;\n\t\t\tbuildActionMask = 2147483647;\n\t\t\tfiles = (\n\t\t\t);\n\t\t\trunOnlyForDeploymentPostprocessing = 0;\n\t\t};\n\t\tAF2C9296279AB3F10094C08D /* Sources */ = {",
    ),
    (
        "\t\tAF2C92A0279AB3F10094C08D /* PBXTargetDependency */ = {",
        "\t\tKHSHREXT016A1B2C3D4E5F6 /* PBXTargetDependency */ = {\n\t\t\tisa = PBXTargetDependency;\n\t\t\ttarget = KHSHREXT008A1B2C3D4E5F6 /* shareextension */;\n\t\t\ttargetProxy = KHSHREXT015A1B2C3D4E5F6 /* PBXContainerItemProxy */;\n\t\t};\n\t\tAF2C92A0279AB3F10094C08D /* PBXTargetDependency */ = {",
    ),
    (
        "\t\tAF2C92A4279AB3F10094C08D /* Release */ = {\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n\t\t\t\tCLANG_ANALYZER_NONNULL = YES_NONAGGRESSIVE;",
        "\t\tKHSHREXT013A1B2C3D4E5F6 /* Debug */ = {\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n\t\t\t\tCLANG_ANALYZER_NONNULL = YES_NONAGGRESSIVE;\n\t\t\t\tCLANG_ANALYZER_NUMBER_OBJECT_CONVERSION = YES;\n\t\t\t\tCLANG_WARN_DOCUMENTATION_COMMENTS = YES;\n\t\t\t\tCLANG_WARN_UNGUARDED_AVAILABILITY = YES;\n\t\t\t\tCODE_SIGN_ENTITLEMENTS = shareextension/shareextension.entitlements;\n\t\t\t\tCODE_SIGN_IDENTITY = \"iPhone Developer\";\n\t\t\t\t\"CODE_SIGN_IDENTITY[sdk=iphoneos*]\" = \"iPhone Developer\";\n\t\t\t\tCODE_SIGN_STYLE = Automatic;\n\t\t\t\tDEBUG_INFORMATION_FORMAT = dwarf;\n\t\t\t\tDEVELOPMENT_TEAM = DQRPWB97AB;\n\t\t\t\tINFOPLIST_FILE = shareextension/Info.plist;\n\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 12.0;\n\t\t\t\tLD_RUNPATH_SEARCH_PATHS = \"$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks\";\n\t\t\t\tMARKETING_VERSION = 1.4.28;\n\t\t\t\tMTL_ENABLE_DEBUG_INFO = INCLUDE_SOURCE;\n\t\t\t\tMTL_FAST_MATH = YES;\n\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = org.khandaq.messenger.shareextension;\n\t\t\t\tPRODUCT_NAME = \"$(TARGET_NAME)\";\n\t\t\t\tSKIP_INSTALL = YES;\n\t\t\t\tSWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;\n\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = \"-Onone\";\n\t\t\t\tSWIFT_VERSION = 4.0;\n\t\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";\n\t\t\t};\n\t\t\tname = Debug;\n\t\t};\n\t\tKHSHREXT014A1B2C3D4E5F6 /* Release */ = {\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n\t\t\t\tCLANG_ANALYZER_NONNULL = YES_NONAGGRESSIVE;\n\t\t\t\tCLANG_ANALYZER_NUMBER_OBJECT_CONVERSION = YES;\n\t\t\t\tCLANG_WARN_DOCUMENTATION_COMMENTS = YES;\n\t\t\t\tCLANG_WARN_UNGUARDED_AVAILABILITY = YES;\n\t\t\t\tCODE_SIGN_ENTITLEMENTS = shareextension/shareextension.entitlements;\n\t\t\t\tCODE_SIGN_IDENTITY = \"iPhone Developer\";\n\t\t\t\t\"CODE_SIGN_IDENTITY[sdk=iphoneos*]\" = \"iPhone Developer\";\n\t\t\t\tCODE_SIGN_STYLE = Automatic;\n\t\t\t\tDEBUG_INFORMATION_FORMAT = \"dwarf-with-dsym\";\n\t\t\t\tDEVELOPMENT_TEAM = DQRPWB97AB;\n\t\t\t\tINFOPLIST_FILE = shareextension/Info.plist;\n\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 12.0;\n\t\t\t\tLD_RUNPATH_SEARCH_PATHS = \"$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks\";\n\t\t\t\tMARKETING_VERSION = 1.4.28;\n\t\t\t\tMTL_ENABLE_DEBUG_INFO = NO;\n\t\t\t\tMTL_FAST_MATH = YES;\n\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = org.khandaq.messenger.shareextension;\n\t\t\t\tPRODUCT_NAME = \"$(TARGET_NAME)\";\n\t\t\t\tSKIP_INSTALL = YES;\n\t\t\t\tSWIFT_VERSION = 4.0;\n\t\t\t\tTARGETED_DEVICE_FAMILY = \"1,2\";\n\t\t\t};\n\t\t\tname = Release;\n\t\t};\n\t\tAF2C92A4279AB3F10094C08D /* Release */ = {\n\t\t\tisa = XCBuildConfiguration;\n\t\t\tbuildSettings = {\n\t\t\t\tCLANG_ANALYZER_NONNULL = YES_NONAGGRESSIVE;",
    ),
    (
        "\t\tAF2C92A5279AB3F10094C08D /* Build configuration list for PBXNativeTarget \"pushextension\" */ = {",
        "\t\tKHSHREXT012A1B2C3D4E5F6 /* Build configuration list for PBXNativeTarget \"shareextension\" */ = {\n\t\t\tisa = XCConfigurationList;\n\t\t\tbuildConfigurations = (\n\t\t\t\tKHSHREXT013A1B2C3D4E5F6 /* Debug */,\n\t\t\t\tKHSHREXT014A1B2C3D4E5F6 /* Release */,\n\t\t\t);\n\t\t\tdefaultConfigurationIsVisible = 0;\n\t\t\tdefaultConfigurationName = Release;\n\t\t};\n\t\tAF2C92A5279AB3F10094C08D /* Build configuration list for PBXNativeTarget \"pushextension\" */ = {",
    ),
]

for needle, addition in insertions:
    if needle not in text:
        raise SystemExit(f"needle not found: {needle[:80]!r}")
    text = text.replace(needle, addition, 1)

PBX.write_text(text)
print("shareextension target added")
