LOCAL_PATH := $(call my-dir)

# From app/src/main/jni/hev-socks5-tunnel up to sudodroid root:
# hev-socks5-tunnel -> jni -> main -> src -> app -> sudodroid
HEV_ROOT := $(LOCAL_PATH)/../../../../../third_party/hev-socks5-tunnel

# Delegate to upstream Android.mk to build libhev-socks5-tunnel (including its
# own JNI glue). PKGNAME/CLSNAME will be provided via Application.mk.
include $(HEV_ROOT)/Android.mk
