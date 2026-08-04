-dontobfuscate
-dontoptimize
-keepattributes SourceFile,LineNumberTable

#-keep class !com.google.gson.** { *; }

-keep class com.zoffcc.applications.trifa.** { *; }
-keep class com.github.gfx.android.orma.Schema { *; }
-keep class javax.annotation.** { *; }
-keep class android.graphics.** { *; }
-keep class java.nio.file.** { *; }
-keep class okhttp3.** { *; }
-keep class org.codehaus.mojo.animal_sniffer.** { *; }

# KHANDAQ (audit A30): release now runs minifyEnabled true. With -dontobfuscate + -dontoptimize
# (top of file) the ONLY active transform is dead-code shrinking; these keeps guarantee shrinking
# can never strip the JNI bridge, the Tox C->Java callbacks, or reflected model classes. There are
# 187 name-based Java_com_zoffcc_applications_trifa_* entrypoints plus native methods in the
# nativeaudio / loggingstdout packages that must all survive.
# 1) Never drop a native method or its host class -- universal JNI safety (covers every package).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# 2) Keep ALL of our own code (broadened from the trifa-only keep above to every Khandaq/zoffcc
#    package) -- covers the 187 JNI entrypoints, C->Java callbacks, Gson models, dynamic lookups.
-keep class com.zoffcc.applications.** { *; }
-keep class org.khandaq.** { *; }
# 2b) KHANDAQ (crash-on-launch ROOT FIX, 10390): IOCipher's native libiocipher2.so calls
#     JniConstants::init from JNI_OnLoad, which FindClass-es info.guardianproject.libcore.io.Struct*
#     classes. Those are referenced ONLY from native code (never from Java), so R8 dead-code shrinking
#     (enabled since 10384/A30) stripped them -> logcat "JniConstants: failed to find class
#     'info/guardianproject/libcore/io/StructAddrinfo'" -> native abort() / SIGABRT the instant
#     VirtualFileSystem.<clinit> runs (MainActivity.onCreateContinue). A native abort cannot be caught
#     by the Java try/catch around VirtualFileSystem.get() nor by the UncaughtExceptionHandler, so it
#     showed up as the silent splash->exit "won't open after update" on RELEASE builds only (debug =
#     minify off = fine). Keep the whole IOCipher + sqlcipher Java surface so native FindClass resolves.
-keep class info.guardianproject.** { *; }
-keep class net.sqlcipher.** { *; }
# 3) Standard reflection-safe keeps.
-keepclassmembers enum * { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn *
