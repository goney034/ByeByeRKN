// Command androidcore is the MASQUE engine of the ByeByeRKN Android app.
//
// The app starts it as a child process with the path of a Unix socket the app listens on as the
// only argument, and writes the JSON config to stdin. Over that socket the core asks for the
// VPN interface and hands over every network socket before using it, so that the app can
// exclude it from its own VPN (VpnService.protect). Events go to stdout, one JSON object per
// line: {"event":"connected"}, {"event":"disconnected","error":"..."},
// {"event":"stats","rx":0,"tx":0} and {"event":"fatal","error":"..."}.
package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/Diniboy1123/usque/api"
	"github.com/Diniboy1123/usque/internal"
)

// Config is what the app sends on stdin.
type Config struct {
	PrivateKey     string `json:"private_key"`      // base64 DER of a P-256 key, PKCS#8 or SEC 1
	EndpointPubKey string `json:"endpoint_pub_key"` // PEM, the server key to pin
	Endpoint       string `json:"endpoint"`         // ip:port
	HTTP2          bool   `json:"http2"`            // TCP and HTTP/2 instead of QUIC and HTTP/3
	SNI            string `json:"sni"`
	MTU            int    `json:"mtu"`
}

const (
	requestTun     = 'T'
	requestProtect = 'P'
	statsEvery     = time.Second
	parentCheck    = 2 * time.Second
	keepalive      = 30 * time.Second
	reconnectDelay = time.Second
)

func main() {
	log.SetOutput(os.Stderr)
	if len(os.Args) != 2 {
		fatal(errors.New("usage: androidcore SOCKET_PATH < config.json"))
	}
	var cfg Config
	if err := json.NewDecoder(os.Stdin).Decode(&cfg); err != nil {
		fatal(fmt.Errorf("read config: %w", err))
	}
	if err := run(cfg, os.Args[1]); err != nil {
		fatal(err)
	}
}

func run(cfg Config, socketPath string) error {
	app, err := dialApp(socketPath)
	if err != nil {
		return err
	}
	tun, err := app.receiveTun()
	if err != nil {
		return err
	}
	api.SocketControl = app.protectControl

	privKey, err := parsePrivateKey(cfg.PrivateKey)
	if err != nil {
		return err
	}
	peerKey, err := parsePeerKey(cfg.EndpointPubKey)
	if err != nil {
		return err
	}
	cert, err := internal.GenerateCert(privKey, &privKey.PublicKey)
	if err != nil {
		return fmt.Errorf("make certificate: %w", err)
	}
	tlsConfig, err := api.PrepareTlsConfig(privKey, peerKey, cert, cfg.SNI, false)
	if err != nil {
		return fmt.Errorf("prepare TLS: %w", err)
	}

	var endpoint net.Addr
	if cfg.HTTP2 {
		endpoint, err = net.ResolveTCPAddr("tcp", cfg.Endpoint)
	} else {
		endpoint, err = net.ResolveUDPAddr("udp", cfg.Endpoint)
	}
	if err != nil {
		return fmt.Errorf("endpoint %q: %w", cfg.Endpoint, err)
	}
	mtu := cfg.MTU
	if mtu == 0 {
		mtu = 1280
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stopOnSignal(cancel)
	stopWhenOrphaned(cancel)

	device := &countingDevice{file: tun}
	go reportStats(ctx, device)

	api.MaintainTunnel(ctx, api.MaintainTunnelConfig{
		TLSConfig:       tlsConfig,
		KeepalivePeriod: keepalive,
		Endpoint:        endpoint,
		Device:          device,
		MTU:             mtu,
		ReconnectDelay:  reconnectDelay,
		AlwaysReconnect: true,
		UseHTTP2:        cfg.HTTP2,
		OnConnected:     func() { emit(event{Event: "connected"}) },
		OnDisconnected:  func(err error) { emit(event{Event: "disconnected", Error: err.Error()}) },
	})
	return nil
}

// appConn is the control connection to the app. Requests and replies alternate, so one at a time.
type appConn struct {
	mu   sync.Mutex
	conn *net.UnixConn
}

func dialApp(path string) (*appConn, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, fmt.Errorf("connect to app: %w", err)
	}
	return &appConn{conn: conn}, nil
}

