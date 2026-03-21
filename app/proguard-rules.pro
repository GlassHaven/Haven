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
-dontwarn com.sun.jna.**

# Keep termlib classes — native JNI renderer accesses fields by name
-keep class org.connectbot.terminal.** { *; }

# Keep smbj (reflection-based protocol handling)
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }

# mbassador optional EL API (not present on Android)
-dontwarn javax.el.**

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
