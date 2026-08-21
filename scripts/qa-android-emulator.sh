#!/usr/bin/env bash
# KHANDAQ — bring up a headless Android emulator for device QA, and say plainly when it cannot.
#
# WHY THIS IS A SCRIPT AND NOT A WIKI PAGE. The instrumentation suite is the only thing that exercises
# the JNI Ed25519 path, the encrypted profile database and the anti-downgrade gate against real
# storage — and every one of those tests SKIPS without an emulator and an open profile. A suite that
# skips is a green tick over nothing, so the setup has to be reproducible rather than remembered.
#
# ACCELERATION, on AMD in particular. The Android emulator needs a hypervisor. On Intel that is HAXM;
# on AMD, HAXM does not exist and the usual advice is to enable Windows Hypervisor Platform — which
# turns the host into a Hyper-V root partition and REQUIRES A REBOOT. It is not necessary: Google
# ships `extras;google;Android_Emulator_Hypervisor_Driver` (AEHD), a kernel driver that needs neither
# Hyper-V nor a reboot, and is the right choice on a machine that is also serving something.
# Verified on Ryzen 9 3900 / Windows Server 2022: `emulator -accel-check` reports
# "AEHD (version 2.2) is installed and usable".
#
#   qa-android-emulator.sh check     what is installed, what is missing, is acceleration live
#   qa-android-emulator.sh install   SDK packages + system image + the AMD hypervisor driver
#   qa-android-emulator.sh up [n]    create and boot n headless AVDs (default 1)
#   qa-android-emulator.sh down      stop them
#
# Needs ANDROID_SDK_ROOT and a JDK 17 on PATH. Windows hosts: run from Git Bash.
set -uo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
IMAGE="${KHANDAQ_QA_IMAGE:-system-images;android-34;google_apis;x86_64}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
DEVICE="${KHANDAQ_QA_DEVICE:-pixel_6}"

die() { echo "ERROR: $*" >&2; exit 1; }
[ -n "$SDK" ] || die "set ANDROID_SDK_ROOT to the Android SDK"

# `.bat` on Windows, extensionless elsewhere — a batch file is not marked executable, so -f not -x.
tool() {
    local base="$SDK/$1"
    [ -x "$base" ] && { echo "$base"; return 0; }
    [ -f "$base.bat" ] && { echo "$base.bat"; return 0; }
    [ -f "$base.exe" ] && { echo "$base.exe"; return 0; }
    return 1
}

SDKMANAGER=$(tool "cmdline-tools/latest/bin/sdkmanager") || die "sdkmanager not found under $SDK"
ADB=$(tool "platform-tools/adb") || ADB=adb

case "${1:-check}" in

check)
    echo "SDK:      $SDK"
    "$SDKMANAGER" --sdk_root="$SDK" --list_installed 2>/dev/null \
        | grep -E '^\s*(platform-tools|platforms;|build-tools;|ndk;|cmake;|emulator|system-images;|extras;)' \
        | sed 's/[[:space:]]*$//' || echo "  (could not list packages — is JAVA_HOME set?)"
    echo
    if EMU=$(tool "emulator/emulator"); then
        echo "acceleration:"
        "$EMU" -accel-check 2>&1 | sed 's/^/  /'
    else
        echo "emulator not installed — run: $0 install"
    fi
    ;;

install)
    echo "==> SDK packages"
    yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1
    "$SDKMANAGER" --sdk_root="$SDK" --install "platform-tools" "emulator" "$IMAGE" \
        "extras;google;Android_Emulator_Hypervisor_Driver" || die "sdkmanager install failed"

    # The driver is a separate, manual step: sdkmanager only DOWNLOADS it. Its own silent_install.bat
    # self-elevates through a UAC prompt, which is useless over ssh/WinRM — so on an already-elevated
    # shell, run the two commands it would have run.
    drv="$SDK/extras/google/Android_Emulator_Hypervisor_Driver"
    if [ -f "$drv/aehd.Inf" ]; then
        if command -v sc.exe >/dev/null 2>&1 && ! sc.exe query aehd >/dev/null 2>&1; then
            echo "==> installing the AMD emulator hypervisor driver (no reboot, no Hyper-V)"
            RUNDLL32.EXE SETUPAPI.DLL,InstallHinfSection DefaultInstall 132 "$(cygpath -w "$drv/aehd.Inf" 2>/dev/null || echo "$drv/aehd.Inf")" \
                || echo "    (driver install returned non-zero; check an elevated shell)"
            sc.exe start aehd >/dev/null 2>&1
        fi
        command -v sc.exe >/dev/null 2>&1 && sc.exe query aehd | sed -n '3,4p'
    fi
    "$0" check
    ;;

up)
    n=${2:-1}
    EMU=$(tool "emulator/emulator") || die "emulator not installed — run: $0 install"
    AVDMANAGER=$(tool "cmdline-tools/latest/bin/avdmanager") || die "avdmanager not found"
    mkdir -p "$AVD_HOME"
    for i in $(seq 1 "$n"); do
        name="khandaq-qa-$i"
        port=$((5554 + (i - 1) * 2))
        if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $name"; then
            echo "==> creating AVD $name"
            echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d "$DEVICE" --force >/dev/null \
                || die "could not create $name (is $IMAGE installed?)"
        fi
        echo "==> booting $name on emulator-$port"
        # -no-window because this is a server; -no-snapshot so every QA run starts from the same
        # state, which is the whole point of first-run automation existing.
        "$EMU" -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot \
            -gpu swiftshader_indirect -memory 3072 -cores 4 >"${TMPDIR:-/tmp}/khandaq-emu-$port.log" 2>&1 &
    done
    "$ADB" start-server >/dev/null 2>&1
    for i in $(seq 1 "$n"); do
        port=$((5554 + (i - 1) * 2))
        "$ADB" -s "emulator-$port" wait-for-device >/dev/null 2>&1
        for _ in $(seq 1 120); do
            [ "$("$ADB" -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
            sleep 5
        done
        echo "emulator-$port: boot_completed=$("$ADB" -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') api=$("$ADB" -s "emulator-$port" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    done
    "$ADB" devices -l
    echo
    echo "Now:  ./gradlew :app:connectedDebugAndroidTest   (from khandaq-android-trifa/android-refimpl-app)"
    echo "Note: app/nativelibs/*/libjni-c-toxcore.so is gitignored. Without it nothing runs — take it"
    echo "      from the android-native-so.yml artifact ('gh run download <id> -n libjni-c-toxcore-so-<n>')"
    echo "      and place armeabi/ as armeabi-v7a/."
    ;;

down)
    for p in 5554 5556 5558 5560 5562; do "$ADB" -s "emulator-$p" emu kill >/dev/null 2>&1; done
    echo "stopped"
    ;;

*)
    die "usage: $0 {check|install|up [n]|down}"
    ;;
esac
