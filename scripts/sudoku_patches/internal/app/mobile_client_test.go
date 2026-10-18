package app

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"

	"github.com/SUDOKU-ASCII/sudoku/internal/config"
)

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func TestMobileClientControllerSwitchesModeLive(t *testing.T) {
	apiAddr := net.JoinHostPort("127.0.0.1", itoa(freePort(t)))
	cfg := &config.Config{
		Mode:               "client",
		LocalPort:          freePort(t),
		ServerAddress:      "127.0.0.1:9",
		Key:                "test-key",
		ProxyMode:          "global",
		ExternalController: apiAddr,
		Secret:             "s3cret",
	}
	if err := cfg.Finalize(); err != nil {
		t.Fatalf("finalize: %v", err)
	}
	inst, err := StartMobileClient(cfg)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	defer inst.Stop()

	call := func(method, path, body, token string) (*http.Response, []byte) {
		req, _ := http.NewRequest(method, "http://"+apiAddr+path, strings.NewReader(body))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp, b
	}

	if resp, _ := call("GET", "/configs", "", ""); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing token: got %d", resp.StatusCode)
	}
	for _, tc := range []struct{ send, want string }{{"direct", "direct"}, {"rule", "pac"}, {"global", "global"}} {
		resp, _ := call("PATCH", "/configs", `{"mode":"`+tc.send+`"}`, "s3cret")
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("patch %s: %d", tc.send, resp.StatusCode)
		}
		_, b := call("GET", "/configs", "", "s3cret")
		var got struct {
			Mode string `json:"mode"`
		}
		if err := json.Unmarshal(b, &got); err != nil {
			t.Fatalf("configs: %s %v", b, err)
		}
		// mihomo calls PAC "rule"; accept either spelling.
		if got.Mode != tc.want && got.Mode != tc.send {
			t.Fatalf("after %s: mode=%q", tc.send, got.Mode)
		}
	}
	if resp, _ := call("GET", "/version", "", "s3cret"); resp.StatusCode != http.StatusOK {
		t.Fatalf("version: %d", resp.StatusCode)
	}
}

func itoa(n int) string {
	b, _ := json.Marshal(n)
	return string(b)
}
