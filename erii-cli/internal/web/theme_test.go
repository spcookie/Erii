package web

import (
	"regexp"
	"strings"
	"testing"
)

func readStatic(t *testing.T, path string) []byte {
	t.Helper()
	data, err := staticFiles.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestNormalizedTheme(t *testing.T) {
	for input, want := range map[string]string{
		"dark":  "dark",
		"light": "light",
		"auto":  "auto",
		"":      "auto",
		"neon":  "auto",
	} {
		if got := normalizedTheme(input); got != want {
			t.Fatalf("normalizedTheme(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestTokenErrorPageReceivesTheme(t *testing.T) {
	page := renderTokenErrorPage("light")
	if !strings.Contains(page, `data-theme="light"`) {
		t.Fatalf("token error page does not contain light theme: %s", page)
	}
	if strings.Contains(page, "__ERII_THEME__") {
		t.Fatal("token error page still contains template marker")
	}
}

func TestStaticConsoleLightPaletteMatchesVercelSpec(t *testing.T) {
	css := string(readStatic(t, "static/css/style.css"))
	for _, token := range []string{
		"--page: #fafafa",
		"--canvas: #ffffff",
		"--ink: #171717",
		"--body: #4d4d4d",
		"--mute: #8f8f8f",
		"--faint: #a1a1a1",
		"--hairline: #ebebeb",
		"--hairline-soft: #f2f2f2",
		"--link: #0070f3",
		"--link-deep: #0761d1",
		"--link-soft: #d3e5ff",
		"--success: #0070f3",
		"--error: #ee0000",
		"--warning: #f5a623",
		"--warning-soft: #ffefcf",
	} {
		if !strings.Contains(css, token) {
			t.Errorf("light theme is missing %q", token)
		}
	}

	page := renderTokenErrorPage("light")
	for _, token := range []string{
		"--page: #fafafa",
		"--surface: #ffffff",
		"--muted: #8f8f8f",
		"--border: #ebebeb",
		"--error: #ee0000",
	} {
		if !strings.Contains(page, token) {
			t.Errorf("token error theme is missing %q", token)
		}
	}
}

func TestIndexPageReceivesThemeAndAssetVersion(t *testing.T) {
	template := string(readStatic(t, "static/index.html"))
	page := renderIndexPage(template, "dark", "build-123")
	for _, marker := range []string{"__ERII_THEME__", "__ERII_ASSET_VERSION__"} {
		if strings.Contains(page, marker) {
			t.Fatalf("rendered index still contains %s", marker)
		}
	}
	for _, asset := range []string{
		"/css/style.css?v=build-123",
		"/js/shuffle.js?v=build-123",
		"/js/side-rays.js?v=build-123",
		"/js/main.js?v=build-123",
	} {
		if !strings.Contains(page, asset) {
			t.Fatalf("rendered index missing versioned asset %q", asset)
		}
	}
}

func TestStaticConsoleLoadsLocalSideRaysEffect(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/side-rays.js"))
	ogl := readStatic(t, "static/js/vendor/ogl.min.mjs")

	for _, want := range []string{
		`id="side-rays"`,
		`data-opacity="1"`,
		`type="module" src="/js/side-rays.js?v=__ERII_ASSET_VERSION__"`,
	} {
		if !strings.Contains(html, want) {
			t.Fatalf("side rays markup missing %q", want)
		}
	}
	for _, want := range []string{
		".side-rays-container",
		`.side-rays-container[data-webgl="ready"]`,
		"pointer-events: none",
		"prefers-reduced-motion: reduce",
		"#topbar:has(#server-menu:not([hidden]))",
	} {
		if !strings.Contains(css, want) {
			t.Fatalf("side rays CSS missing %q", want)
		}
	}
	for _, want := range []string{
		"from './vendor/ogl.min.mjs'",
		"IntersectionObserver",
		"visibilitychange",
		"prefers-reduced-motion: reduce",
	} {
		if !strings.Contains(js, want) {
			t.Fatalf("side rays JS missing %q", want)
		}
	}
	if len(ogl) == 0 {
		t.Fatal("local OGL browser module should not be empty")
	}
	if strings.Contains(html, "cdn.") || strings.Contains(js, "https://") {
		t.Fatal("side rays effect should not depend on a CDN")
	}
}

func TestStaticConsoleLoadsWelcomeShuffleEffect(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	mainJS := string(readStatic(t, "static/js/main.js"))
	shuffleJS := string(readStatic(t, "static/js/shuffle.js"))

	for _, want := range []string{
		`id="terminal-welcome"`,
		`id="welcome-shuffle"`,
		`href="/fonts/PressStart2P-Regular.woff2"`,
		`src="/js/shuffle.js?v=__ERII_ASSET_VERSION__"`,
	} {
		if !strings.Contains(html, want) {
			t.Fatalf("welcome shuffle markup missing %q", want)
		}
	}
	for _, want := range []string{
		".terminal-welcome",
		".shuffle-char-wrapper",
		".shuffle-parent.is-ready",
		"font-family: 'Press Start 2P'",
	} {
		if !strings.Contains(css, want) {
			t.Fatalf("welcome shuffle CSS missing %q", want)
		}
	}
	for _, want := range []string{
		"new window.Shuffle",
		"scrambleCharset: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'",
		"showTerminalWelcome('ERIIBOT')",
		"hideTerminalWelcome()",
	} {
		if !strings.Contains(mainJS, want) {
			t.Fatalf("welcome shuffle integration missing %q", want)
		}
	}
	for _, want := range []string{
		"class Shuffle",
		"strip.animate",
		"prefers-reduced-motion: reduce",
		"mouseenter",
	} {
		if !strings.Contains(shuffleJS, want) {
			t.Fatalf("native shuffle implementation missing %q", want)
		}
	}
	if strings.Contains(shuffleJS, "https://") {
		t.Fatal("welcome shuffle effect should not depend on a CDN")
	}
	if len(readStatic(t, "static/fonts/PressStart2P-Regular.woff2")) < 1000 {
		t.Fatal("local Press Start 2P font should not be empty")
	}
}

func TestStaticConsoleUsesLogoAsFavicon(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	logo := readStatic(t, "static/img/logo.svg")
	if !strings.Contains(html, `<link rel="icon" href="/img/logo.svg" type="image/svg+xml">`) {
		t.Fatal("console markup should use logo.svg as the favicon")
	}
	if !strings.Contains(string(logo), `<svg`) {
		t.Fatal("embedded logo.svg should be an SVG image")
	}
}

func TestStaticConsoleContainsAdaptiveThemeContracts(t *testing.T) {
	html := readStatic(t, "static/index.html")
	css := readStatic(t, "static/css/style.css")
	js := readStatic(t, "static/js/main.js")
	for _, want := range []string{`data-theme="light"`, `data-theme="dark"`, "prefers-color-scheme: dark"} {
		if !strings.Contains(string(css), want) {
			t.Fatalf("theme CSS missing %q", want)
		}
	}
	if !strings.Contains(string(css), "#terminal-mount") || !strings.Contains(string(css), "padding: 8px") {
		t.Fatal("terminal mount should include four-sided inner padding")
	}
	if !strings.Contains(string(css), "grid-template-columns: 240px minmax(0, 1fr)") {
		t.Fatal("desktop command sidebar should use the compact width")
	}
	if !strings.Contains(string(js), "term.open(terminalMount)") || !strings.Contains(string(js), "ro.observe(terminalMount)") {
		t.Fatal("xterm fitting should use the padded terminal mount")
	}
	if !strings.Contains(string(html), `id="terminal-mount"`) {
		t.Fatal("console markup should include the terminal mount")
	}
	for _, want := range []string{"resolvedTheme", "terminalThemes", "theme: resolvedTheme"} {
		if !strings.Contains(string(js), want) {
			t.Fatalf("theme JS missing %q", want)
		}
	}
}

func TestStaticConsoleLoadsRenderingAddons(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	js := string(readStatic(t, "static/js/main.js"))

	for _, addon := range []string{
		"xterm.min.js",
		"addon-fit.min.js",
		"addon-canvas.min.js",
		"addon-unicode11.min.js",
		"addon-web-links.min.js",
		"addon-webgl.min.js",
	} {
		assetPath := "/js/vendor/" + addon
		if !strings.Contains(html, assetPath) {
			t.Fatalf("console markup does not load %s", assetPath)
		}
		if len(readStatic(t, "static/js/vendor/"+addon)) < 1000 {
			t.Fatalf("embedded addon %s is unexpectedly small", addon)
		}
	}

	for _, contract := range []string{
		"new Unicode11Addon.Unicode11Addon()",
		"term.unicode.activeVersion = '11'",
		"new WebLinksAddon.WebLinksAddon(openTerminalLink)",
		"new WebglAddon.WebglAddon()",
		"webglAddon.onContextLoss",
		"new CanvasAddon.CanvasAddon()",
		"new ResizeObserver",
		"scheduleFit(false)",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("xterm addon contract missing %q", contract)
		}
	}
}

func TestStaticConsoleContainsCollapsibleSidebarGroups(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/main.js"))

	if got := strings.Count(html, `class="sidebar-group-toggle"`); got != 5 {
		t.Fatalf("sidebar group toggle count = %d, want 5", got)
	}
	if got := strings.Count(html, `data-sidebar-group=`); got != 5 {
		t.Fatalf("sidebar group key count = %d, want 5", got)
	}
	for _, contract := range []string{
		`class="sidebar-group collapsed"`,
		`aria-expanded="false"`,
		".sidebar-group.collapsed",
		".sidebar-group.collapsed > .sidebar-item",
		".sidebar-group-toggle:focus-visible",
		"erii.console.sidebar.expanded.v2",
		"function setSidebarGroupCollapsed(group, collapsed, persist)",
		"if (otherGroup !== group)",
		"window.localStorage.setItem",
		"window.localStorage.removeItem",
		"revealSidebarItem(item)",
		"initializeSidebarGroups()",
	} {
		if !strings.Contains(html+css+js, contract) {
			t.Fatalf("collapsible sidebar contract missing %q", contract)
		}
	}
}

func TestStaticConsoleEmbedsNerdIconSubset(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/main.js"))
	font := readStatic(t, "static/fonts/EriiNerdIcons.woff2")

	if len(font) < 1000 || len(font) > 10*1024 {
		t.Fatalf("Nerd icon subset size = %d bytes, want 1-10 KB", len(font))
	}
	for _, contract := range []string{
		`rel="preload" href="/fonts/EriiNerdIcons.woff2?v=nerd-v3.4.0-2"`,
		`font-family: 'Erii Nerd Icons'`,
		`unicode-range: U+EDE2`,
		`"Erii Nerd Icons", monospace`,
	} {
		if !strings.Contains(html+css+js, contract) {
			t.Fatalf("Nerd icon font contract missing %q", contract)
		}
	}
}

func TestStaticConsoleContainsRuntimeStatusContracts(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/main.js"))

	for _, contract := range []string{
		`id="core-status"`,
		`id="core-status-text"`,
		`id="setup-command"`,
		`data-requires-core="true"`,
	} {
		if !strings.Contains(html, contract) {
			t.Fatalf("runtime status markup missing %q", contract)
		}
	}
	if got := strings.Count(html, `data-requires-core="true"`); got != 7 {
		t.Fatalf("core-dependent command count = %d, want 7", got)
	}

	for _, contract := range []string{
		"new EventSource('/api/runtime/events?token='",
		"runtimeEvents.addEventListener('status'",
		"connectRuntimeStatus",
		"applyRuntimeStatus",
		"START HERE",
		"revealSidebarItem(setupCommand)",
		"CORE OFFLINE",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("runtime status JS missing %q", contract)
		}
	}
	if strings.Contains(js, "RUNTIME_POLL_INTERVAL") || strings.Contains(js, "pollRuntimeStatus") {
		t.Fatal("runtime status should use server-sent events instead of browser polling")
	}
	if strings.Contains(js, "status.pidFile && status.coreReachable") {
		t.Fatal("healthy externally managed core should not require a CLI PID file")
	}

	for _, contract := range []string{
		".topbar-status.online",
		".sidebar-item:disabled",
		".command-tag.guide",
		".command-tag.unavailable",
	} {
		if !strings.Contains(css, contract) {
			t.Fatalf("runtime status CSS missing %q", contract)
		}
	}
}

func TestStaticConsoleContainsPluginSendPanel(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/main.js"))

	for _, contract := range []string{
		`data-sidebar-group="plugin"`,
		`data-panel="plugin-send"`,
		`id="plugin-send-modal"`,
		`id="plugin-send-result"`,
		`id="plugin-command-input"`,
	} {
		if !strings.Contains(html, contract) {
			t.Fatalf("plugin send markup missing %q", contract)
		}
	}
	for _, contract := range []string{
		".plugin-modal",
		".plugin-result-panel",
		".plugin-command-input:focus",
		".plugin-match-item",
	} {
		if !strings.Contains(css, contract) {
			t.Fatalf("plugin send CSS missing %q", contract)
		}
	}
	for _, contract := range []string{
		"showPluginSendPanel",
		"refreshPluginMatches",
		"'/api/plugin/match?limit=20&query='",
		"fetch('/api/plugin/send'",
		"showPluginSendResult(input, data)",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("plugin send JS missing %q", contract)
		}
	}
}

func TestStaticConsoleContainsAutoCommandContracts(t *testing.T) {
	js := string(readStatic(t, "static/js/main.js"))

	for _, contract := range []string{
		"const requestedCommandText = (urlParams.get('cmd') || '').trim()",
		"function parseAutoCommand(value)",
		"const parts = parseArgs(value)",
		"if (!/^[A-Za-z0-9_-]+$/.test(cmd)) return null",
		"autoCommand = parseAutoCommand(requestedCommandText)",
		"function findCommandItem(cmd, args)",
		"function maybeRunAutoCommand()",
		"if (!terminalOpened || !cliConnected || !ws || ws.readyState !== WebSocket.OPEN) return",
		"if (item && item.disabled) return",
		"autoCommandStarted = true",
		"execCmd(autoCommand.cmd, autoCommand.args, title)",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("auto-command JS missing %q", contract)
		}
	}
}

