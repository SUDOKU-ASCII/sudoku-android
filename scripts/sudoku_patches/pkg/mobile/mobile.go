package mobile

import (
	"bufio"
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

	sudokuapis "github.com/SUDOKU-ASCII/sudoku/apis"
	"github.com/SUDOKU-ASCII/sudoku/internal/app"
	"github.com/SUDOKU-ASCII/sudoku/internal/config"
	"github.com/SUDOKU-ASCII/sudoku/internal/tunnel"
	sudokukey "github.com/SUDOKU-ASCII/sudoku/pkg/crypto"
	sudokutable "github.com/SUDOKU-ASCII/sudoku/pkg/obfs/sudoku"
	"github.com/coder/websocket"
)

const sudokuTCPSubprotocol = "sudoku-tcp-v1"
const latencyProbeTarget = "i.ytimg.com:443"
const latencyProbeServerName = "i.ytimg.com"
const latencyProbePath = "/generate_204"

type latencyProbeResult struct {
	LatencyMs     int64  `json:"latency_ms"`
	StatusCode    int    `json:"status_code"`
	ConnectOK     bool   `json:"connect_ok"`
	CheckedAtUnix int64  `json:"checked_at_unix"`
	Error         string `json:"error"`
}

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

var (
	mu              sync.Mutex
	instance        *app.MobileInstance
	reverseInstance *reverseForwardInstance
	reverseStatus   reverseForwardStatus
	coreLocalPort   int32
)

func normalizeClientProbeConfig(cfg *config.Config) error {
	if cfg == nil {
		return fmt.Errorf("nil config")
	}
	switch strings.ToLower(strings.TrimSpace(cfg.HTTPMask.Mode)) {
	case "xhttp":
		cfg.HTTPMask.Mode = "stream"
	case "pht":
		cfg.HTTPMask.Mode = "poll"
	}
	return cfg.Finalize()
}

func tableSeedKey(key string) string {
	trimmed := strings.TrimSpace(key)
	if trimmed == "" {
		return ""
	}
	pubKeyPoint, err := sudokukey.RecoverPublicKey(trimmed)
	if err != nil {
		return trimmed
	}
	return sudokukey.EncodePoint(pubKeyPoint)
}

func buildLatencyProtocolConfig(cfg *config.Config) (*sudokuapis.ProtocolConfig, error) {
	if cfg == nil {
		return nil, fmt.Errorf("nil config")
	}
	seedKey := tableSeedKey(cfg.Key)
	proto := sudokuapis.DefaultConfig()
	proto.ServerAddress = strings.TrimSpace(cfg.ServerAddress)
	proto.TargetAddress = latencyProbeTarget
	proto.Key = strings.TrimSpace(cfg.Key)
	proto.AEADMethod = strings.TrimSpace(cfg.AEAD)
	proto.PaddingMin = cfg.PaddingMin
	proto.PaddingMax = cfg.PaddingMax
	proto.EnablePureDownlink = cfg.EnablePureDownlink
	proto.DisableHTTPMask = cfg.HTTPMask.Disable
	proto.HTTPMaskMode = strings.TrimSpace(cfg.HTTPMask.Mode)
	proto.HTTPMaskTLSEnabled = cfg.HTTPMask.TLS
	proto.HTTPMaskHost = strings.TrimSpace(cfg.HTTPMask.Host)
	proto.HTTPMaskPathRoot = strings.TrimSpace(cfg.HTTPMask.PathRoot)
	proto.HTTPMaskMultiplex = strings.TrimSpace(cfg.HTTPMask.Multiplex)

	patterns := cfg.CustomTables
	if len(patterns) == 0 && strings.TrimSpace(cfg.CustomTable) != "" {
		patterns = []string{cfg.CustomTable}
	}
	if len(patterns) == 0 {
		patterns = []string{""}
	}
	tableSet, err := sudokutable.NewTableSet(seedKey, cfg.ASCII, patterns)
	if err != nil {
		return nil, err
	}
	proto.Tables = tableSet.Candidates()
	if err := proto.ValidateClient(); err != nil {
		return nil, err
	}
	return proto, nil
}

func probeLatency(jsonConfig string) latencyProbeResult {
	result := latencyProbeResult{
		LatencyMs:     -1,
		CheckedAtUnix: time.Now().UnixMilli(),
	}
	start := time.Now()

	var cfg config.Config
	if err := json.Unmarshal([]byte(jsonConfig), &cfg); err != nil {
		result.Error = fmt.Sprintf("parse config: %v", err)
		return result
	}
	if err := normalizeClientProbeConfig(&cfg); err != nil {
		result.Error = err.Error()
		return result
	}
	proto, err := buildLatencyProtocolConfig(&cfg)
	if err != nil {
		result.Error = err.Error()
		return result
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := sudokuapis.Dial(ctx, proto)
	if err != nil {
		result.Error = err.Error()
		return result
	}
	defer conn.Close()

	tlsConn := tls.Client(conn, &tls.Config{ServerName: latencyProbeServerName})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		result.Error = err.Error()
		return result
	}
	if _, err := io.WriteString(tlsConn, "GET "+latencyProbePath+" HTTP/1.1\r\nHost: "+latencyProbeServerName+"\r\nConnection: close\r\n\r\n"); err != nil {
		result.Error = err.Error()
		return result
	}
	line, err := bufio.NewReader(tlsConn).ReadString('\n')
	if err != nil {
		result.Error = err.Error()
		return result
	}

	parts := strings.Fields(strings.TrimSpace(line))
	if len(parts) >= 2 {
		result.StatusCode, _ = strconv.Atoi(parts[1])
	}
	result.ConnectOK = result.StatusCode >= 200 && result.StatusCode < 500
	result.LatencyMs = time.Since(start).Milliseconds()
	result.CheckedAtUnix = time.Now().UnixMilli()
	if !result.ConnectOK {
		result.Error = fmt.Sprintf("unexpected HTTP status %d", result.StatusCode)
	}
	return result
}

func ProbeLatencyJson(jsonConfig string) string {
	result := probeLatency(jsonConfig)
	b, err := json.Marshal(result)
	if err != nil {
		return `{"latency_ms":-1,"status_code":0,"connect_ok":false,"error":"marshal latency result failed"}`
	}
	return string(b)
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