// receiveTun asks the app for the VPN interface, which comes back as a passed file descriptor.
func (a *appConn) receiveTun() (*os.File, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, err := a.conn.Write([]byte{requestTun}); err != nil {
		return nil, fmt.Errorf("ask for tun: %w", err)
	}
	buf := make([]byte, 1)
	oob := make([]byte, syscall.CmsgSpace(4))
	_, oobn, _, _, err := a.conn.ReadMsgUnix(buf, oob)
	if err != nil {
		return nil, fmt.Errorf("receive tun: %w", err)
	}
	messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(messages) == 0 {
		return nil, fmt.Errorf("receive tun: no file descriptor (%v)", err)
	}
	fds, err := syscall.ParseUnixRights(&messages[0])
	if err != nil || len(fds) == 0 {
		return nil, fmt.Errorf("receive tun: bad file descriptor (%v)", err)
	}
	return os.NewFile(uintptr(fds[0]), "tun"), nil
}

// protect hands fd to the app, which excludes it from the VPN and answers with 1 on success.
func (a *appConn) protect(fd int) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, _, err := a.conn.WriteMsgUnix([]byte{requestProtect}, syscall.UnixRights(fd), nil); err != nil {
		return fmt.Errorf("send socket to app: %w", err)
	}
	reply := make([]byte, 1)
	if _, err := io.ReadFull(a.conn, reply); err != nil {
		return fmt.Errorf("app did not answer: %w", err)
	}
	if reply[0] != 1 {
		return errors.New("app could not exclude the socket from the VPN")
	}
	return nil
}

func (a *appConn) protectControl(_, _ string, c syscall.RawConn) error {
	var protectErr error
	if err := c.Control(func(fd uintptr) { protectErr = a.protect(int(fd)) }); err != nil {
		return err
	}
	return protectErr
}

// countingDevice is the VPN interface, counting bytes the way the app shows them.
type countingDevice struct {
	file   *os.File
	rx, tx atomic.Uint64
}

func (d *countingDevice) ReadPacket(buf []byte) (int, error) {
	n, err := d.file.Read(buf)
	if n > 0 {
		d.tx.Add(uint64(n))
	}
	return n, err
}

func (d *countingDevice) WritePacket(pkt []byte) error {
	if _, err := d.file.Write(pkt); err != nil {
		return err
	}
	d.rx.Add(uint64(len(pkt)))
	return nil
}

func reportStats(ctx context.Context, d *countingDevice) {
	ticker := time.NewTicker(statsEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			emit(event{Event: "stats", Rx: d.rx.Load(), Tx: d.tx.Load()})
		}
	}
}

func parsePrivateKey(b64 string) (*ecdsa.PrivateKey, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("private key: %w", err)
	}
	if key, err := x509.ParsePKCS8PrivateKey(der); err == nil {
		if ec, ok := key.(*ecdsa.PrivateKey); ok {
			return ec, nil
		}
		return nil, errors.New("private key: not ECDSA")
	}
	key, err := x509.ParseECPrivateKey(der)
	if err != nil {
		return nil, fmt.Errorf("private key: %w", err)
	}
	return key, nil
}

func parsePeerKey(pemText string) (*ecdsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemText))
	if block == nil {
		return nil, errors.New("endpoint key: not PEM")
	}
	key, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("endpoint key: %w", err)
	}
	ec, ok := key.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("endpoint key: not ECDSA")
	}
	return ec, nil
}

// stopOnSignal ends the tunnel when the app asks the process to stop.
func stopOnSignal(cancel context.CancelFunc) {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-signals
		cancel()
		os.Exit(0)
	}()
}

// stopWhenOrphaned exits once the app is gone: nobody would ever stop the tunnel otherwise.
func stopWhenOrphaned(cancel context.CancelFunc) {
	parent := os.Getppid()
	go func() {
		for range time.Tick(parentCheck) {
			if os.Getppid() != parent {
				cancel()
				os.Exit(0)
			}
		}
	}()
}

type event struct {
	Event string `json:"event"`
	Error string `json:"error,omitempty"`
	Rx    uint64 `json:"rx,omitempty"`
	Tx    uint64 `json:"tx,omitempty"`
}

var stdoutMu sync.Mutex

func emit(e event) {
	line, _ := json.Marshal(e)
	stdoutMu.Lock()
	defer stdoutMu.Unlock()
	_, _ = os.Stdout.Write(append(line, '\n'))
}

func fatal(err error) {
	emit(event{Event: "fatal", Error: err.Error()})
	os.Exit(1)
}
