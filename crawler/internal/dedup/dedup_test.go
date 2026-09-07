package dedup

import (
	"context"
	"fmt"
	"strings"
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

func TestMarkOversized_RecordsURLAndSizeQueryably(t *testing.T) {
	d := newTestDedup(t, time.Minute)
	url := fmt.Sprintf("http://big-%d.onion/", time.Now().UnixNano())

	d.MarkOversized(url, 1987020)

	val, err := d.client.Get(context.Background(), "skipped:oversized:"+hashURL(url)).Result()
	if err != nil {
		t.Fatalf("expected oversized marker to be queryable, got error: %v", err)
	}
	if !strings.Contains(val, url) || !strings.Contains(val, "1987020") {
		t.Errorf("marker value = %q, want it to contain url and size", val)
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
