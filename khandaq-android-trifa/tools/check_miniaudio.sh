#! /bin/bash

_HOME2_=$(dirname $0)
export _HOME2_
_HOME_=$(cd $_HOME2_;pwd)
export _HOME_

basedir="$_HOME_""/../android-refimpl-app/"
cd "$basedir"

r1='https://github.com/mackron/miniaudio'
f1='native-audio-jni/src/main/cpp/miniaudio.h'

tagspec=''
ver=$(git ls-remote --refs --sort='v:refname' --tags "$r1" 2>/dev/null \
    | cut --delimiter='/' --fields=3 2>/dev/null \
    | tail -1 2>/dev/null \
    )


#define MA_VERSION_MAJOR    0
#define MA_VERSION_MINOR    11
#define MA_VERSION_REVISION 21

major=$(cat "$f1"|grep 'define MA_VERSION_MAJOR'|awk '{print $3}'|tr -d ' ')
minor=$(cat "$f1"|grep 'define MA_VERSION_MINOR'|awk '{print $3}'|tr -d ' ')
revis=$(cat "$f1"|grep 'define MA_VERSION_REVISION'|awk '{print $3}'|tr -d ' ')

current_ver="$major"'.'"$minor"'.'"$revis"

# echo "current version=$current_ver"

if [ "$current_ver""x" != "$ver""x" ]; then
    echo "__VERSIONUPDATE__:""$ver"
else
    :
    # no new version available
fi
