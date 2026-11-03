-dontobfuscate

# Disable ServiceLoader reproducibility-breaking optimizations
-keep class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

-dontwarn kotlinx.serialization.KSerializer
-dontwarn kotlinx.serialization.Serializable
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep all Parcelable implementations and their creators for Android 13+ type-safe parceling
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable {
    public <init>(android.os.Parcel);
}
-keepnames class com.looker.droidify.ui.appDetail.AppDetailAdapter$SavedState
