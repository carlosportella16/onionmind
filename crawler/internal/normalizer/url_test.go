package normalizer

import "testing"

func TestNormalize(t *testing.T) {
	cases := []struct{ in, want string }{
		{"HTTP://EXAMPLE.ONION/Page?b=2&a=1#frag", "http://example.onion/page?a=1&b=2"},
		{"http://x.onion:80/path/", "http://x.onion/path"},
		{"http://x.onion/", "http://x.onion/"},
	}
	for _, tc := range cases {
		got := Normalize(tc.in)
		if got != tc.want {
			t.Errorf("Normalize(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}
