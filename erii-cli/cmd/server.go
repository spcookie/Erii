package cmd

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"

	"erii-cli/internal/path"
	uioutput "erii-cli/internal/ui/output"

	"github.com/spf13/cobra"
)

var serverCmd = &cobra.Command{
	Use:                "server",
	Short:              "Manage the Erii backend Java server",
	DisableFlagParsing: true,
	Long: `Manage the Erii backend Java server.

The server starts as a detached background process by default. Detached mode
does not monitor the server or restart it after a crash; use a process
supervisor when automatic recovery is required.

Subcommands:
  start     Start the server
  stop      Stop a running server
  status    Show server status
  restart   Restart the server
`,
	RunE: func(cmd *cobra.Command, args []string) error {
		return cmd.Help()
	},
}

var serverStartCmd = &cobra.Command{
	Use:   "start",
	Short: "Start the Erii backend server",
	Long: `Start the Erii backend server.

By default the process is detached from the terminal. This is background-only
mode, not process supervision: the CLI will not restart the server if it exits.`,
	DisableFlagParsing: true,
	RunE: func(cmd *cobra.Command, args []string) error {
		if hasHelpFlag(args) {
			return cmd.Help()
		}
		return runServerStart(cmd.OutOrStdout())
	},
}

var serverStopCmd = &cobra.Command{
	Use:   "stop",
	Short: "Stop the running Erii backend server",
	RunE: func(cmd *cobra.Command, args []string) error {
		return runServerStop(cmd.OutOrStdout())
	},
}

var serverStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show Erii backend server status",
	RunE: func(cmd *cobra.Command, args []string) error {
		return runServerStatus(cmd.OutOrStdout())
	},
}

var serverRestartCmd = &cobra.Command{
	Use:                "restart",
	Short:              "Restart the Erii backend server",
	DisableFlagParsing: true,
	RunE: func(cmd *cobra.Command, args []string) error {
		if hasHelpFlag(args) {
			return cmd.Help()
		}
		return runServerRestart(cmd.OutOrStdout())
	},
}

func init() {
	// These flags are documentation-only: the server commands use
	// DisableFlagParsing (to forward args to Java), so cobra does not parse
	// them — passthroughArgs handles -f/--foreground manually. Registering
	// them here makes them show up in `-h` output.
	serverCmd.Flags().BoolP("foreground", "f", false, "Run in foreground instead of detached background mode")
	serverStartCmd.Flags().BoolP("foreground", "f", false, "Run in foreground instead of detached background mode")

	serverCmd.AddCommand(serverStartCmd)
	serverCmd.AddCommand(serverStopCmd)
	serverCmd.AddCommand(serverStatusCmd)
	serverCmd.AddCommand(serverRestartCmd)
	rootCmd.AddCommand(serverCmd)
}

// ---- path helpers ----

func projectRoot() string {
	if path.ConfDir != "" {
		return filepath.Dir(path.ConfDir)
	}
	cwd, _ := os.Getwd()
	return cwd
}

func pidFilePath() string {
	if path.EriiDir != "" {
		return filepath.Join(path.EriiDir, "erii.pid")
	}
	return filepath.Join(projectRoot(), ".erii", "erii.pid")
}

// ---- Java finding ----

func findJava() (string, error) {
	javaBin := "java"
	if runtime.GOOS == "windows" {
		javaBin = "java.exe"
	}

	runtimeJava := filepath.Join(projectRoot(), "runtime", "bin", javaBin)
	if _, err := os.Stat(runtimeJava); err == nil {
		return runtimeJava, nil
	}

	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		homeJava := filepath.Join(jh, "bin", javaBin)
		if _, err := os.Stat(homeJava); err == nil {
			return homeJava, nil
		}
	}

	return exec.LookPath("java")
}

// ---- classpath ----

