package dedup

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

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
