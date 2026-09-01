# Proguard rules for Shizuku and AIDL
-keep class dev.rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class android.hardware.input.IInputManager** { *; }
-keep class com.kinou.gameassist.injector.IInputManagerHelper** { *; }

# Data Models & Serialization (Gson)
-keep class com.kinou.gameassist.data.model.** { *; }
-keep class com.kinou.gameassist.data.community.** { *; }
-keep class com.kinou.gameassist.data.updater.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coroutines & ViewModel
-keepclassmembers class * extends kotlinx.coroutines.CoroutineScope { *; }

