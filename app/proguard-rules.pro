-keep class jcifs.**
-keep class net.schmizz.**
-keep class org.bouncycastle.**
-dontwarn jcifs.**
-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn sun.security.x509.X509Key

# Strip verbose/debug/info/warn logs in release builds (minify-enabled).
# Keep error/wtf for release crash diagnostics.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
