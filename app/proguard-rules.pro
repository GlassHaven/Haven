# Allow R8 to proceed with missing classes (JSch references JNA, GSSAPI, SLF4J,
# Log4j2, and Unix sockets which are unavailable on Android)
-ignorewarnings

# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Keep JSch
-keep class com.jcraft.jsch.** { *; }

# JSch optional dependencies not available on Android
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
# Keep JNA — native JNI accesses Pointer.peer field by name (needed for IronRDP)
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.Log4j2Logger
-dontwarn com.jcraft.jsch.Slf4jLogger
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.JUnixSocketFactory

# Keep termlib classes — native JNI renderer accesses fields by name
-keep class org.connectbot.terminal.** { *; }

# Keep mosh transport + generated protobuf classes.
# The pure-Kotlin transport reflects on protobuf field names like `width_`.
# If R8 renames those fields, Mosh connects but never establishes a usable
# terminal session in release builds.
-keep class sh.haven.mosh.** { *; }

# Keep smbj (reflection-based protocol handling)
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-dontwarn javax.el.**

# Keep protobuf generated classes — protobuf-lite uses reflection on field names
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
-keep class sh.haven.mosh.proto.** { *; }

# Keep the entire et-kotlin submodule. The previous keep rule covered only
# `sh.haven.et.protocol.**`, leaving `sh.haven.et.transport.EtTransport`,
# `sh.haven.et.crypto.EtCrypto`, and `sh.haven.et.EtLogger` exposed to R8 —
# verified against the v5.5.0 release mapping.txt where EtTransport is
# renamed to h5.b. Eternal Terminal connections fail in release builds as
# a result. Mirror the broad `sh.haven.mosh.**` rule for consistency.
-keep class sh.haven.et.** { *; }

# Keep gomobile/rclone bindings — JNI native methods and Go runtime
-keep class go.** { *; }
-keep class sh.haven.rclone.binding.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Reticulum — rns-core and rns-interfaces do extensive class-literal
# reflection (link::class.java.getMethod("getLinkId"), getInitiator,
# receive, validateProof, getExpectedHops, getAttachedInterfaceHash,
# clientCount, etc). The rns-android module has a consumer-rules.pro
# with the correct keeps, but Haven consumes rns-core/rns-interfaces
# directly via settings.gradle.kts substitution and rns-android is never
# pulled in, so those consumer rules never reach the app's R8 pass.
# Without this, every reflective link/packet operation throws
# NoSuchMethodException in release builds (verified against v5.4.4
# mapping.txt: Transport → w4.q, Reticulum → j4.a).
-keep class network.reticulum.** { *; }
-keep interface network.reticulum.** { *; }

# Keep MessagePack — Reticulum's serialization path resolves classes
# by string name (e.g. MessageBufferU). Same reason as above: rns-android
# consumer rules aren't reaching us.
-keep class org.msgpack.** { *; }
-dontwarn org.msgpack.**

# Keep JNA Structure subclasses — IronRDP (UniFFI-generated sh.haven.rdp.**)
# uses @Structure.FieldOrder("capacity", "len", ...) string literals that
# JNA resolves reflectively at runtime. R8 renames the @JvmField properties
# to single letters, Structure.deriveLayout() can't find them, and every
# RDP connection fails with "unknown or zero size (ensure all fields are
# public)" — issue #93.
-keep class * extends com.sun.jna.Structure {
    <fields>;
    <init>(...);
}
-keep class sh.haven.rdp.** { *; }

# Keep Shizuku API — Haven calls Shizuku only via reflection
# (Class.forName("rikka.shizuku.Shizuku").getMethod("pingBinder") etc).
# Without this, R8 prunes pingBinder / addBinderReceivedListenerSticky /
# checkSelfPermission / requestPermission / newProcess, and the reflection
# lookups throw NoSuchMethodException at runtime — issue #82.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }

# sora-editor's TextMate language registry deserializes JSON language /
# theme / grammar definitions with Gson, into both its own wrapper types
# (rosemoe.sora.langs.textmate.registry) AND the underlying tm4e engine
# types (org.eclipse.tm4e.*). R8 obfuscates the class names which Gson
# then reports as "Abstract classes can't be instantiated" the first
# time the editor opens any file with a detectable language. Keep both
# package trees whole — they're all data carriers.
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keepclassmembers class io.github.rosemoe.sora.langs.textmate.** {
    <init>(...);
    <fields>;
}
-keepclassmembers class org.eclipse.tm4e.** {
    <init>(...);
    <fields>;
}

# Joni (Java Oniguruma regex engine) and jcodings (its byte-encoding
# dep) cannot be minified — Joni has heavy reliance on cross-class
# static constants and reflective lookups, and R8 stripping any of
# them produces NullPointerException in <clinit> the first time a
# regex is compiled. Both libs are pulled in transitively by tm4e.
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
