package app

import (
	"net"
	"sync/atomic"
)

type TrafficStats struct {
	DirectTx uint64 `json:"direct_tx"`
	DirectRx uint64 `json:"direct_rx"`
	ProxyTx  uint64 `json:"proxy_tx"`
	ProxyRx  uint64 `json:"proxy_rx"`
}

var (
	trafficDirectTx uint64
	trafficDirectRx uint64
	trafficProxyTx  uint64
	trafficProxyRx  uint64
)

const (
	trafficKindDirect = 0
	trafficKindProxy  = 1
)

type countingConn struct {
	net.Conn
	kind int
}

func (c *countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		if c.kind == trafficKindProxy {
			atomic.AddUint64(&trafficProxyRx, uint64(n))
		} else {
			atomic.AddUint64(&trafficDirectRx, uint64(n))
		}
	}
	return n, err
}

func (c *countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		if c.kind == trafficKindProxy {
			atomic.AddUint64(&trafficProxyTx, uint64(n))
		} else {
			atomic.AddUint64(&trafficDirectTx, uint64(n))
		}
	}
	return n, err
}

func wrapConnForTrafficStats(conn net.Conn, shouldProxy bool) net.Conn {
	if conn == nil {
		return conn
	}
	kind := trafficKindDirect
	if shouldProxy {
		kind = trafficKindProxy
	}
	return &countingConn{Conn: conn, kind: kind}
}

func SnapshotTrafficStats() TrafficStats {
	return TrafficStats{
		DirectTx: atomic.LoadUint64(&trafficDirectTx),
		DirectRx: atomic.LoadUint64(&trafficDirectRx),
		ProxyTx:  atomic.LoadUint64(&trafficProxyTx),
		ProxyRx:  atomic.LoadUint64(&trafficProxyRx),
	}
}

func ResetTrafficStats() {
	atomic.StoreUint64(&trafficDirectTx, 0)
	atomic.StoreUint64(&trafficDirectRx, 0)
	atomic.StoreUint64(&trafficProxyTx, 0)
	atomic.StoreUint64(&trafficProxyRx, 0)
}