func buildClasspath(root string) string {
	libDir := filepath.Join(root, "lib")
	parts := []string{
		filepath.Join(libDir, "browser", "base", "*"),
		filepath.Join(libDir, "browser", "driver", "*"),
		filepath.Join(libDir, "core", "*"),
	}

	// deps are split into sub-packages, each symlinked as lib/deps/<name>.
	// Java's classpath wildcard only expands a single trailing "/*", so每个子目录
	// 都要单独作为一条 "<dir>/*" 加入。动态枚举以适配任意分片数量/命名。
	depsDir := filepath.Join(libDir, "deps")
	hasSubDir := false
	if entries, err := os.ReadDir(depsDir); err == nil {
		for _, e := range entries {
			if e.IsDir() || (e.Type()&os.ModeSymlink != 0) {
				parts = append(parts, filepath.Join(depsDir, e.Name(), "*"))
				hasSubDir = true
			}
		}
	}
	// Fallback to the legacy flat layout (jars directly under lib/deps).
	if !hasSubDir {
		parts = append(parts, filepath.Join(depsDir, "*"))
	}

	return strings.Join(parts, string(filepath.ListSeparator))
}

// ---- opts reading ----

func readOpts(filename string) ([]string, error) {
	file, err := os.Open(filename)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer file.Close()

	var lines []string
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		lines = append(lines, line)
	}
	return lines, scanner.Err()
}

// ---- env loading ----

func loadServerEnv() []string {
	env := os.Environ()

	envFile := filepath.Join(path.ConfDir, ".env.local")
	if f, err := os.Open(envFile); err == nil {
		defer f.Close()
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if k, v, ok := parseEnvLine(line); ok {
				env = append(env, k+"="+v)
			}
		}
	}

	envOptsFile := filepath.Join(path.OptsPath, "env.opts")
	if f, err := os.Open(envOptsFile); err == nil {
		defer f.Close()
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if k, v, ok := parseEnvLine(line); ok {
				env = append(env, k+"="+v)
			}
		}
	}

	return env
}

func parseEnvLine(line string) (string, string, bool) {
	idx := strings.IndexByte(line, '=')
	if idx <= 0 {
		return "", "", false
	}
	key := strings.TrimSpace(line[:idx])
	if !isEnvVarKey(key) {
		return "", "", false
	}
	val := strings.TrimSpace(line[idx+1:])
	return key, val, true
}

func isEnvVarKey(s string) bool {
	if len(s) == 0 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
			return false
		}
	}
	return (s[0] >= 'A' && s[0] <= 'Z') || s[0] == '_'
}

// ---- passthrough args ----

// hasHelpFlag reports whether -h/--help appears in the raw args. Needed because
// the server commands use DisableFlagParsing to forward args to Java, which also
// suppresses cobra's built-in help handling.
func hasHelpFlag(args []string) bool {
	for _, a := range args {
		if a == "-h" || a == "--help" {
			return true
		}
	}
	return false
}

// passthroughArgs extracts args after "server <subcommand>" from os.Args.
// Returns the filtered args and whether --foreground/-f was set.
func passthroughArgs(subcommand string) ([]string, bool) {
	state := 0 // 0=before server, 1=after server, 2=after subcommand
	var args []string
	foreground := false
	for _, arg := range os.Args[1:] {
		if state == 0 {
			if arg == "server" {
				state = 1
			}
		} else if state == 1 {
			if subcommand != "" && arg == subcommand {
				state = 2
			} else if subcommand == "" {
				// No subcommand expected — collect remaining args directly
				state = 2
				if arg == "--foreground" || arg == "-f" {
					foreground = true
				} else {
					args = append(args, arg)
				}
			}
		} else {
			if arg == "--foreground" || arg == "-f" {
				foreground = true
			} else {
				args = append(args, arg)
			}
		}
	}
	return args, foreground
}

// ---- Java args building ----

