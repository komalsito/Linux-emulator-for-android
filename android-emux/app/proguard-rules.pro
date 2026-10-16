# Keep termux view/emulator internals; they use reflection for JNI-style callbacks.
-keep class com.termux.** { *; }
-dontwarn com.termux.**
# Our JNI-free core is pure Kotlin; nothing else to keep.
