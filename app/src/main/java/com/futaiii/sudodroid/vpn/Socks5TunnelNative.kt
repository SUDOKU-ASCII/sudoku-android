package com.futaiii.sudodroid.vpn

import hev.htproxy.TProxyService

object Socks5TunnelNative {
    fun start(configPath: String, tunFd: Int): Int {
        return try {
            if (TProxyService.TProxyStartService(configPath, tunFd)) 0 else -1
        } catch (_: Throwable) {
            -1
        }
    }

    fun stop() {
        runCatching { TProxyService.TProxyStopService() }
    }

    fun isRunning(): Boolean {
        return runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
    }
}
