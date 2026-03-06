package mobile

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/saba-futai/sudoku/internal/app"
	"github.com/saba-futai/sudoku/internal/config"
	"github.com/saba-futai/sudoku/internal/tunnel"
	"github.com/saba-futai/sudoku/pkg/dnsutil"
)

const sudokuTCPSubprotocol = "sudoku-tcp-v1"

type reverseForwardStatus struct {
	Running    bool   `json:"running"`
	ListenAddr string `json:"listen_addr"`
	DialURL    string `json:"dial_url"`
	Insecure   bool   `json:"insecure"`
	LastError  string `json:"last_error"`
}

type reverseForwardInstance struct {
	ln         net.Listener
	done       chan struct{}
	listenAddr string
	dialURL    string
	insecure   bool
}

type resolvedServerAddress struct {
	Host          string `json:"host"`
	Port          int    `json:"port"`
	ServerAddress string `json:"server_address"`
	SNIHost       string `json:"sni_host,omitempty"`
	Error         string `json:"error,omitempty"`
}

var (
	mu              sync.Mutex
	instance        *app.MobileInstance
	reverseInstance *reverseForwardInstance
	reverseStatus   reverseForwardStatus
	coreLocalPort   int32
)

func encodeResolvedServerAddress(result resolvedServerAddress) string {
	b, err := json.Marshal(result)
	if err != nil {
		return "{\"error\":\"encode resolved server address failed\"}"
	}
	return string(b)
}

func normalizeIPMode(mode string) string {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "ipv4_only", "ipv4":
		return "ipv4_only"
	case "ipv6_preferred", "ipv6":
		return "ipv6_preferred"
	default:
		return "default"
	}
}

func orderIPsByMode(ips []net.IP, mode string) []net.IP {
	if len(ips) == 0 {
		return nil
	}

	mode = normalizeIPMode(mode)
	v4 := make([]net.IP, 0, len(ips))
	v6 := make([]net.IP, 0, len(ips))
	for _, ip := range ips {
		if ip == nil {
			continue
		}
		if ip4 := ip.To4(); ip4 != nil {
			v4 = append(v4, ip4)
			continue
		}
		if ip16 := ip.To16(); ip16 != nil {
			v6 = append(v6, ip16)
		}
	}

	switch mode {
	case "ipv4_only":
		return v4
	case "ipv6_preferred":
		return append(v6, v4...)
	default:
		return append(v4, v6...)
	}
}

func resolveHost(host string) string {
	return strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(host, "["), "]"))
}

