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
# sshj 0.39+：jar 同时含 net.schmizz.**（主体，~356 类）与 com.hierynomus.**（~52 类）
# ——不存在「主体包迁移」，两个坐标都在 jar 内。com.hierynomus 部分的 security/
# transport 内部存在按名称的算法/工厂发现（KeyAlgorithm 注册、NamedFactory 查找），
# member keep（{ *; }）避免构造器/方法被 R8 裁剪后反射实例化失败（InternalError
# 绕过 catch(Exception) → 崩溃），与 BouncyCastle 同理。
-keep class com.hierynomus.** { *; }

-dontwarn jcifs.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
# sshj 依赖 eddsa（EdDSA 签名实现）与 slf4j-api（Android 无绑定实现，缺绑定仅
# 运行时 NOP，非致命）。eddsa 无已知反射需求——此 keep 是防御性的（防未来版本
# 引入按类名的发现机制时被裁剪），非必须。
-keep class net.i2p.crypto.eddsa.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
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
