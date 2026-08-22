package publisher

import (
	"bytes"
	"encoding/json"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/IBM/sarama/mocks"

	"github.com/carlosportella16/onionmind/crawler/internal/connector"
)

func TestNew_ReturnsErrorForUnreachableBroker(t *testing.T) {
	done := make(chan struct{})
	go func() {
		_, err := New([]string{"127.0.0.1:1"}, "raw-pages")
		if err == nil {
			t.Error("expected error for unreachable broker")
		}
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("New did not return promptly for an unreachable broker")
	}
}

func TestPublishRawPage_SerializesPayloadAndUsesURLAsKey(t *testing.T) {
	page := &connector.RawPage{
		URL:        "http://example.onion/page",
		SourceType: "tor",
		HTML:       []byte("<html>hi</html>"),
		FetchedAt:  "2026-08-22T00:00:00Z",
	}

	mockProducer := mocks.NewSyncProducer(t, nil)
	mockProducer.ExpectSendMessageWithCheckerFunctionAndSucceed(func(val []byte) error {
		var got connector.RawPage
		if err := json.Unmarshal(val, &got); err != nil {
			return err
		}
		if got.URL != page.URL || got.SourceType != page.SourceType ||
			!bytes.Equal(got.HTML, page.HTML) || got.FetchedAt != page.FetchedAt {
			t.Errorf("published payload = %+v, want %+v", got, *page)
		}
		return nil
	})

	p := &Publisher{producer: mockProducer, topic: "raw-pages"}
	defer p.Close()

	if err := p.PublishRawPage(page); err != nil {
		t.Fatalf("PublishRawPage returned error: %v", err)
	}
}

func TestPublishRawPage_UsesURLAsPartitionKey(t *testing.T) {
	page := &connector.RawPage{URL: "http://key.onion/", SourceType: "tor", HTML: []byte("x")}

	mockProducer := mocks.NewSyncProducer(t, nil)
	mockProducer.ExpectSendMessageWithMessageCheckerFunctionAndSucceed(func(msg *sarama.ProducerMessage) error {
		key, err := msg.Key.Encode()
		if err != nil {
			return err
		}
		if string(key) != page.URL {
			t.Errorf("key = %q, want %q", key, page.URL)
		}
		return nil
	})

	p := &Publisher{producer: mockProducer, topic: "raw-pages"}
	defer p.Close()

	if err := p.PublishRawPage(page); err != nil {
		t.Fatalf("PublishRawPage returned error: %v", err)
	}
}
