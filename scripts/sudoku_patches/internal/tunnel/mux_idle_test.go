package tunnel

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestMuxIdleCloserClosesOnlyAfterIdleTimeout(t *testing.T) {
	local, remote := net.Pipe()
	defer remote.Close()
	go func() { _, _ = io.Copy(io.Discard, remote) }()

	sess := newMuxSession(local, nil)
	d := &MuxDialer{session: sess}
	closer := MuxIdleCloser{Dialer: d, Timeout: time.Minute}
	start := time.Now()

	if closer.Check(start) {
		t.Fatal("closed on first sample")
	}
	st := newMuxStream(sess, sess.nextStreamID())
	sess.registerStream(st)
	if closer.Check(start.Add(2 * time.Minute)) {
		t.Fatal("closed while a stream was open")
	}
	sess.removeStream(st.id)
	if closer.Check(start.Add(150 * time.Second)) {
		t.Fatal("closed before the idle timeout elapsed")
	}
	if !closer.Check(start.Add(181 * time.Second)) {
		t.Fatal("did not close the idle session")
	}
	if !sess.isClosed() || d.session != nil {
		t.Fatal("idle session was not discarded")
	}
}
