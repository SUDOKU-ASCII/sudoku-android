//go:build sudoku_patch

package dnsutil

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	envDNSSystemResolver = "SUDOKU_DNS_SYSTEM"
	envDNSTimeoutMs      = "SUDOKU_DNS_TIMEOUT_MS"
)

var (
	fakeIPBenchNet = &net.IPNet{IP: net.IPv4(198, 18, 0, 0), Mask: net.CIDRMask(15, 32)}
	fakeIPCGNATNet = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}
)

func init() {
	if strings.TrimSpace(os.Getenv(envDNSSystemResolver)) == "1" {
		return
	}
	if defaultResolver == nil {
		return
	}
	defaultResolver.lookupFn = lookupIPDoHFirst
}

type dohClient struct {
	host      string
	path      string
	bootstrap []string
	next      uint32
	client    *http.Client
}

func newDoHClient(host string, path string, bootstrap []string) *dohClient {
	host = strings.TrimSpace(host)
	path = strings.TrimSpace(path)
	bootstrap = filterIPs(bootstrap)
	if host == "" || path == "" || len(bootstrap) == 0 {
		return nil
	}

	c := &dohClient{host: host, path: path, bootstrap: bootstrap}
	tr := &http.Transport{
		TLSClientConfig: &tls.Config{ServerName: host},
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			d := &net.Dialer{Timeout: 3 * time.Second}
			h, port, err := net.SplitHostPort(addr)
			if err != nil {
				return d.DialContext(ctx, network, addr)
			}
			if strings.EqualFold(h, host) {
				idx := atomic.AddUint32(&c.next, 1)
				ip := c.bootstrap[int(idx)%len(c.bootstrap)]
				return d.DialContext(ctx, network, net.JoinHostPort(ip, port))
			}
			return d.DialContext(ctx, network, addr)
		},
	}
	c.client = &http.Client{
		Timeout:   3 * time.Second,
		Transport: tr,
	}
	return c
}

func (c *dohClient) Exchange(ctx context.Context, query []byte) ([]byte, error) {
	if c == nil {
		return nil, errors.New("nil doh client")
	}
	if ctx == nil {
		ctx = context.Background()
	}

	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		"https://"+c.host+c.path,
		bytes.NewReader(query),
	)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("doh http %s", resp.Status)
	}
	return io.ReadAll(resp.Body)
}

var dohClients = []*dohClient{
	newDoHClient("dns.alidns.com", "/dns-query", []string{"223.5.5.5", "223.6.6.6"}),
	newDoHClient("doh.pub", "/dns-query", []string{"119.29.29.29", "119.28.28.28"}),
	newDoHClient("cloudflare-dns.com", "/dns-query", []string{"1.1.1.1", "1.0.0.1"}),
}

func lookupIPDoHFirst(ctx context.Context, network, host string) ([]net.IP, error) {
	host = strings.TrimSpace(host)
	if host == "" {
		return nil, fmt.Errorf("empty host")
	}

	if ip := net.ParseIP(strings.Trim(host, "[]")); ip != nil {
		return []net.IP{ip}, nil
	}

	if isLikelyLocalHostname(host) {
		ips, err := net.DefaultResolver.LookupIP(ctx, network, host)
		return filterBogusIPs(normalizeIPs(ips)), err
	}

	if ctx == nil {
		ctx = context.Background()
	}
	if _, ok := ctx.Deadline(); !ok {
		timeout := parseTimeoutEnv()
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}

	qtype := dnsmessage.TypeA
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "ip6":
		qtype = dnsmessage.TypeAAAA
	case "ip4", "":
		qtype = dnsmessage.TypeA
	default:
		ips, err := net.DefaultResolver.LookupIP(ctx, network, host)
		ips = filterBogusIPs(normalizeIPs(ips))
		if len(ips) > 0 {
			return ips, nil
		}
		return nil, err
	}

	query, err := buildDNSQuery(host, qtype)
	if err != nil {
		return nil, err
	}

	var lastErr error
	for _, c := range dohClients {
		if c == nil {
			continue
		}
		resp, err := c.Exchange(ctx, query)
		if err != nil {
			lastErr = err
			continue
		}
		ips, parseErr := parseDNSAnswerIPs(resp, qtype)
		if parseErr != nil {
			lastErr = parseErr
			continue
		}
		ips = filterBogusIPs(normalizeIPs(ips))
		if len(ips) > 0 {
			return ips, nil
		}
	}

	sysIPs, sysErr := net.DefaultResolver.LookupIP(ctx, network, host)
	sysIPs = filterBogusIPs(normalizeIPs(sysIPs))
	if len(sysIPs) > 0 {
		return sysIPs, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, sysErr
}

