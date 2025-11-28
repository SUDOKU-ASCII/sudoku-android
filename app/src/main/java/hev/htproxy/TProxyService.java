package hev.htproxy;

/**
 * Minimal host class for hev-socks5-tunnel JNI bindings.
 * The upstream hev-jni.c expects this exact package/class name and method
 * signatures, matching its default PKGNAME/CLSNAME.
 */
public class TProxyService {
    public native void TProxyStartService(String configPath, int tunFd);
    public native void TProxyStopService();
    public native long[] TProxyGetStats();
}

