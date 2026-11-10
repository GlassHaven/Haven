# Allow R8 to proceed with missing classes (JSch references JNA, GSSAPI, SLF4J,
# Log4j2, and Unix sockets which are unavailable on Android)
-ignorewarnings

# Phase 2/3 distro infrastructure — keep class + member names so the
# tagged Log.d / Log.e lines stay diagnosable when users hit edge
# cases (stale pacman DB, partial pacman -Syu, etc.). Without this,
# R8 can rename the classes/methods and stack-traces from these
# code paths become impossible to map back to source.
-keep class sh.haven.core.local.ProotManager { *; }
-keep class sh.haven.core.local.DesktopManager { *; }
-keep class sh.haven.core.local.LocalSessionManager { *; }
-keep class sh.haven.core.local.proot.** { *; }

# Defensive: if any future AGP / library bump adds an
# `-assumenosideeffects class android.util.Log { ... }` rule (which
# would strip log calls entirely from release builds), pin our
# critical Log statements here. As of AGP 8.9.1's
# proguard-android-optimize.txt no such rule ships.

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

# sshlib's Ed25519 JCE provider asks for its OWN package name while installing
# itself:
#
#   Ed25519Provider.class.getPackage().getName()      (Ed25519Provider.setup)
#
# R8 flattens ~26k classes into the root package, and a class with no package
# returns null from getPackage() — so that line throws NullPointerException
# inside the provider's constructor, killing the connection immediately after
# ECDH_REPLY (#513). Keeping the name keeps the package, which is the whole
# point of this rule; the members are incidental.
#
# Only reached via getEd25519FallbackProvider(), i.e. when the platform's own
# Ed25519 is unusable — which is why it depends on the device rather than
# failing for everyone.
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519Provider { *; }

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

# Keep the SPICE bindings (UniFFI-generated sh.haven.spice.**) for the same
# reason as sh.haven.rdp above: JNA loads the generated UniffiLib interface
# reflectively and casts the proxy to it — R8's rename turned that into a
# messageless ClassCastException 7ms into every release-build SPICE connect,
# before a single byte reached the server (#549).
-keep class sh.haven.spice.** { *; }

# Keep the Prns JVM SDK (rs.reticulum.prns.**) — its JNA Library interface's
# method names ARE the native symbol names (prns_host_*); a rename breaks
# every call with UnsatisfiedLinkError in release only.
-keep class rs.reticulum.prns.** { *; }

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

# JavaMail (com.sun.mail:android-mail) registers its Store/Transport providers
# reflectively by fully-qualified class name — via META-INF/javamail.providers
# and the mail.<proto>.class properties ImapMailClient sets, which JavaMail
# resolves with Class.forName. R8 leaves the providers *resource* untouched but
# renames the provider *classes* (verified against this release's mapping.txt:
# com.sun.mail.imap.IMAPSSLStore -> s4.c, com.sun.mail.smtp.SMTPSSLTransport ->
# u4.c), so getStore("imaps") / getTransport("smtps") throw
# NoSuchProviderException and every email account fails to connect in release
# builds (debug works because R8 doesn't run). Keep the whole provider tree.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# ONNX Runtime — libonnxruntime4j_jni.so dispatches back into Java by
# class+field+method name (OnnxTensor, OrtSession, OrtEnvironment, etc.).
# Keep the package tree and JNI-flagged native method declarations verbatim.
# Used by core:scan for RapidOCR (terminal attach OCR + desktop MCP OCR).
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class ai.onnxruntime.** {
    native <methods>;
}

# dump_haven_ui reaches Compose's semantics tree by reflecting on the view's
# class NAME and the `getSemanticsOwner` / `get*RootSemanticsNode` getters
# (HavenUiBridge). R8 renames both, so the verb failed in EVERY release build
# with "No Compose view in the foreground window" while debug worked — the
# same shape as the IMAPSSLStore breakage this file's header warns about.
# Found on-device while making overlay windows dumpable (#355).
-keep class androidx.compose.ui.platform.AndroidComposeView { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwner { *; }

# Opt-in root filesystem worker (#799) — launched via `/system/bin/app_process`
# by fully-qualified class name (`sh.haven.feature.sftp.root.RootFsDaemon`)
# with `CLASSPATH=<apk>`. Without this keep, R8 renames the class/main method
# in release builds and `app_process` fails with ClassNotFoundException.
-keep class sh.haven.feature.sftp.root.RootFsDaemon {
    public static void main(java.lang.String[]);
}
