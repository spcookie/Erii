package cmd

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"erii-cli/internal/path"
	uioutput "erii-cli/internal/ui/output"
	"erii-cli/internal/ui/theme"
	"erii-cli/internal/web"

	"github.com/spf13/cobra"
)

const (
	webDaemonChildEnv = "ERII_WEB_DAEMON_CHILD"
	webReadyTimeout   = 5 * time.Second
)

var (
	webPort   string
	webHost   string
	webToken  string
	webDaemon bool
)

var webCmd = &cobra.Command{
	Use:   "web",
	Short: "Manage the Erii web console",
	Long: `Manage the web console that provides browser access to Erii TUI.

The --daemon flag starts a detached background process only. It does not
monitor the web console or restart it after a crash.

Subcommands:
  start     Start the web console
  stop      Stop a background web console
  status    Show background web console status
`,
	RunE: func(cmd *cobra.Command, args []string) error {
		return cmd.Help()
	},
}

var webStartCmd = &cobra.Command{
	Use:   "start",
	Short: "Start the Erii web console",
	Long: `Start the Erii web console.

Use --daemon/-d to detach it from the terminal. Detached mode does not provide
process monitoring or automatic restart.`,
	RunE: func(cmd *cobra.Command, args []string) error {
		return runWebStart(cmd.OutOrStdout())
	},
}

var webStopCmd = &cobra.Command{
	Use:   "stop",
	Short: "Stop the background Erii web console",
	RunE: func(cmd *cobra.Command, args []string) error {
		return runWebStop(cmd.OutOrStdout())
	},
}

var webStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show background Erii web console status",
	RunE: func(cmd *cobra.Command, args []string) error {
		return runWebStatus(cmd.OutOrStdout())
	},
}

func init() {
	webStartCmd.Flags().StringVar(&webPort, "port", "9527", "HTTP listen port")
	webStartCmd.Flags().StringVar(&webHost, "host", "127.0.0.1", "HTTP listen host")
	webStartCmd.Flags().StringVar(&webToken, "token", "", "Custom access token (default: random generated)")
	webStartCmd.Flags().BoolVarP(
		&webDaemon,
		"daemon",
		"d",
		false,
		"Run as a detached background process (no monitoring or automatic restart)",
	)

	webCmd.AddCommand(webStartCmd)
	webCmd.AddCommand(webStopCmd)
	webCmd.AddCommand(webStatusCmd)
	rootCmd.AddCommand(webCmd)
}

func runWebStart(w io.Writer) error {
	token := webToken
	if token == "" {
		token = web.GenerateToken()
	}

	eriiBin, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve Erii executable: %w", err)
	}
	cfg := web.Config{
		Port:        webPort,
		Host:        webHost,
		Token:       token,
		EriiBin:     eriiBin,
		ConfDir:     path.ConfDir,
		MetaConfDir: path.ConfMetaDir,
		EriiDir:     path.EriiDir,
		PluginDir:   path.PluginDir,
		OptsPath:    path.OptsPath,
		LogsPath:    logsPathFlag,
		Theme:       string(theme.Requested()),
		Output:      w,
	}

	if os.Getenv(webDaemonChildEnv) == "1" {
		cfg.Detached = true
		if err := claimWebPID(os.Getpid()); err != nil {
			return err
		}
		defer removeWebPIDIfOwned(os.Getpid())
		return web.Start(cfg)
	}
	if webDaemon {
		return startWebDaemon(w, cfg)
	}
	return web.Start(cfg)
}

func webPIDFilePath() string {
	return filepath.Join(path.EriiDir, "erii.web.pid")
}

func webLogFilePath() (string, error) {
	logDir := logsPathFlag
	if logDir == "" {
		logDir = "logs"
	}
	logPath := filepath.Join(logDir, "erii.web.log")
	return filepath.Abs(logPath)
}

func readWebPID() (int, error) {
	data, err := os.ReadFile(webPIDFilePath())
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	value := strings.TrimSpace(string(data))
	if value == "" {
		return 0, nil
	}
	return strconv.Atoi(value)
}

func claimWebPID(pid int) error {
	existingPID, err := readWebPID()
	if err != nil {
		return fmt.Errorf("reading web PID file: %w", err)
	}
	if existingPID > 0 && isProcessRunning(existingPID) {
		return fmt.Errorf("web console is already running (PID: %d)", existingPID)
	}
	if existingPID > 0 {
		removeWebPIDIfOwned(existingPID)
	}
	if err := os.MkdirAll(filepath.Dir(webPIDFilePath()), 0755); err != nil {
		return fmt.Errorf("create web PID directory: %w", err)
	}
	if err := os.WriteFile(webPIDFilePath(), []byte(strconv.Itoa(pid)), 0644); err != nil {
		return fmt.Errorf("write web PID file: %w", err)
	}
	return nil
}

func removeWebPIDIfOwned(pid int) {
	currentPID, err := readWebPID()
	if err == nil && currentPID == pid {
		_ = os.Remove(webPIDFilePath())
	}
}

