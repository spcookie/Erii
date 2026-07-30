package path

import (
	"path/filepath"
	"testing"
)

func TestInitPathsSetsMcpDir(t *testing.T) {
	confDir := t.TempDir()
	metaDir := t.TempDir()
	eriiDir := t.TempDir()
	pluginDir := t.TempDir()
	optsDir := t.TempDir()

	InitPaths(confDir, metaDir, eriiDir, pluginDir, optsDir)

	if got, want := McpDir, filepath.Join(confDir, "mcp"); got != want {
		t.Fatalf("McpDir = %q, want %q", got, want)
	}
	if got := ConfMetaDir; got != metaDir {
		t.Fatalf("ConfMetaDir = %q, want %q", got, metaDir)
	}
	if got, want := PluginConfigDir, filepath.Join(confDir, "plugin"); got != want {
		t.Fatalf("PluginConfigDir = %q, want %q", got, want)
	}
	if got, want := PluginSchemaDir, filepath.Join(metaDir, "schema"); got != want {
		t.Fatalf("PluginSchemaDir = %q, want %q", got, want)
	}
	if got := EriiDir; got != eriiDir {
		t.Fatalf("EriiDir = %q, want %q", got, eriiDir)
	}
	if got := PluginDir; got != pluginDir {
		t.Fatalf("PluginDir = %q, want %q", got, pluginDir)
	}
	if got := OptsPath; got != optsDir {
		t.Fatalf("OptsPath = %q, want %q", got, optsDir)
	}
}
