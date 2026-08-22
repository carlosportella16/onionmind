package publisher

import (
	"encoding/json"
	"log/slog"

	"github.com/IBM/sarama"

	"github.com/carlosportella16/onionmind/crawler/internal/connector"
)

type Publisher struct {
	producer sarama.SyncProducer
	topic    string
}

func New(brokers []string, topic string) (*Publisher, error) {
	config := sarama.NewConfig()
	config.Producer.Return.Successes = true
	config.Producer.Idempotent = true // dedup at the broker
	config.Producer.RequiredAcks = sarama.WaitForAll
	config.Net.MaxOpenRequests = 1 // required for idempotent producers

	producer, err := sarama.NewSyncProducer(brokers, config)
	if err != nil {
		return nil, err
	}
	return &Publisher{producer: producer, topic: topic}, nil
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
