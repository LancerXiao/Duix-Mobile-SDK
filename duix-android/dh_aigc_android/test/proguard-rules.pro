# Add project specific ProGuard rules here.

# ViewBinding - 必须保留，否则R8会移除binding类导致白屏
-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}

# 保留所有Activity类
-keep class ai.guiji.duix.test.** { *; }
-keep class ai.guiji.duix.test.ui.** { *; }
-keep class ai.guiji.duix.test.service.** { *; }

# OkHttp
-dontwarn com.squareup.okhttp3.**
-keep class com.squareup.okhttp3.** { *;}
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# DUIX SDK
-keep class ai.guiji.duix.DuixNcnn{*; }
-keep class ai.guiji.duix.sdk.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder { *** rewind(); }

# 保留JSON相关
-keep class org.json.** { *; }

# 保留AndroidX组件
-keep class androidx.appcompat.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }

# 保留native方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留自定义View
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留Serializable/Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
