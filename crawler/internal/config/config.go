package config

import (
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

// Duration wraps time.Duration so it can be parsed from YAML strings like "45s" or "6h".
type Duration time.Duration

func (d *Duration) UnmarshalYAML(value *yaml.Node) error {
	parsed, err := time.ParseDuration(value.Value)
	if err != nil {
		return err
	}
	*d = Duration(parsed)
	return nil
}

func (d Duration) Duration() time.Duration { return time.Duration(d) }

type Config struct {
	Crawler  CrawlerConfig  `yaml:"crawler"`
	Tor      TorConfig      `yaml:"tor"`
	Redis    RedisConfig    `yaml:"redis"`
	Redpanda RedpandaConfig `yaml:"redpanda"`
	Frontier FrontierConfig `yaml:"frontier"`
}

type CrawlerConfig struct {
	Workers         int                  `yaml:"workers"`
	FetchTimeout    Duration             `yaml:"fetch_timeout"`
	MaxPageSizeMB   int                  `yaml:"max_page_size_mb"`
	PolitenessDelay Duration             `yaml:"politeness_delay"`
	CircuitBreaker  CircuitBreakerConfig `yaml:"circuit_breaker"`
}

type CircuitBreakerConfig struct {
	ConsecutiveFailures int      `yaml:"consecutive_failures"`
	Timeout             Duration `yaml:"timeout"`
}

type TorConfig struct {
	SocksAddr string `yaml:"socks_addr"`
}

type RedisConfig struct {
	Addr     string   `yaml:"addr"`
	DedupTTL Duration `yaml:"dedup_ttl"`
}

type RedpandaConfig struct {
	Brokers []string `yaml:"brokers"`
	Topic   string   `yaml:"topic"`
}

type FrontierConfig struct {
	Seeds        []string `yaml:"seeds"`
	MaxDepth     int      `yaml:"max_depth"`
	MaxQueueSize int      `yaml:"max_queue_size"`
}

// Load reads and parses the crawler config from the given YAML file path.
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}
