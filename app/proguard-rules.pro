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
-keep class sh.haven.et.protocol.** { *; }

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
