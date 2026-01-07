# Keep JNI entrypoints: native code expects exact class/method signatures.
-keep class hev.htproxy.TProxyService { *; }

# Keep any native method names from being obfuscated/removed.
-keepclasseswithmembernames class * {
    native <methods>;
}

# gomobile bindings: Go JNI loader and generated Java API are referenced from native code.
-keep class go.** { *; }
-keep class com.futaiii.sudoku.** { *; }
