# ==================== BLE 协议层 ====================
# 协议帧与 TLV 解析使用反射调用较少，但为防止优化破坏行为，整体保留
-keep class com.turing.thumbcard.ble.** { *; }
-keep class com.turing.thumbcard.crypto.** { *; }

# BouncyCastle（AES/RSA 依赖）
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ==================== 网络模型 ====================
# Retrofit + Gson 依赖字段名与注解，需保留
-keep class com.turing.thumbcard.data.api.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# Gson 泛型
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**

# ==================== Room ====================
-keep class com.turing.thumbcard.data.db.** { *; }
-dontwarn androidx.room.paging.**

# ==================== 安全存储 ====================
-keep class androidx.security.crypto.** { *; }

# ==================== 保留行号便于排查 ====================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
