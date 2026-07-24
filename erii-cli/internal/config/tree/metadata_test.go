package tree

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSaveDescEmptyRemovesOverride(t *testing.T) {
	previousMetadata := GlobalMetadata
	previousMetaDir := metaDir
	t.Cleanup(func() {
		GlobalMetadata = previousMetadata
		metaDir = previousMetaDir
	})

	dir := t.TempDir()
	metaDir = dir
	GlobalMetadata = &Metadata{
		MainDesc: map[string]string{"items.entry": "Old description"},
	}

	if err := SaveDesc("items.entry", "  "); err != nil {
		t.Fatal(err)
	}
	if _, exists := GlobalMetadata.MainDesc["items.entry"]; exists {
		t.Fatal("empty description should remove the in-memory override")
	}

	data, err := os.ReadFile(filepath.Join(dir, "desc.json"))
	if err != nil {
		t.Fatal(err)
	}
	var persisted map[string]string
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatal(err)
	}
	if _, exists := persisted["items.entry"]; exists {
		t.Fatal("empty description should remove the persisted override")
	}
}

func TestPluginMetadataDoesNotFallBackToMainConfig(t *testing.T) {
	previousMetadata := GlobalMetadata
	previousValueConfig := GlobalValueConfig
	t.Cleanup(func() {
		GlobalMetadata = previousMetadata
		GlobalValueConfig = previousValueConfig
	})

	GlobalMetadata = &Metadata{
		MainDesc:   map[string]string{"onebot": "Main OneBot description"},
		PluginDesc: map[string]map[string]string{"music": {"onebot.*": "Plugin bot"}},
		MainEnum:   map[string][]string{"onebot.mode": {"main"}},
		PluginEnum: map[string]map[string][]string{"music": {}},
		CopyMain:   []string{"onebot"},
		CopyPlugin: map[string][]string{"music": {}},
	}
	GlobalValueConfig = &ValueConfigStore{
		Main:   map[string]*ValueConfig{"onebot.port": {Type: "number"}},
		Plugin: map[string]map[string]*ValueConfig{"music": {}},
	}

	if got := GetDesc("music", "root.onebot"); got != "" {
		t.Fatalf("plugin description leaked from main config: %q", got)
	}
	if got := GetDesc("music", "root.onebot.erii"); got != "Plugin bot" {
		t.Fatalf("plugin wildcard description = %q", got)
	}
	if got := GetDesc("", "root.onebot"); got != "Main OneBot description" {
		t.Fatalf("main description = %q", got)
	}
	if got := GetEnum("music", "root.onebot.mode"); got != nil {
		t.Fatalf("plugin enum leaked from main config: %v", got)
	}
	if got := GetValueConfig("music", "root.onebot.port"); got != nil {
		t.Fatalf("plugin value config leaked from main config: %+v", got)
	}
	if CanCopy("music", "onebot") {
		t.Fatal("plugin copy permission leaked from main config")
	}
}
