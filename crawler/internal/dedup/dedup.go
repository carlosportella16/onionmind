package dedup

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// oversizedRetention bounds how long a skipped-for-size marker survives — long enough to
// investigate or reprocess after raising limits, but not forever (fix-ingestion-stability).
const oversizedRetention = 30 * 24 * time.Hour

type Dedup struct {
	client *redis.Client
	ttl    time.Duration
}

func New(addr string, ttl time.Duration) *Dedup {
	return &Dedup{
		client: redis.NewClient(&redis.Options{Addr: addr}),
		ttl:    ttl,
	}
}

// SeenRecently returns true if the (already normalized) URL was seen within the TTL.
// Fails open (returns false) if Redis is unreachable — dedup must never block the crawler.
func (d *Dedup) SeenRecently(canonicalURL string) bool {
	val, err := d.client.Exists(context.Background(), "seen:"+canonicalURL).Result()
	if err != nil {
		return false
	}
	return val > 0
}

// MarkSeen records the URL as visited, with expiration.
func (d *Dedup) MarkSeen(canonicalURL string) {
	d.client.Set(context.Background(), "seen:"+canonicalURL, "1", d.ttl)
}

// MarkOversized records that a page was skipped because it exceeded the publish size
// ceiling, so it stays queryable and identifiable for later reprocessing instead of
// only ever existing as an ERROR log line (fix-ingestion-stability).
func (d *Dedup) MarkOversized(url string, sizeBytes int) {
	key := "skipped:oversized:" + hashURL(url)
	value := fmt.Sprintf("%s|%d|%s", url, sizeBytes, time.Now().UTC().Format(time.RFC3339))
	d.client.Set(context.Background(), key, value, oversizedRetention)
}

func hashURL(url string) string {
	sum := sha1.Sum([]byte(url))
	return hex.EncodeToString(sum[:])
}
