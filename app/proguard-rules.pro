# BouncyCastle: full member keep ({ *; }) is mandatory. The provider constructor
# reflectively instantiates *$Mappings inner classes to register algorithms, AND
# JCA Provider.getService() later reflectively instantiates algorithm impl classes
# (e.g. MD4$Digest). Fine-grained "-keep **$Mappings { <init>(); }" is insufficient
# because algorithm impl constructors are also reflectively invoked at runtime.
# Without { *; }, R8 strips these no-arg constructors → InternalError (extends Error,
# bypasses catch(Exception) → app crash). Verified: GOST3411$Mappings ctor stripped.
-keep class org.bouncycastle.** { *; }

# jcifs-ng / SSHJ: class-name-only keep is sufficient — these libraries do not
# reflectively invoke their own methods; method-body optimization (inline/deadcode)
# remains enabled. SMB verified working in release with class-name-only keep.
-keep class jcifs.**
-keep class net.schmizz.**

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
