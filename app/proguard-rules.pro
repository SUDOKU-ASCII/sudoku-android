# Go mobile binding is loaded via reflection in GoCoreClient.
-keep class com.futaiii.sudoku.mobile.** { *; }

# hev-socks5-tunnel JNI uses hard-coded class/method names from C (RegisterNatives).
# Do not obfuscate this binding class or its method names.
-keep class hev.htproxy.TProxyService { *; }