func buildJavaArgs(passthrough []string) ([]string, error) {
	javaOpts, err := readOpts(filepath.Join(path.OptsPath, "java.opts"))
	if err != nil {
		return nil, fmt.Errorf("reading java.opts: %w", err)
	}
	eriiCoreOpts, err := readOpts(filepath.Join(path.OptsPath, "erii-core.opts"))
	if err != nil {
		return nil, fmt.Errorf("reading erii-core.opts: %w", err)
	}

	var userSystemProps []string
	var userProgramArgs []string
	for _, arg := range passthrough {
		if strings.HasPrefix(arg, "-D") {
			userSystemProps = append(userSystemProps, arg)
		} else {
			userProgramArgs = append(userProgramArgs, arg)
		}
	}

	userPropKeys := make(map[string]bool)
	for _, arg := range userSystemProps {
		if idx := strings.IndexByte(arg, '='); idx > 0 {
			userPropKeys[arg[:idx]] = true
		}
	}
	var filteredCoreOpts []string
	for _, arg := range eriiCoreOpts {
		if strings.HasPrefix(arg, "-D") {
			key := arg
			if idx := strings.IndexByte(arg, '='); idx > 0 {
				key = arg[:idx]
			}
			if userPropKeys[key] {
				continue
			}
		}
		filteredCoreOpts = append(filteredCoreOpts, arg)
	}

	cp := buildClasspath(projectRoot())

	all := make([]string, 0, len(javaOpts)+len(userSystemProps)+len(filteredCoreOpts)+len(userProgramArgs)+3)
	all = append(all, javaOpts...)
	all = append(all, userSystemProps...)
	all = append(all, filteredCoreOpts...)
	all = append(all, "-cp", cp)
	all = append(all, "io.ktor.server.netty.EngineMain")
	all = append(all, userProgramArgs...)

	return all, nil
}

// ---- PID file management ----

func readPidFile() (int, error) {
	data, err := os.ReadFile(pidFilePath())
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	pidStr := strings.TrimSpace(string(data))
	if pidStr == "" {
		return 0, nil
	}
	return strconv.Atoi(pidStr)
}

func writePidFile(pid int) error {
	if err := os.MkdirAll(filepath.Dir(pidFilePath()), 0755); err != nil {
		return err
	}
	return os.WriteFile(pidFilePath(), []byte(strconv.Itoa(pid)), 0644)
}

func removePidFile() {
	os.Remove(pidFilePath())
}

// ---- process management ----

