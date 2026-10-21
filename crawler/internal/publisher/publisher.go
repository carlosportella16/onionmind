package publisher

import (
	"encoding/json"
	"errors"
	"log/slog"
	"strings"

	"github.com/IBM/sarama"

	"github.com/carlosportella16/onionmind/crawler/internal/connector"
)

type Publisher struct {
	producer sarama.SyncProducer
	topic    string
}

// sizeMarginPercent is headroom for JSON envelope overhead (base64-ish expansion of
// binary-looking HTML, field names, escaping) on top of the crawler's own configured
// max_page_size_mb — a page right at the fetch ceiling still needs to fit once wrapped.
const sizeMarginPercent = 10

func New(brokers []string, topic string, maxPageSizeMB int) (*Publisher, error) {
	config := sarama.NewConfig()
	config.Producer.Return.Successes = true
	config.Producer.Idempotent = true // dedup at the broker
	config.Producer.RequiredAcks = sarama.WaitForAll
	config.Net.MaxOpenRequests = 1 // required for idempotent producers
	config.Producer.MaxMessageBytes = maxMessageBytesFor(maxPageSizeMB)

	producer, err := sarama.NewSyncProducer(brokers, config)
	if err != nil {
		return nil, err
	}
	return &Publisher{producer: producer, topic: topic}, nil
}

// maxMessageBytesFor turns the crawler's own fetch-size ceiling (max_page_size_mb) into
// the producer's message-size ceiling. Without this, the producer silently kept sarama's
// 1MB default regardless of how large a page the fetcher was configured to accept —
// pages the crawler happily fetched then failed to publish. A misconfigured (zero or
// negative) limit falls back to sarama's own default instead of producing a bogus ceiling.
func maxMessageBytesFor(maxPageSizeMB int) int {
	if maxPageSizeMB <= 0 {
		return sarama.NewConfig().Producer.MaxMessageBytes
	}
	return maxPageSizeMB * 1024 * 1024 * (100 + sizeMarginPercent) / 100
}

func (p *Publisher) PublishRawPage(page *connector.RawPage) error {
	payload, err := json.Marshal(page)
	if err != nil {
		return err
	}
	msg := &sarama.ProducerMessage{
		Topic: p.topic,
		Key:   sarama.StringEncoder(page.URL), // partition by URL -> per-site order
		Value: sarama.ByteEncoder(payload),
	}
	partition, offset, err := p.producer.SendMessage(msg)
	if err != nil {
		return err
	}
	slog.Info("published", "url", page.URL, "partition", partition, "offset", offset)
	return nil
}

func (p *Publisher) Close() error { return p.producer.Close() }

// IsMessageSizeError reports whether err is PublishRawPage rejecting a message for being
// too large — either caught client-side against our own MaxMessageBytes, or rejected by
// the broker's kafka_batch_max_bytes. Callers use this to record an oversized page instead
// of just logging it (fix-ingestion-stability, defense-in-depth after aligning the two
// ceilings with max_page_size_mb in New).
func IsMessageSizeError(err error) bool {
	var cfgErr sarama.ConfigurationError
	if errors.As(err, &cfgErr) {
		return strings.Contains(string(cfgErr), "MaxMessageBytes")
	}
	return errors.Is(err, sarama.ErrMessageSizeTooLarge)
}
