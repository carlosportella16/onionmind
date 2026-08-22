package main

import "testing"

func TestRun_MissingConfigReturnsError(t *testing.T) {
	if err := run("does-not-exist.yaml"); err == nil {
		t.Fatal("expected error for missing config file")
	}
}

func TestRun_UnreachableBrokerReturnsErrorInsteadOfBlocking(t *testing.T) {
	// config.yaml points at docker-compose service hostnames (tor, redis,
	// redpanda) which aren't resolvable from the host test runner. Bootstrap
	// must fail fast with an error rather than hang or exit the process.
	if err := run("../../config.yaml"); err == nil {
		t.Fatal("expected error creating publisher against unreachable broker")
	}
}
