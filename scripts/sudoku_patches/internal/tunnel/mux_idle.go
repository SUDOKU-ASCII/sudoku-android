package tunnel

import (
	"errors"
	"time"
)

var errMuxIdle = errors.New("mux session idle")

// MuxIdleCloser closes the shared mux session after it has carried no streams
// for Timeout. Both peers send a keepalive frame every 15s on a live session,
// which keeps a phone's radio awake; closing idle sessions lets it sleep and the
// next Dial lazily opens a fresh session.
type MuxIdleCloser struct {
	Dialer  *MuxDialer
	Timeout time.Duration

	sess      *muxSession
	lastID    uint32
	idleSince time.Time
}

// Check samples the current session and closes it once idle long enough.
// It must be called from a single goroutine.
func (c *MuxIdleCloser) Check(now time.Time) bool {
	if c == nil || c.Dialer == nil || c.Timeout <= 0 {
		return false
	}
	d := c.Dialer
	d.mu.Lock()
	sess := d.session
	d.mu.Unlock()
	if sess == nil || sess.isClosed() {
		c.sess = nil
		return false
	}

	sess.streamsMu.Lock()
	active := len(sess.streams)
	id := sess.nextID
	sess.streamsMu.Unlock()

	// Any open stream, or a stream opened since the last sample, resets the timer.
	if sess != c.sess || active > 0 || id != c.lastID {
		c.sess, c.lastID, c.idleSince = sess, id, now
		return false
	}
	if now.Sub(c.idleSince) < c.Timeout {
		return false
	}
	d.discardSession(sess)
	sess.closeWithError(errMuxIdle)
	c.sess = nil
	return true
}
