package web

import (
	"net/http"
	"reflect"
	"testing"
)

func TestPluginProxyErrorStatusPreservesBackendHTTPError(t *testing.T) {
	if got := pluginProxyErrorStatus(http.StatusGatewayTimeout); got != http.StatusGatewayTimeout {
		t.Fatalf("pluginProxyErrorStatus(504) = %d, want 504", got)
	}
	if got := pluginProxyErrorStatus(0); got != http.StatusBadGateway {
		t.Fatalf("pluginProxyErrorStatus(0) = %d, want 502", got)
	}
}

func TestWSHandlerCommandArgsIncludesGlobalPathFlags(t *testing.T) {
	h := &WSHandler{
		ConfDir:     "./.uesugi/conf",
		MetaConfDir: "./.uesugi/.conf",
		EriiDir:     "./.uesugi/.erii",
		PluginDir:   "./erii-plugins/build/plugins",
		OptsPath:    "./.uesugi/opts",
		LogsPath:    "./.uesugi/logs",
	}

	got := h.commandArgs([]string{"refresh"})
	want := []string{
		"--conf-dir", "./.uesugi/conf",
		"--meta-conf-dir", "./.uesugi/.conf",
		"--erii-dir", "./.uesugi/.erii",
		"--plugin-dir", "./erii-plugins/build/plugins",
		"--opts-path", "./.uesugi/opts",
		"--logs-path", "./.uesugi/logs",
		"refresh",
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("commandArgs() = %#v, want %#v", got, want)
	}
}

func TestWSHandlerCommandArgsUsesResolvedBrowserThemeForAuto(t *testing.T) {
	h := &WSHandler{Theme: "auto"}
	got := h.commandArgs([]string{"refresh"}, "light")
	want := []string{"--theme", "light", "refresh"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("commandArgs() = %#v, want %#v", got, want)
	}
}

func TestWSHandlerCommandArgsRejectsUnknownBrowserTheme(t *testing.T) {
	h := &WSHandler{Theme: "auto"}
	got := h.commandArgs([]string{"refresh"}, "neon")
	want := []string{"refresh"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("commandArgs() = %#v, want %#v", got, want)
	}
}

func TestWSHandlerExplicitThemeOverridesBrowserTheme(t *testing.T) {
	h := &WSHandler{Theme: "dark"}
	got := h.commandArgs([]string{"refresh"}, "light")
	want := []string{"--theme", "dark", "refresh"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("commandArgs() = %#v, want %#v", got, want)
	}
}
