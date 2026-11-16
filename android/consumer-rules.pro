# Applied to the app's release build automatically (consumerProguardFiles).
# These classes are saved and restored with Gson, which reads their fields by
# name, so R8 must not rename or strip them.
-keep class com.kesha_antonov.RNBGDTaskConfig { *; }
-keep class com.kesha_antonov.RNBGDUploadTaskConfig { *; }
-keep class com.kesha_antonov.UploadPartResult { *; }
-keep class com.kesha_antonov.utils.StorageManager$PausedDownloadInfoData { *; }

# Gson needs the generic type of each TypeToken
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.tencent.mmkv.** { *; }
