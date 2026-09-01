# Proguard rules for Shizuku and AIDL
-keep class dev.rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class android.hardware.input.IInputManager** { *; }
-keep class com.kinou.gameassist.injector.IInputManagerHelper** { *; }

# Data Models & Serialization (Kotlinx Serialization)
-keep class com.kinou.gameassist.data.model.** { *; }
-keep class com.kinou.gameassist.data.community.** { *; }
-keep class com.kinou.gameassist.data.updater.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Coroutines & ViewModel
-keepclassmembers class * extends kotlinx.coroutines.CoroutineScope { *; }

# Security Crypto (Tink) optional annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