func stopServer(pid int) error {
	if err := killProcess(pid); err != nil {
		return err
	}
	for i := 0; i < 50; i++ {
		if !isProcessRunning(pid) {
			removePidFile()
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("timeout waiting for server to stop")
}

func spawnDaemon(javaBin string, args []string, env []string) (int, error) {
	cmd := exec.Command(javaBin, args...)
	cmd.Dir = projectRoot()
	cmd.Env = env
	cmd.Stdin = nil
	cmd.Stdout = io.Discard
	cmd.Stderr = io.Discard
	configureDaemonProcess(cmd)

	if err := cmd.Start(); err != nil {
		return 0, err
	}
	return cmd.Process.Pid, nil
}

// ---- command implementations ----

func runServerStart(w io.Writer) error {
	javaBin, err := findJava()
	if err != nil {
		return fmt.Errorf("Java not found. Install Java 17+ or set JAVA_HOME: %w", err)
	}

	passthrough, foreground := passthroughArgs("start")
	args, err := buildJavaArgs(passthrough)
	if err != nil {
		return err
	}
	env := loadServerEnv()

	if foreground {
		cmd := exec.Command(javaBin, args...)
		cmd.Dir = projectRoot()
		cmd.Env = env
		cmd.Stdin = os.Stdin
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr

		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, os.Interrupt)
		go func() {
			for sig := range sigCh {
				if cmd.Process != nil {
					cmd.Process.Signal(sig)
				}
			}
		}()

		if err := cmd.Run(); err != nil {
			if exitErr, ok := err.(*exec.ExitError); ok {
				os.Exit(exitErr.ExitCode())
			}
			return err
		}
		return nil
	}

	return daemonStart(w, javaBin, args, env)
}

func daemonStart(w io.Writer, javaBin string, args []string, env []string) error {
	existingPid, err := readPidFile()
	if err != nil {
		return fmt.Errorf("reading PID file: %w", err)
	}

	if existingPid > 0 && isProcessRunning(existingPid) {
		return fmt.Errorf("server is already running (PID: %d). Use 'erii server restart' to restart", existingPid)
	}
	if existingPid > 0 {
		removePidFile()
	}

	pid, err := spawnDaemon(javaBin, args, env)
	if err != nil {
		return fmt.Errorf("starting server: %w", err)
	}
	if err := writePidFile(pid); err != nil {
		return fmt.Errorf("writing PID file: %w", err)
	}
	printServerResult(w, "Server started", "ok", "PID", strconv.Itoa(pid), "Mode", "detached (no auto-restart)")
	return nil
}

func daemonRestart(w io.Writer, javaBin string, args []string, env []string) error {
	existingPid, err := readPidFile()
	if err != nil {
		return fmt.Errorf("reading PID file: %w", err)
	}

	if existingPid > 0 && isProcessRunning(existingPid) {
		fmt.Fprintln(w, uioutput.Title("Server restart")+"  "+uioutput.Status("running"))
		fmt.Fprint(w, uioutput.Row("PID", strconv.Itoa(existingPid)))
		if err := stopServer(existingPid); err != nil {
			fmt.Fprint(w, uioutput.ErrorResult("Server restart", "Stop", err))
			os.Exit(1)
		}
		fmt.Fprintln(w, uioutput.Muted("Server stopped. Starting server..."))
	} else if existingPid > 0 {
		removePidFile()
	}

	pid, err := spawnDaemon(javaBin, args, env)
	if err != nil {
		return fmt.Errorf("starting server: %w", err)
	}
	if err := writePidFile(pid); err != nil {
		return fmt.Errorf("writing PID file: %w", err)
	}
	printServerResult(w, "Server restarted", "ok", "PID", strconv.Itoa(pid), "Mode", "detached (no auto-restart)")
	return nil
}

func runServerStop(w io.Writer) error {
	pid, err := readPidFile()
	if err != nil {
		return fmt.Errorf("reading PID file: %w", err)
	}
	if pid == 0 {
		printServerResult(w, "Server stop", "warning", "Status", "not running", "Reason", "PID file not found")
		return nil
	}
	if !isProcessRunning(pid) {
		printServerResult(w, "Server stop", "warning", "PID", strconv.Itoa(pid), "Status", "stale PID file removed")
		removePidFile()
		return nil
	}

	fmt.Fprintln(w, uioutput.Title("Server stop")+"  "+uioutput.Status("running"))
	fmt.Fprint(w, uioutput.Row("PID", strconv.Itoa(pid)))
	if err := stopServer(pid); err != nil {
		fmt.Fprint(w, uioutput.ErrorResult("Server stop", "Process", err))
		os.Exit(1)
	}
	printServerResult(w, "Server stopped", "ok", "PID", strconv.Itoa(pid))
	return nil
}

func runServerStatus(w io.Writer) error {
	pid, err := readPidFile()
	if err != nil {
		return fmt.Errorf("reading PID file: %w", err)
	}
	if pid == 0 {
		printServerResult(w, "Server status", "warning", "Status", "not running", "Reason", "PID file not found")
		return nil
	}
	if isProcessRunning(pid) {
		printServerResult(w, "Server status", "ok", "Status", "running", "PID", strconv.Itoa(pid))
		return nil
	}
	printServerResult(w, "Server status", "error", "Status", "not running", "Stale PID", strconv.Itoa(pid))
	os.Exit(1)
	return nil
}

func runServerRestart(w io.Writer) error {
	javaBin, err := findJava()
	if err != nil {
		return fmt.Errorf("Java not found. Install Java 17+ or set JAVA_HOME: %w", err)
	}

	passthrough, _ := passthroughArgs("restart")
	args, err := buildJavaArgs(passthrough)
	if err != nil {
		return err
	}
	env := loadServerEnv()

	return daemonRestart(w, javaBin, args, env)
}

func printServerResult(w io.Writer, title, status string, fields ...string) {
	fmt.Fprintln(w, uioutput.Title(title)+"  "+uioutput.Status(status))
	for i := 0; i+1 < len(fields); i += 2 {
		fmt.Fprint(w, uioutput.Row(fields[i], fields[i+1]))
	}
}