func ensureWebDaemonStopped() error {
	pid, err := readWebPID()
	if err != nil {
		return fmt.Errorf("reading web PID file: %w", err)
	}
	if pid > 0 && isProcessRunning(pid) {
		return fmt.Errorf("web console is already running (PID: %d)", pid)
	}
	if pid > 0 {
		removeWebPIDIfOwned(pid)
	}
	return nil
}

func startWebDaemon(w io.Writer, cfg web.Config) error {
	if err := ensureWebDaemonStopped(); err != nil {
		return err
	}
	logPath, err := webLogFilePath()
	if err != nil {
		return fmt.Errorf("resolve web log path: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(logPath), 0755); err != nil {
		return fmt.Errorf("create web log directory: %w", err)
	}
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return fmt.Errorf("open web log: %w", err)
	}
	if err := logFile.Chmod(0600); err != nil {
		_ = logFile.Close()
		return fmt.Errorf("secure web log: %w", err)
	}

	args := webDaemonArgs(cfg)
	cmd := exec.Command(cfg.EriiBin, args...)
	cmd.Dir = projectRoot()
	cmd.Env = append(os.Environ(), webDaemonChildEnv+"=1")
	cmd.Stdin = nil
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	configureDaemonProcess(cmd)
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		return fmt.Errorf("start background web console: %w", err)
	}
	_ = logFile.Close()

	pid := cmd.Process.Pid
	if err := waitForWebReady(cfg, pid); err != nil {
		_ = killProcess(pid)
		_, _ = cmd.Process.Wait()
		removeWebPIDIfOwned(pid)
		return fmt.Errorf("%w; see %s", err, logPath)
	}
	if err := cmd.Process.Release(); err != nil {
		return fmt.Errorf("release background web process: %w", err)
	}
	web.PrintReady(w, cfg, false)
	return nil
}

func webDaemonArgs(cfg web.Config) []string {
	args := make([]string, 0, 24)
	addPathFlag := func(flag, value string) {
		if value != "" {
			args = append(args, flag, value)
		}
	}
	addPathFlag("--conf-dir", cfg.ConfDir)
	addPathFlag("--meta-conf-dir", cfg.MetaConfDir)
	addPathFlag("--erii-dir", cfg.EriiDir)
	addPathFlag("--plugin-dir", cfg.PluginDir)
	addPathFlag("--opts-path", cfg.OptsPath)
	addPathFlag("--logs-path", cfg.LogsPath)
	addPathFlag("--theme", cfg.Theme)
	args = append(args,
		"web", "start",
		"--host", cfg.Host,
		"--port", cfg.Port,
		"--token", cfg.Token,
	)
	return args
}

func waitForWebReady(cfg web.Config, pid int) error {
	deadline := time.Now().Add(webReadyTimeout)
	for time.Now().Before(deadline) {
		if !isProcessRunning(pid) {
			return fmt.Errorf("background web console exited before becoming ready")
		}
		claimedPID, _ := readWebPID()
		if claimedPID == pid && probeWeb(cfg) {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("timeout waiting for background web console to become ready")
}

func probeWeb(cfg web.Config) bool {
	host := cfg.Host
	switch host {
	case "", "0.0.0.0", "::", "[::]":
		host = "127.0.0.1"
	}
	url := "http://" + net.JoinHostPort(host, cfg.Port) + "/?token=" + cfg.Token
	client := &http.Client{
		Timeout: 250 * time.Millisecond,
		Transport: &http.Transport{
			Proxy: nil,
		},
	}
	resp, err := client.Get(url)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

func runWebStop(w io.Writer) error {
	pid, err := readWebPID()
	if err != nil {
		return fmt.Errorf("reading web PID file: %w", err)
	}
	if pid == 0 {
		printWebResult(w, "Web console stop", "warning", "Status", "not running", "Reason", "PID file not found")
		return nil
	}
	if !isProcessRunning(pid) {
		removeWebPIDIfOwned(pid)
		printWebResult(w, "Web console stop", "warning", "PID", strconv.Itoa(pid), "Status", "stale PID file removed")
		return nil
	}

	if err := killProcess(pid); err != nil {
		return fmt.Errorf("stop web console PID %d: %w", pid, err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if !isProcessRunning(pid) {
			removeWebPIDIfOwned(pid)
			printWebResult(w, "Web console stopped", "ok", "PID", strconv.Itoa(pid))
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("timeout waiting for web console PID %d to stop", pid)
}

func runWebStatus(w io.Writer) error {
	pid, err := readWebPID()
	if err != nil {
		return fmt.Errorf("reading web PID file: %w", err)
	}
	if pid == 0 {
		printWebResult(w, "Web console status", "warning", "Status", "not running", "Reason", "PID file not found")
		return nil
	}
	if isProcessRunning(pid) {
		printWebResult(w, "Web console status", "ok", "Status", "running", "PID", strconv.Itoa(pid))
		return nil
	}
	printWebResult(w, "Web console status", "error", "Status", "not running", "Stale PID", strconv.Itoa(pid))
	os.Exit(1)
	return nil
}

func printWebResult(w io.Writer, title, status string, fields ...string) {
	fmt.Fprintln(w, uioutput.Title(title)+"  "+uioutput.Status(status))
	for i := 0; i+1 < len(fields); i += 2 {
		fmt.Fprint(w, uioutput.Row(fields[i], fields[i+1]))
	}
}