func buildDNSQuery(host string, qtype dnsmessage.Type) ([]byte, error) {
	host = strings.TrimSuffix(host, ".")
	name, err := dnsmessage.NewName(host + ".")
	if err != nil {
		return nil, err
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		RecursionDesired: true,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(dnsmessage.Question{
		Name:  name,
		Type:  qtype,
		Class: dnsmessage.ClassINET,
	}); err != nil {
		return nil, err
	}
	return b.Finish()
}

func parseDNSAnswerIPs(resp []byte, qtype dnsmessage.Type) ([]net.IP, error) {
	var p dnsmessage.Parser
	if _, err := p.Start(resp); err != nil {
		return nil, err
	}
	for {
		_, err := p.Question()
		if err == dnsmessage.ErrSectionDone {
			break
		}
		if err != nil {
			return nil, err
		}
	}

	out := make([]net.IP, 0, 4)
	for {
		h, err := p.AnswerHeader()
		if err == dnsmessage.ErrSectionDone {
			break
		}
		if err != nil {
			return nil, err
		}
		switch h.Type {
		case dnsmessage.TypeA:
			if qtype != dnsmessage.TypeA {
				if err := p.SkipAnswer(); err != nil {
					return nil, err
				}
				continue
			}
			a, err := p.AResource()
			if err != nil {
				return nil, err
			}
			out = append(out, net.IPv4(a.A[0], a.A[1], a.A[2], a.A[3]))
		case dnsmessage.TypeAAAA:
			if qtype != dnsmessage.TypeAAAA {
				if err := p.SkipAnswer(); err != nil {
					return nil, err
				}
				continue
			}
			a, err := p.AAAAResource()
			if err != nil {
				return nil, err
			}
			out = append(out, append(net.IP(nil), a.AAAA[:]...))
		default:
			if err := p.SkipAnswer(); err != nil {
				return nil, err
			}
		}
	}
	return out, nil
}

func filterIPs(ips []string) []string {
	out := make([]string, 0, len(ips))
	for _, s := range ips {
		s = strings.TrimSpace(s)
		if s == "" || net.ParseIP(s) == nil {
			continue
		}
		out = append(out, s)
	}
	return out
}

func filterBogusIPs(ips []net.IP) []net.IP {
	if len(ips) == 0 {
		return nil
	}
	out := ips[:0]
	for _, ip := range ips {
		if ip == nil {
			continue
		}
		if ip4 := ip.To4(); ip4 != nil {
			if fakeIPBenchNet.Contains(ip4) || fakeIPCGNATNet.Contains(ip4) {
				continue
			}
			out = append(out, ip4)
			continue
		}
		if ip16 := ip.To16(); ip16 != nil {
			out = append(out, ip16)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func isLikelyLocalHostname(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	host = strings.TrimSuffix(host, ".")
	if host == "" {
		return false
	}
	if !strings.Contains(host, ".") {
		return true
	}
	if strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".lan") {
		return true
	}
	return false
}

func parseTimeoutEnv() time.Duration {
	raw := strings.TrimSpace(os.Getenv(envDNSTimeoutMs))
	if raw == "" {
		return 1600 * time.Millisecond
	}
	ms, err := time.ParseDuration(raw + "ms")
	if err == nil && ms > 0 {
		return ms
	}
	d, err := time.ParseDuration(raw)
	if err == nil && d > 0 {
		return d
	}
	return 1600 * time.Millisecond
}