func ResolveServerAddressJson(host string, port int, ipMode string) string {
	host = resolveHost(host)
	result := resolvedServerAddress{
		Host: host,
		Port: port,
	}

	if host == "" {
		result.Error = "empty host"
		return encodeResolvedServerAddress(result)
	}
	if port <= 0 || port > 65535 {
		result.Error = fmt.Sprintf("invalid port: %d", port)
		return encodeResolvedServerAddress(result)
	}

	if ip := net.ParseIP(host); ip != nil {
		ipText := ip.String()
		result.Host = ipText
		result.ServerAddress = net.JoinHostPort(ipText, strconv.Itoa(port))
		return encodeResolvedServerAddress(result)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	ips, err := dnsutil.LookupIPsWithCache(ctx, host)
	if err != nil {
		result.Error = err.Error()
		return encodeResolvedServerAddress(result)
	}

	ordered := orderIPsByMode(ips, ipMode)
	if len(ordered) == 0 {
		switch normalizeIPMode(ipMode) {
		case "ipv4_only":
			result.Error = fmt.Sprintf("No IPv4 address found for %s", host)
		default:
			result.Error = fmt.Sprintf("No IPv4/IPv6 address found for %s", host)
		}
		return encodeResolvedServerAddress(result)
	}

	selected := ordered[0].String()
	result.Host = selected
	result.SNIHost = host
	result.ServerAddress = net.JoinHostPort(selected, strconv.Itoa(port))
	return encodeResolvedServerAddress(result)
}

func dialSocks5(ctx context.Context, proxyAddr, targetAddr string) (net.Conn, error) {
	proxyAddr = strings.TrimSpace(proxyAddr)
	targetAddr = strings.TrimSpace(targetAddr)
	if proxyAddr == "" {
		return nil, fmt.Errorf("empty proxy address")
	}
	if targetAddr == "" {
		return nil, fmt.Errorf("empty target address")
	}

	host, portStr, err := net.SplitHostPort(targetAddr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return nil, fmt.Errorf("invalid port: %q", portStr)
	}

	if ctx == nil {
		ctx = context.Background()
	}
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", proxyAddr)
	if err != nil {
		return nil, err
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	fail := func(e error) (net.Conn, error) {
		_ = conn.Close()
		return nil, e
	}

	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return fail(err)
	}
	choice := make([]byte, 2)
	if _, err := io.ReadFull(conn, choice); err != nil {
		return fail(err)
	}
	if choice[0] != 0x05 || choice[1] != 0x00 {
		return fail(fmt.Errorf("socks5 no-auth not accepted"))
	}

	req := make([]byte, 0, 6+len(host))
	req = append(req, 0x05, 0x01, 0x00)

	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else if ip16 := ip.To16(); ip16 != nil {
			req = append(req, 0x04)
			req = append(req, ip16...)
		} else {
			return fail(fmt.Errorf("invalid ip: %q", host))
		}
	} else {
		if len(host) > 255 {
			return fail(fmt.Errorf("domain too long"))
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, []byte(host)...)
	}

	portBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(portBuf, uint16(port))
	req = append(req, portBuf...)

	if _, err := conn.Write(req); err != nil {
		return fail(err)
	}

	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return fail(err)
	}
	if hdr[0] != 0x05 {
		return fail(fmt.Errorf("invalid socks5 reply version"))
	}
	if hdr[1] != 0x00 {
		return fail(fmt.Errorf("socks5 connect failed (rep=%d)", hdr[1]))
	}

	atyp := hdr[3]
	var addrLen int
	switch atyp {
	case 0x01:
		addrLen = 4
	case 0x04:
		addrLen = 16
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return fail(err)
		}
		addrLen = int(l[0])
	default:
		return fail(fmt.Errorf("unknown socks5 atyp=%d", atyp))
	}
	if addrLen > 0 {
		if _, err := io.ReadFull(conn, make([]byte, addrLen)); err != nil {
			return fail(err)
		}
	}
	if _, err := io.ReadFull(conn, make([]byte, 2)); err != nil {
		return fail(err)
	}

	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func dialTCPWithFallback(ctx context.Context, network, addr string) (net.Conn, error) {
	if ctx == nil {
		ctx = context.Background()
	}

	directCtx, directCancel := context.WithTimeout(ctx, 3*time.Second)
	conn, err := (&net.Dialer{}).DialContext(directCtx, network, addr)
	directCancel()
	if err == nil {
		return conn, nil
	}

	port := atomic.LoadInt32(&coreLocalPort)
	if port <= 0 || port > 65535 {
		return nil, err
	}
	proxyAddr := fmt.Sprintf("127.0.0.1:%d", port)
	pConn, pErr := dialSocks5(ctx, proxyAddr, addr)
	if pErr == nil {
		return pConn, nil
	}
	return nil, fmt.Errorf("dial failed (direct: %v; via socks5(%s): %v)", err, proxyAddr, pErr)
}

func wsHTTPClientForDial(insecure bool, scheme string) *http.Client {
	tr := &http.Transport{
		ForceAttemptHTTP2: false,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialTCPWithFallback(ctx, network, addr)
		},
	}
	if insecure && strings.EqualFold(scheme, "wss") {
		tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return &http.Client{Transport: tr}
}

func stopReverseForwarderLocked() {
	if reverseInstance == nil {
		reverseStatus.Running = false
		reverseStatus.LastError = ""
		return
	}

	ln := reverseInstance.ln
	done := reverseInstance.done
	reverseInstance = nil

	if ln != nil {
		_ = ln.Close()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
			log.Printf("[Mobile][Reverse] timeout while stopping forwarder")
		}
	}
	reverseStatus.Running = false
	reverseStatus.LastError = ""
}

