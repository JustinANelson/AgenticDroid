# LSP4J builds its LanguageServer/LanguageClient endpoints via reflection (JSON-RPC
# method dispatch keyed by @JsonRequest/@JsonNotification annotations) and serializes
# every message type with Gson. Keep the whole API surface so R8 doesn't strip or
# rename members the reflection layer looks up by name/signature at runtime.
-keep class org.eclipse.lsp4j.** { *; }
-keepclassmembers class org.eclipse.lsp4j.** { *; }
-keep interface org.eclipse.lsp4j.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Gson (pulled in transitively by lsp4j.jsonrpc) reflects over field names to
# (de)serialize LSP message payloads.
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# BouncyCastle providers/algorithms are looked up by fully-qualified class name via
# java.security.Provider service entries, not direct references.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj resolves cipher/kex/mac/compression implementations by class name from its
# config classes (net.schmizz.sshj.transport.*Factories), and Bouncy Castle Provider
# classes it references reflectively.
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**

# zstd-jni loads its native bridge class by name and calls native methods on it -
# names must survive obfuscation.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Apache Commons Compress and XZ for Java pick codecs by reflection in a few paths.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

-dontwarn org.slf4j.**

# javax.annotation.* (JSR-305) is a compile-only annotation dependency pulled in by
# Tink (via androidx.security.crypto) - never present or needed at runtime.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
