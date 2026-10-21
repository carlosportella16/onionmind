// Package testfakes stands in for the Tor network in tests and in the e2e stack: a
// SOCKS5 proxy that only reaches .onion hosts, and the .onion sites behind it.
package testfakes

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"strings"
	"sync"
)

// SOCKS5Server is a minimal no-auth SOCKS5 proxy supporting CONNECT. Like Tor with
// socks5h, it receives the hostname — .onion names are resolved here, never by the
// client — and connects every .onion host to Upstream (an OnionSites server, which routes
// by Host header). Any other host is refused and recorded.
type SOCKS5Server struct {
	Upstream string

	listener net.Listener
	mu       sync.Mutex
	hosts    []string
	refused  []string
}

// NewSOCKS5Server starts listening on addr ("127.0.0.1:0" for a random port).
func NewSOCKS5Server(addr, upstream string) (*SOCKS5Server, error) {
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	s := &SOCKS5Server{Upstream: upstream, listener: l}
	go s.serve()
	return s, nil
}

func (s *SOCKS5Server) Addr() string { return s.listener.Addr().String() }

func (s *SOCKS5Server) Close() error { return s.listener.Close() }

// Hosts returns every hostname a client asked to CONNECT to, in order.
func (s *SOCKS5Server) Hosts() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.hosts...)
}

// Refused returns the non-.onion hosts that were refused.
func (s *SOCKS5Server) Refused() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.refused...)
}

// Reset forgets recorded hosts (between scenarios).
func (s *SOCKS5Server) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.hosts, s.refused = nil, nil
}

func (s *SOCKS5Server) serve() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go func() {
			if err := s.handle(conn); err != nil && !errors.Is(err, io.EOF) {
				slog.Debug("socks5 fake: connection ended", "err", err)
			}
		}()
	}
}

const (
	socksVersion   = 5
	cmdConnect     = 1
	atypIPv4       = 1
	atypDomain     = 3
	atypIPv6       = 4
	repSucceeded   = 0
	repNotAllowed  = 2
	repCmdNotSupp  = 7
	repAddrNotSupp = 8
)

func (s *SOCKS5Server) handle(conn net.Conn) error {
	defer conn.Close()

	// greeting: VER NMETHODS METHODS... -> VER 0x00 (no auth)
	head := make([]byte, 2)
	if _, err := io.ReadFull(conn, head); err != nil {
		return err
	}
	if head[0] != socksVersion {
		return fmt.Errorf("unsupported socks version %d", head[0])
	}
	if _, err := io.ReadFull(conn, make([]byte, head[1])); err != nil {
		return err
	}
	if _, err := conn.Write([]byte{socksVersion, 0}); err != nil {
		return err
	}

	// request: VER CMD RSV ATYP DST.ADDR DST.PORT
	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil {
		return err
	}
	if req[1] != cmdConnect {
		return reply(conn, repCmdNotSupp)
	}
	host, err := readHost(conn, req[3])
	if err != nil {
		_ = reply(conn, repAddrNotSupp)
		return err
	}
	if _, err := io.ReadFull(conn, make([]byte, 2)); err != nil { // port: irrelevant, Upstream decides
		return err
	}

	s.mu.Lock()
	s.hosts = append(s.hosts, host)
	onion := strings.HasSuffix(strings.ToLower(host), ".onion")
	if !onion {
		s.refused = append(s.refused, host)
	}
	s.mu.Unlock()
	if !onion {
		return reply(conn, repNotAllowed)
	}

	upstream, err := net.Dial("tcp", s.Upstream)
	if err != nil {
		_ = reply(conn, repNotAllowed)
		return err
	}
	defer upstream.Close()
	if err := reply(conn, repSucceeded); err != nil {
		return err
	}

	go func() {
		_, _ = io.Copy(upstream, conn)
		if tcp, ok := upstream.(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
	}()
	_, err = io.Copy(conn, upstream)
	return err
}

func readHost(conn net.Conn, atyp byte) (string, error) {
	switch atyp {
	case atypDomain:
		n := make([]byte, 1)
		if _, err := io.ReadFull(conn, n); err != nil {
			return "", err
		}
		name := make([]byte, n[0])
		if _, err := io.ReadFull(conn, name); err != nil {
			return "", err
		}
		return string(name), nil
	case atypIPv4, atypIPv6:
		size := net.IPv4len
		if atyp == atypIPv6 {
			size = net.IPv6len
		}
		ip := make([]byte, size)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return "", err
		}
		return net.IP(ip).String(), nil
	default:
		return "", fmt.Errorf("unsupported address type %d", atyp)
	}
}

func reply(conn net.Conn, rep byte) error {
	// VER REP RSV ATYP(IPv4) BND.ADDR(0.0.0.0) BND.PORT(0)
	msg := []byte{socksVersion, rep, 0, atypIPv4, 0, 0, 0, 0}
	msg = binary.BigEndian.AppendUint16(msg, 0)
	_, err := conn.Write(msg)
	return err
}
