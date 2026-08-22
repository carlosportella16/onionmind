package dedup

import (
	"context"
	"fmt"
	"testing"
	"time"
)

func newTestDedup(t *testing.T, ttl time.Duration) *Dedup {
	t.Helper()
	d := New("localhost:6379", ttl)
	if err := d.client.Ping(context.Background()).Err(); err != nil {
		t.Skipf("redis not reachable at localhost:6379: %v", err)
	}
	return d
}

func TestSeenRecently(t *testing.T) {
	d := newTestDedup(t, time.Minute)
	url := fmt.Sprintf("http://new-%d.onion", time.Now().UnixNano())

	if d.SeenRecently(url) {
		t.Fatalf("new URL should not be seen recently")
	}

	d.MarkSeen(url)

	if !d.SeenRecently(url) {
		t.Fatalf("marked URL should be seen recently")
	}
}

func TestSeenRecently_TTLExpires(t *testing.T) {
	d := newTestDedup(t, 500*time.Millisecond)
	url := fmt.Sprintf("http://ttl-%d.onion", time.Now().UnixNano())

	d.MarkSeen(url)
	if !d.SeenRecently(url) {
		t.Fatalf("marked URL should be seen recently before TTL")
	}

	time.Sleep(700 * time.Millisecond)

	if d.SeenRecently(url) {
		t.Fatalf("URL should no longer be seen recently after TTL expires")
	}
}
