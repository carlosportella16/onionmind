.PHONY: test test-coverage vet build clean help

help:
	@echo "OnionMind Crawler - Makefile targets"
	@echo "  make test           - Run tests"
	@echo "  make test-coverage  - Run tests with coverage output"
	@echo "  make vet            - Run go vet"
	@echo "  make build          - Build crawler binary"
	@echo "  make clean          - Remove build artifacts"

test:
	go test ./...

test-coverage:
	go test -cover ./...

vet:
	go vet ./...

build:
	CGO_ENABLED=0 GOOS=linux go build -o bin/crawler ./cmd/crawler

clean:
	rm -rf bin/
	go clean
