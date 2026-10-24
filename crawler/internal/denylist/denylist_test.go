package denylist

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Synthetic host (random base32, not a real service) and its MD5 as ahmia's /banned/ would
// list it: md5("<host>.onion"), no scheme. Locks the hash input format (design D3).
const (
	bannedHost = "okmbttbgvhbw6bgrfia73or2p55mub2dh2zvtdskyylq5iar55xp6yts.onion"
	bannedHash = "e3d147839e96e2746fd7a72d604addf5"
	otherHost  = "43tplwhcyasylz7lq6lr2mz7ht4jl5nmxggw4zsjsvxzifcfauu4ksfe.onion"
)

func writeList(t *testing.T, lines ...string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "banned-hosts.md5")
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestBanned_NormalizesHost(t *testing.T) {
	d, err := Load(writeList(t, bannedHash))
	if err != nil {
		t.Fatal(err)
	}
	upper := strings.ToUpper(bannedHost)
	cases := []struct {
		url  string
		want bool
	}{
		{"http://" + bannedHost + "/", true},
		{"https://" + bannedHost + "/other/page?q=1", true},
		{"http://" + upper + "/", true},
		{"HTTP://" + upper + ":8080/x", true},
		{bannedHost, true},
		{bannedHost + "/path", true},
		{"http://www." + bannedHost + "/", true},
		{"http://" + otherHost + "/", false},
		{"http://ahmia.fi/onions/", false},
		{"::not a url", false},
	}
	for _, c := range cases {
		if got := d.Banned(c.url); got != c.want {
			t.Errorf("Banned(%q) = %v, want %v", c.url, got, c.want)
		}
	}
}

func TestLoad_SkipsInvalidLines(t *testing.T) {
	d, err := Load(writeList(t,
		"  "+strings.ToUpper(bannedHash)+"  ",
		"",
		"not-a-hash",
		"e3d147839e96e2746fd7a72d604addf",  // 31 chars
		"zzd147839e96e2746fd7a72d604addf5", // not hex
	))
	if err != nil {
		t.Fatal(err)
	}
	if d.Len() != 1 {
		t.Fatalf("Len = %d, want 1", d.Len())
	}
	if !d.Banned("http://" + bannedHost + "/") {
		t.Error("valid (upper-case, padded) hash should still ban its host")
	}
}

func TestLoad_EmptyFileBansNothing(t *testing.T) {
	d, err := Load(writeList(t))
	if err != nil {
		t.Fatal(err)
	}
	if d.Len() != 0 || d.Banned("http://"+bannedHost+"/") {
		t.Errorf("empty list should ban nothing, Len = %d", d.Len())
	}
}

func TestLoad_MissingFileNamesPath(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing.md5")
	_, err := Load(path)
	if err == nil || !strings.Contains(err.Error(), path) {
		t.Fatalf("expected an error naming %s, got %v", path, err)
	}
}

func TestNilDenylistBansNothing(t *testing.T) {
	var d *Denylist
	if d.Banned("http://" + bannedHost + "/") {
		t.Error("nil denylist should ban nothing")
	}
}
