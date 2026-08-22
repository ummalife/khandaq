#!/usr/bin/env python3
"""KHANDAQ (release 0.2.40) — every Java `native` method must exist in the library that ships.

Found by QA on real phones, after everything else was green.

MainActivity declares three native methods for Ed25519 — khandaq_ed25519_keypair, _sign and _verify —
and this repository implements all three, in khandaq-android-trifa/jni-c-toxcore/jni-c-toxcore.c at
lines 4590, 4648 and 4707. Nothing compiles that file. app/build.gradle points jniLibs at
`nativelibs`, which holds a single empty dummy.txt, so libjni-c-toxcore.so is taken instead from the
prebuilt AAR com.github.zoff99:pkgs_ToxAndroidRefImpl:1.0.175 — a build that predates those functions.

Nothing anywhere noticed:
  - javac does not care: a `native` declaration needs no implementation to compile.
  - the 172 JVM unit tests are Robolectric/plain JVM and never load the .so.
  - the instrumented tests that DO call them (NgcHistSigNativeTest, NgcHskStoreDeviceTest) are not
    run by any workflow.
  - lint, R8, the APK signature and the release gates are all indifferent.

The cost was not theoretical. On both test phones the shipped build threw
UnsatisfiedLinkError roughly 33 times a second, forever — 9193 traces in 280 seconds — from
HelperGroup.maintain_all_groups -> announce_hsk_to_group -> NgcHskAnnounce.buildSelfAnnouncement.
And because the HSK directory can only be written by a path that goes through the missing verify(),
it stays empty, so NgcHistoryDowngradePolicy.decide() always sees an unknown author and always
returns ACCEPT_LEGACY. The K-01 anti-downgrade protection, and the F-03 extension of it to file
history, are present in source and unreachable in practice.

So: for every `public static native` method declared in the Java sources, the shipped library must
export the correspondingly mangled JNI symbol.

    python3 scripts/check-jni-symbols.py [path/to/libjni-c-toxcore.so]

With no argument it looks in khandaq-android-trifa/android-refimpl-app/app/nativelibs/<abi>/. It
fails when the library is absent, because "no library to check" is exactly the state that produced
this bug.

Needs nm (llvm-nm, binutils nm, or Android NDK's). Stdlib only otherwise.
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.environ.get("KHANDAQ_ROOT") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "khandaq-android-trifa", "android-refimpl-app", "app")
NATIVELIBS = os.path.join(APP, "nativelibs")
JAVA_DIR = os.path.join(APP, "src", "main", "java", "com", "zoffcc", "applications", "trifa")

LIB = "libjni-c-toxcore.so"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

# Declarations to police. Deliberately narrow: these are the ones this project added and the ones a
# stale prebuilt library silently drops. Upstream toxcore natives come from the same library and are
# covered by it being the right library at all.
WATCHED = re.compile(r"^\s*public\s+static\s+native\s+\w[\w\[\]<>., ]*\s+(khandaq_\w+)\s*\(", re.M)


def die(msg):
    print("::error::" + msg, file=sys.stderr)
    sys.exit(1)


def mangle(java_name):
    """JNI short name: underscores in the method name become _1."""
    return "Java_com_zoffcc_applications_trifa_MainActivity_" + java_name.replace("_", "_1")


def find_nm():
    for c in ("llvm-nm", "nm"):
        p = shutil.which(c)
        if p:
            return p
    ndk = os.path.expanduser("~/Library/Android/sdk/ndk")
    if os.path.isdir(ndk):
        for v in sorted(os.listdir(ndk), reverse=True):
            p = os.path.join(ndk, v, "toolchains", "llvm", "prebuilt", "darwin-x86_64", "bin", "llvm-nm")
            if os.path.isfile(p):
                return p
    return None


def declared_natives():
    names = set()
    if not os.path.isdir(JAVA_DIR):
        die("cannot find the Java sources at %s" % JAVA_DIR)
    for fn in sorted(os.listdir(JAVA_DIR)):
        if not fn.endswith(".java"):
            continue
        with open(os.path.join(JAVA_DIR, fn), encoding="utf-8", errors="replace") as fh:
            for m in WATCHED.finditer(fh.read()):
                names.add(m.group(1))
    return sorted(names)


def exported(nm, path):
    out = subprocess.run([nm, "-D", "--defined-only", path], capture_output=True, text=True)
    if out.returncode != 0:
        out = subprocess.run([nm, "-D", path], capture_output=True, text=True)
    return {line.split()[-1] for line in out.stdout.splitlines() if line.strip()}


def main():
    natives = declared_natives()
    if not natives:
        die("no `public static native khandaq_*` declarations found — either they were removed (in "
            "which case delete this check deliberately) or the pattern has drifted. A check that "
            "finds nothing to protect must not report success.")
    print("Java declares %d khandaq_* native method(s): %s" % (len(natives), ", ".join(natives)))

    nm = find_nm()
    if nm is None:
        die("no nm found (llvm-nm, nm, or the NDK's). This check does not skip itself.")

    libs = []
    if len(sys.argv) > 1:
        libs = [(os.path.basename(os.path.dirname(sys.argv[1])) or "given", sys.argv[1])]
    else:
        for abi in ABIS:
            p = os.path.join(NATIVELIBS, abi, LIB)
            if os.path.isfile(p):
                libs.append((abi, p))

    if not libs:
        die("no %s found under %s.\n"
            "    The app packages whatever jniLibs.srcDirs points at; with that directory empty the "
            "build silently falls back to the prebuilt AAR, which is exactly how three implemented "
            "JNI functions came to be missing from the shipped app. Build the library "
            "(.github/workflows/android-native-so.yml) and commit it."
            % (LIB, os.path.relpath(NATIVELIBS, ROOT)))

    missing_any = False
    for abi, path in libs:
        syms = exported(nm, path)
        missing = [n for n in natives if mangle(n) not in syms]
        total_jni = len([s for s in syms if s.startswith("Java_com_zoffcc_")])
        if missing:
            missing_any = True
            print("::error::%s: %s exports %d JNI symbol(s) but NOT %s"
                  % (abi, LIB, total_jni, ", ".join(mangle(n) for n in missing)), file=sys.stderr)
            for n in missing:
                print("      %s  ->  %s" % (n, mangle(n)), file=sys.stderr)
        else:
            print("  ok  %-12s %d JNI symbols, all %d khandaq_* present"
                  % (abi, total_jni, len(natives)))

    if missing_any:
        die("the shipped native library is missing implementations that Java declares. Calling them "
            "throws UnsatisfiedLinkError at runtime — which is what shipped, thirty-three times a "
            "second, in the build users are running.")

    print("every declared khandaq_* native is exported by every packaged ABI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
