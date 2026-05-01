# whisper.cpp JNI entry points must not be renamed
-keep class io.whispershare.WhisperEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ViewModel constructors
-keepclasseswithmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
