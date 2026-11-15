//go:build windows

// Windows app of the fork: a small local control panel for the MASQUE core (usque.exe).
// It starts a web page on 127.0.0.1, opens it in the default browser and runs
// "usque.exe nativetun" as a child process, then routes traffic into the tunnel.
package main

import (
	"bufio"
	_ "embed"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed index.html
var indexHTML []byte

const ifaceName = "ByeByeRKN"

type app struct {
	mu       sync.Mutex
	dir      string
	exeDir   string
	cmd      *exec.Cmd
	state    string // disconnected | connecting | connected | error
	message  string
	logLines []string
	endpoint string
	gateway  string
}

func main() {
	if !isAdmin() {
		relaunchAsAdmin()
		return
	}
	exe, _ := os.Executable()
	appData, _ := os.UserConfigDir()
	dir := filepath.Join(appData, "ByeByeRKN")
	_ = os.MkdirAll(dir, 0o700)
	a := &app{dir: dir, exeDir: filepath.Dir(exe), state: "disconnected"}

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(indexHTML)
	})
	mux.HandleFunc("/api/status", a.handleStatus)
	mux.HandleFunc("/api/connect", a.handleConnect)
	mux.HandleFunc("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		a.disconnect()
		a.handleStatus(w, r)
	})
	mux.HandleFunc("/api/quit", func(w http.ResponseWriter, r *http.Request) {
		a.disconnect()
		_, _ = w.Write([]byte("ok"))
		go func() { time.Sleep(200 * time.Millisecond); os.Exit(0) }()
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Println("cannot listen:", err)
		os.Exit(1)
	}
	url := "http://" + ln.Addr().String() + "/"
	fmt.Println("ByeByeRKN panel:", url)
	_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	_ = http.Serve(ln, mux)
}

func (a *app) handleStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	defer a.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"state": a.state, "message": a.message, "log": a.logLines})
}

func (a *app) set(state, msg string) {
	a.mu.Lock()
	a.state, a.message = state, msg
	a.mu.Unlock()
}

func (a *app) addLog(line string) {
	a.mu.Lock()
	a.logLines = append(a.logLines, line)
	if len(a.logLines) > 60 {
		a.logLines = a.logLines[len(a.logLines)-60:]
	}
	a.mu.Unlock()
}

func (a *app) handleConnect(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	busy := a.state == "connecting" || a.state == "connected"
	if !busy {
		a.state, a.message, a.logLines = "connecting", "", nil
	}
	a.mu.Unlock()
	if !busy {
		go a.connect()
	}
	a.handleStatus(w, r)
}

func (a *app) usque() string { return filepath.Join(a.exeDir, "usque.exe") }

func (a *app) connect() {
	config := filepath.Join(a.dir, "config.json")
	if _, err := os.Stat(config); err != nil {
		a.addLog("Registering a free WARP account...")
		out, err := exec.Command(a.usque(), "register", "-a", "-c", config).CombinedOutput()
		a.addLog(strings.TrimSpace(string(out)))
		if err != nil {
			a.set("error", "register")
			return
		}
	}
	if _, err := os.Stat(filepath.Join(a.exeDir, "wintun.dll")); err != nil {
		a.set("error", "wintun")
		return
	}
	var cfg struct {
		EndpointV4 string `json:"endpoint_v4"`
	}
	if data, err := os.ReadFile(config); err == nil {
		_ = json.Unmarshal(data, &cfg)
	}
	a.endpoint = cfg.EndpointV4
	a.gateway = defaultGateway()

	cmd := exec.Command(a.usque(), "nativetun", "-c", config, "-n", ifaceName)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	stdout, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		a.addLog(err.Error())
		a.set("error", "start")
		return
	}
	a.mu.Lock()
	a.cmd = cmd
	a.mu.Unlock()

	routed := false
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		line := sc.Text()
		a.addLog(line)
		if !routed && strings.Contains(line, "Connected to MASQUE server") {
			routed = true
			a.route()
			a.set("connected", "")
		}
	}
	_ = cmd.Wait()
	a.unroute()
	a.mu.Lock()
	if a.cmd == cmd {
		a.cmd = nil
		if a.state != "error" {
			a.state = "disconnected"
		}
	}
	a.mu.Unlock()
}

func (a *app) disconnect() {
	a.mu.Lock()
	cmd := a.cmd
	a.cmd = nil
	a.state = "disconnected"
	a.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
	a.unroute()
}

func run(name string, args ...string) string {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, _ := c.CombinedOutput()
	return strings.TrimSpace(string(out))
}

func defaultGateway() string {
	return run("powershell", "-NoProfile", "-Command",
		"(Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Sort-Object RouteMetric | Select-Object -First 1).NextHop")
}

// route sends everything into the tunnel except the tunnel's own packets to the Cloudflare endpoint.
func (a *app) route() {
	if a.endpoint != "" && a.gateway != "" {
		run("route", "add", a.endpoint, "mask", "255.255.255.255", a.gateway, "metric", "1")
	}
	run("netsh", "interface", "ipv4", "add", "route", "0.0.0.0/1", "interface="+ifaceName, "nexthop=0.0.0.0")
	run("netsh", "interface", "ipv4", "add", "route", "128.0.0.0/1", "interface="+ifaceName, "nexthop=0.0.0.0")
	run("netsh", "interface", "ipv4", "set", "dnsservers", "name="+ifaceName, "static", "1.1.1.1", "primary")
}

func (a *app) unroute() {
	if a.endpoint != "" {
		run("route", "delete", a.endpoint)
	}
}

func isAdmin() bool {
	f, err := os.Open(`\\.\PHYSICALDRIVE0`)
	if err != nil {
		return false
	}
	_ = f.Close()
	return true
}

func relaunchAsAdmin() {
	exe, _ := os.Executable()
	_ = exec.Command("powershell", "-NoProfile", "-Command",
		fmt.Sprintf("Start-Process -FilePath '%s' -Verb RunAs", exe)).Run()
}
