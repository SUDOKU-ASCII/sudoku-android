package com.futaiii.sudodroid.vpn

import hev.htproxy.TProxyService

object Socks5TunnelNative {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    private val impl = TProxyService()

    fun isLoaded(): Boolean = true

    /**
     * 封装官方 sockstun 的 JNI 接口，通过 hev.htproxy.TProxyService 代理：
     * TProxyStartService(configPath, tunFd) / TProxyStopService() / TProxyIsRunning()
     */
    fun start(configPath: String, tunFd: Int): Int {
        return try {
            if (impl.TProxyStartService(configPath, tunFd)) 0 else -1
        } catch (_: Throwable) {
            -1
        }
    }

    fun stop() {
        runCatching { impl.TProxyStopService() }
    }

    fun isRunning(): Boolean {
        return runCatching { impl.TProxyIsRunning() }.getOrDefault(false)
    }
}