func TestStaticConsoleReconnectsUntilCLIReturns(t *testing.T) {
	js := string(readStatic(t, "static/js/main.js"))
	for _, contract := range []string{
		"const MAX_RECONNECT_DELAY = 10000",
		"function scheduleReconnect()",
		"setStatus('reconnecting')",
		"reconnectAttempts = 0",
		"if (ws !== socket) return",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("persistent WebSocket reconnect contract missing %q", contract)
		}
	}
	if strings.Contains(js, "maxReconnects") {
		t.Fatal("WebSocket reconnects must not stop after a fixed number of attempts")
	}
}

func TestStaticConsoleContainsLogAndServerControls(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	css := string(readStatic(t, "static/css/style.css"))
	js := string(readStatic(t, "static/js/main.js"))

	for _, contract := range []string{
		`id="debug-log-button"`,
		`id="server-menu-button"`,
		`id="server-menu"`,
		`data-server-action="start"`,
		`data-server-action="stop"`,
		`data-server-action="restart"`,
	} {
		if !strings.Contains(html, contract) {
			t.Fatalf("command control markup missing %q", contract)
		}
	}

	for _, contract := range []string{
		"execCmd('log', [], 'Logs')",
		"activeCmd === 'log'",
		"data: '\\x1b'",
		"logActive ? 'true' : 'false'",
		"const exitedCmd = activeCmd",
		"tuiCommands.indexOf(exitedCmd) !== -1",
		"function terminalHasVisibleOutput()",
		"line.translateToString(true).trim()",
		"term.write('', finishExit)",
		"!hasVisibleOutput",
		"showWelcome()",
		"execCmd('server', [action], title)",
		"runtimeState !== 'offline' || runtimeHasPid",
		"runtimeState === 'starting' || runtimeState === 'unavailable' || runtimeState === 'online'",
		"runtimeHasPid = status.pidFile === true",
		"function positionServerMenu()",
		"serverMenu.contains(event.target)",
		"const tuiCommands = ['config', 'setup', 'manage', 'status', 'chat', 'usage', 'log']",
		"closeServerMenu(true)",
	} {
		if !strings.Contains(js, contract) {
			t.Fatalf("command control JS missing %q", contract)
		}
	}
	if strings.Contains(js, "showWelcome(false)") {
		t.Fatal("command switching must not pre-fill the terminal main buffer with the welcome screen")
	}
	if strings.Contains(js, "const banner = [") || strings.Contains(js, "████") {
		t.Fatal("the legacy xterm ASCII welcome screen must not remain behind the HTML welcome layer")
	}

	for _, contract := range []string{
		".server-menu",
		".topbar-action:focus-visible",
		".terminal-icon-button",
		`.terminal-icon-button[aria-pressed="true"]`,
	} {
		if !strings.Contains(css, contract) {
			t.Fatalf("command control CSS missing %q", contract)
		}
	}
	if strings.Contains(css, "#topbar:has(#server-menu:not([hidden]))") {
		t.Fatal("opening the server menu must not change the topbar stacking context")
	}
	for _, contract := range []string{
		".side-rays-container {\n    position: fixed;\n    z-index: 40;",
		"#topbar {\n    position: relative;\n    z-index: auto;",
		".server-control {\n    position: relative;\n    z-index: 50;",
		".server-menu {\n    position: fixed;\n    z-index: 60;",
	} {
		if !strings.Contains(css, contract) {
			t.Fatalf("server menu and foreground effect stacking contract missing %q", contract)
		}
	}
}

func TestStaticConsoleShellUsesEnglishLabels(t *testing.T) {
	html := string(readStatic(t, "static/index.html"))
	js := string(readStatic(t, "static/js/main.js"))
	if regexp.MustCompile(`[一-龥]`).MatchString(html + js) {
		t.Fatal("web console shell should not mix Chinese and English labels")
	}
	for _, label := range []string{
		">CLI<",
		"Configuration",
		"Data",
		"Chat",
		"System",
		"Connected",
		"Offline",
		"Starting",
		"Online",
	} {
		if !strings.Contains(html+js, label) {
			t.Fatalf("web console shell missing English label %q", label)
		}
	}
}
