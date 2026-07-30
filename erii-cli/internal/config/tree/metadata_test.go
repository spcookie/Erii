package tree

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	eriipath "erii-cli/internal/path"
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

func TestSavePluginDescPersistsOnlyCorrespondingSchema(t *testing.T) {
	previousMetadata := GlobalMetadata
	previousMetaDir := metaDir
	previousSchemaDir := eriipath.PluginSchemaDir
	t.Cleanup(func() {
		GlobalMetadata = previousMetadata
		metaDir = previousMetaDir
		eriipath.PluginSchemaDir = previousSchemaDir
	})

	root := t.TempDir()
	metaDir = filepath.Join(root, ".conf")
	eriipath.PluginSchemaDir = filepath.Join(metaDir, "schema")
	if err := os.MkdirAll(eriipath.PluginSchemaDir, 0755); err != nil {
		t.Fatal(err)
	}
	mainDesc := []byte("{\n  \"main.key\": \"Main description\"\n}\n")
	if err := os.MkdirAll(metaDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(metaDir, "desc.json"), mainDesc, 0644); err != nil {
		t.Fatal(err)
	}

	schemaPath := filepath.Join(eriipath.PluginSchemaDir, "music.json")
	schema := []byte(`{
  "__desc__": {"__overall__": "Music plugin", "api.token": "Old description"},
  "__enum__": {"mode": ["fast", "safe"]},
  "__copy__": ["accounts"],
  "__value__": {"api.token": {"type": "string"}}
}`)
	if err := os.WriteFile(schemaPath, schema, 0644); err != nil {
		t.Fatal(err)
	}
	GlobalMetadata = &Metadata{
		MainDesc:   map[string]string{"main.key": "Main description"},
		PluginDesc: map[string]map[string]string{"music": {"api.token": "Old description"}},
	}

	if err := SaveDescForPlugin("music", "root.api.token", "New description"); err != nil {
		t.Fatal(err)
	}

	unchangedMainDesc, err := os.ReadFile(filepath.Join(metaDir, "desc.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(unchangedMainDesc) != string(mainDesc) {
		t.Fatalf("main desc.json was modified:\n%s", unchangedMainDesc)
	}

	data, err := os.ReadFile(schemaPath)
	if err != nil {
		t.Fatal(err)
	}
	var persisted map[string]any
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatal(err)
	}
	desc := persisted["__desc__"].(map[string]any)
	if got := desc["api.token"]; got != "New description" {
		t.Fatalf("plugin description = %v", got)
	}
	if got := desc["__overall__"]; got != "Music plugin" {
		t.Fatalf("overall description was not preserved: %v", got)
	}
	if _, ok := persisted["__enum__"]; !ok {
		t.Fatal("__enum__ was removed")
	}
	if _, ok := persisted["__copy__"]; !ok {
		t.Fatal("__copy__ was removed")
	}
	if _, ok := persisted["__value__"]; !ok {
		t.Fatal("__value__ was removed")
	}

	if err := SaveDescForPlugin("music", "api.token", "  "); err != nil {
		t.Fatal(err)
	}
	data, err = os.ReadFile(schemaPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatal(err)
	}
	desc = persisted["__desc__"].(map[string]any)
	if _, ok := desc["api.token"]; ok {
		t.Fatal("empty plugin description should remove the schema override")
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