func startReverseForwarderLocked(listenAddr, dialURL string, insecure bool) error {
	listenAddr = strings.TrimSpace(listenAddr)
	dialURL = strings.TrimSpace(dialURL)
	if listenAddr == "" {
		return fmt.Errorf("missing listen address")
	}
	if dialURL == "" {
		return fmt.Errorf("missing dial url")
	}

	u, err := url.Parse(dialURL)
	if err != nil || u == nil {
		return fmt.Errorf("invalid dial url: %q", dialURL)
	}
	switch strings.ToLower(u.Scheme) {
	case "ws", "wss":
	default:
		return fmt.Errorf("dial url must be ws:// or wss:// (got %q)", u.Scheme)
	}
	if strings.TrimSpace(u.Host) == "" {
		return fmt.Errorf("dial url missing host: %q", dialURL)
	}

	wsHTTPClient := wsHTTPClientForDial(insecure, u.Scheme)

	{
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		ws, _, err := websocket.Dial(ctx, dialURL, &websocket.DialOptions{
			Subprotocols:    []string{sudokuTCPSubprotocol},
			CompressionMode: websocket.CompressionDisabled,
			HTTPClient:      wsHTTPClient,
		})
		cancel()
		if err != nil {
			return fmt.Errorf("preflight: %w", err)
		}
		if ws.Subprotocol() != sudokuTCPSubprotocol {
			_ = ws.Close(websocket.StatusPolicyViolation, "subprotocol required")
			return fmt.Errorf("server did not accept %s", sudokuTCPSubprotocol)
		}
		_ = ws.Close(websocket.StatusNormalClosure, "ok")
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}

	inst := &reverseForwardInstance{
		ln:         ln,
		done:       make(chan struct{}),
		listenAddr: listenAddr,
		dialURL:    dialURL,
		insecure:   insecure,
	}
	reverseInstance = inst
	reverseStatus = reverseForwardStatus{
		Running:    true,
		ListenAddr: listenAddr,
		DialURL:    dialURL,
		Insecure:   insecure,
		LastError:  "",
	}

	go func(localInst *reverseForwardInstance) {
		defer close(localInst.done)
		for {
			c, err := localInst.ln.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				mu.Lock()
				reverseStatus.LastError = err.Error()
				mu.Unlock()
				continue
			}

			go func(local net.Conn) {
				if local == nil {
					return
				}
				defer local.Close()

				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				ws, _, err := websocket.Dial(ctx, localInst.dialURL, &websocket.DialOptions{
					Subprotocols:    []string{sudokuTCPSubprotocol},
					CompressionMode: websocket.CompressionDisabled,
					HTTPClient:      wsHTTPClient,
				})
				cancel()
				if err != nil {
					mu.Lock()
					reverseStatus.LastError = err.Error()
					mu.Unlock()
					return
				}
				if ws.Subprotocol() != sudokuTCPSubprotocol {
					_ = ws.Close(websocket.StatusPolicyViolation, "subprotocol required")
					mu.Lock()
					reverseStatus.LastError = "server did not accept sudoku-tcp-v1"
					mu.Unlock()
					return
				}

				mu.Lock()
				reverseStatus.LastError = ""
				mu.Unlock()

				wsConn := websocket.NetConn(context.Background(), ws, websocket.MessageBinary)
				tunnel.PipeConn(local, wsConn)
			}(c)
		}
	}(inst)

	return nil
}

func StartReverseForwarder(listenAddr, dialURL string, insecure bool) error {
	mu.Lock()
	defer mu.Unlock()

	stopReverseForwarderLocked()
	return startReverseForwarderLocked(listenAddr, dialURL, insecure)
}

func StopReverseForwarder() {
	mu.Lock()
	defer mu.Unlock()
	stopReverseForwarderLocked()
}

func GetReverseForwardStatusJson() string {
	mu.Lock()
	status := reverseStatus
	mu.Unlock()
	b, err := json.Marshal(status)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func Start(jsonConfig string) error {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		instance.Stop()
		instance = nil
	}
	app.ResetTrafficStats()

	var cfg config.Config
	if err := json.Unmarshal([]byte(jsonConfig), &cfg); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	switch strings.ToLower(strings.TrimSpace(cfg.HTTPMask.Mode)) {
	case "xhttp":
		cfg.HTTPMask.Mode = "stream"
	case "pht":
		cfg.HTTPMask.Mode = "poll"
	}
	if err := cfg.Finalize(); err != nil {
		return err
	}
	atomic.StoreInt32(&coreLocalPort, int32(cfg.LocalPort))

	inst, err := app.StartMobileClient(&cfg)
	if err != nil {
		return err
	}
	instance = inst
	return nil
}

func GetTrafficStatsJson() string {
	stats := app.SnapshotTrafficStats()
	b, err := json.Marshal(stats)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func ResetTrafficStats() {
	app.ResetTrafficStats()
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		instance.Stop()
		instance = nil
	}
	app.ResetTrafficStats()
}
