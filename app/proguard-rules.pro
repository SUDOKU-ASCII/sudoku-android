# Go mobile binding is loaded via reflection in GoCoreClient.
-keep class com.futaiii.sudoku.mobile.** { *; }

# The upstream AAR JNI uses this exact class and method names (RegisterNatives).
-keep class hev.htproxy.TProxyService { *; }
