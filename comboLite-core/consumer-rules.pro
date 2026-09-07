-keep public interface com.combo.core.api.** { *; }
-keep public class com.combo.core.component.**.* { *; }
-keep public class com.combo.core.runtime.app.BaseHostApplication { *; }

-keep public class com.combo.core.runtime.PluginManager { *; }
-keep class com.combo.core.runtime.ValidationStrategy { *; }
-keep class com.combo.core.runtime.RegistryAccessMode { *; }
-keep class com.combo.core.runtime.RegistryMutationDeniedException { *; }
-keep class com.combo.core.runtime.RegistryRecoveryRequiredException { *; }
-keep class com.combo.core.runtime.loader.PluginClassLoadingPolicy { *; }
-keep class com.combo.core.runtime.loader.PluginClassLoadingPolicy$* { *; }
-keep class com.combo.core.runtime.loader.PluginClassLoader { *; }

-keep class com.combo.core.model.** { *; }
-keep @kotlinx.serialization.Serializable class com.combo.core.** {
    <fields>;
    *** Companion;
}
-keep class com.combo.core.**$$serializer { *; }
-keep class com.combo.core.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers enum com.combo.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep @interface com.combo.core.security.permission.RequiresPermission { *; }
-keepclassmembers class * {
    @com.combo.core.security.permission.RequiresPermission <methods>;
}

-keep class kotlin.Metadata { *; }

-keep public class com.combo.core.security.crash.CrashActivity { *; }
-keep public class com.combo.core.security.auth.AuthorizationActivity { *; }

-keep public class com.combo.core.utils.ExtensionsKt {
    public static <methods>;
}

-dontwarn com.combo.core.**
