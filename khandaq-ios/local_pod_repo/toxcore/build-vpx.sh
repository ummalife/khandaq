#!/bin/bash

TEMP_DIR="temp"
LIBVPX_DIR="libvpx"
VPX_FRAMEWORK="vpx.framework"

# $1 - framework directory
remove_old_framework() {
    if [ -d "$1" ]; then
        echo "Removing $1"
        rm -rf "$1"
    fi
}

clone_libvpx() {
    echo "Cloning libvpx"
    mkdir $TEMP_DIR
    cd $TEMP_DIR
    git clone --branch v1.4.0 --depth 1 https://github.com/webmproject/libvpx.git $LIBVPX_DIR
}

# $1 - patch file name
patch_vpx() {
    echo "Patching libvpx"
    cd $LIBVPX_DIR
    git apply ../../$1
    cd ../..
}

build_vpx() {
    echo "Building vpx framework"
    $TEMP_DIR/$LIBVPX_DIR/build/make/iosbuild.sh --show-build-output --verbose

    echo "Moving headers from $VPX_FRAMEWORK/Headers/vpx/ to $VPX_FRAMEWORK/Headers/"
    mv $VPX_FRAMEWORK/Headers/vpx/* $VPX_FRAMEWORK/Headers/
    rm -rf $VPX_FRAMEWORK/Headers/vpx
}

# $2 - directory to move framework to
move_vpx() {
    echo "Moving framework from $VPX_FRAMEWORK to $1/$VPX_FRAMEWORK"
    mkdir $1
    mv $VPX_FRAMEWORK $1/$VPX_FRAMEWORK
}

cleanup() {
    echo "Removing $TEMP_DIR directory"
    rm -rf $TEMP_DIR
}

# $1 - patch file name
# $2 - directory install framework to
do_all() {
    echo "Building for $2"

    remove_old_framework $2

    clone_libvpx
    patch_vpx $1
    build_vpx
    move_vpx $2

    cleanup
}

do_all vpx-osx.diff osx
do_all vpx-ios.diff ios

echo "Done"
