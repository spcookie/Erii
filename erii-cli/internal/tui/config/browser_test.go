package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"erii-cli/internal/config/tree"
	eriipath "erii-cli/internal/path"

	tea "github.com/charmbracelet/bubbletea"
)

func TestArrayBranchRenameKeepsIndexedTitleAndClearsDescription(t *testing.T) {
	previousMetadata := tree.GlobalMetadata
	t.Cleanup(func() { tree.GlobalMetadata = previousMetadata })
	tree.GlobalMetadata = &tree.Metadata{
		MainDesc:   map[string]string{"items.[0]": "Old description"},
		CopyPlugin: make(map[string][]string),
	}

	root := tree.NewBranch("root", "")
	items := tree.NewBranch("items", "")
	items.SetIsArray(true)
	item := tree.NewBranch("[0]", "Old description")
	items.AddChild(item)
	root.AddChild(items)

	model := NewBrowserModel(root, "Config", nil, nil)
	model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	if !model.canModify() || !model.canEditDesc() {
		t.Fatal("array parent should allow item modification without a copy rule")
	}
	model.Update(tea.KeyMsg{Type: tea.KeyCtrlR})
	if !model.renaming || model.renameForm == nil {
		t.Fatal("ctrl+r should edit a branch under a copyable parent")
	}

	// The array title is not part of the form; changing the backing value must
	// still not allow an array element to lose its generated index title.
	model.renameValue = "custom-title"
	for range len("Old description") {
		model.Update(tea.KeyMsg{Type: tea.KeyBackspace})
	}
	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	runBrowserCommands(t, model, cmd)

	if got := item.Title(); got != "[0]" {
		t.Fatalf("array item title = %q, want %q", got, "[0]")
	}
	if got := item.Description(); got != "" {
		t.Fatalf("array item description = %q, want empty", got)
	}
	if _, exists := tree.GlobalMetadata.MainDesc["items.[0]"]; exists {
		t.Fatal("empty description should remove its metadata override")
	}
	if got := (NodeItem{Node: item}).Description(); got != "Object" {
		t.Fatalf("branch without a custom description renders as %q", got)
	}
}

func TestBranchDefaultDescriptionUsesNodeType(t *testing.T) {
	object := tree.NewBranch("object", "")
	if got := (NodeItem{Node: object}).Description(); got != "Object" {
		t.Fatalf("object description = %q, want Object", got)
	}

	array := tree.NewBranch("array", "")
	array.SetIsArray(true)
	array.AddChild(tree.NewBranch("[0]", ""))
	array.AddChild(tree.NewBranch("[1]", ""))
	if got := (NodeItem{Node: array}).Description(); got != "Array (2 items)" {
		t.Fatalf("array description = %q, want Array (2 items)", got)
	}

	custom := tree.NewBranch("custom", "Custom description")
	if got := (NodeItem{Node: custom}).Description(); got != "Custom description" {
		t.Fatalf("custom description = %q", got)
	}
}

func TestArrayAddUsesGeneratedIndexTitle(t *testing.T) {
	previousMetadata := tree.GlobalMetadata
	t.Cleanup(func() { tree.GlobalMetadata = previousMetadata })
	tree.GlobalMetadata = &tree.Metadata{
		CopyPlugin: make(map[string][]string),
		MainDesc:   make(map[string]string),
	}

	root := tree.NewBranch("root", "")
	items := tree.NewBranch("items", "")
	items.SetIsArray(true)
	items.AddChild(tree.NewBranch("[0]", ""))
	root.AddChild(items)

	model := NewBrowserModel(root, "Config", nil, nil)
	model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	model.Update(tea.KeyMsg{Type: tea.KeyCtrlN})
	if !model.adding || model.addForm == nil {
		t.Fatal("ctrl+n should add under a copyable array parent")
	}

	model.addTitle = "custom-title"
	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	runBrowserCommands(t, model, cmd)

	children := items.Children()
	if len(children) != 2 {
		t.Fatalf("array child count = %d, want 2", len(children))
	}
	if got := children[1].Title(); got != "[1]" {
		t.Fatalf("new array item title = %q, want %q", got, "[1]")
	}
}

func TestCopyableParentAllowsClearingBranchDescription(t *testing.T) {
	previousMetadata := tree.GlobalMetadata
	t.Cleanup(func() { tree.GlobalMetadata = previousMetadata })
	tree.GlobalMetadata = &tree.Metadata{
		MainDesc:   map[string]string{"items.entry": "Old description"},
		CopyMain:   []string{"items"},
		CopyPlugin: make(map[string][]string),
	}

	root := tree.NewBranch("root", "")
	items := tree.NewBranch("items", "")
	entry := tree.NewBranch("entry", "Old description")
	items.AddChild(entry)
	root.AddChild(items)

	model := NewBrowserModel(root, "Config", nil, nil)
	model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	model.Update(tea.KeyMsg{Type: tea.KeyCtrlE})
	if !model.editingDesc || model.editDescForm == nil {
		t.Fatal("ctrl+e should edit a branch under a copyable parent")
	}

	for range len("Old description") {
		model.Update(tea.KeyMsg{Type: tea.KeyBackspace})
	}
	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	runBrowserCommands(t, model, cmd)

	if got := entry.Description(); got != "" {
		t.Fatalf("branch description = %q, want empty", got)
	}
	if _, exists := tree.GlobalMetadata.MainDesc["items.entry"]; exists {
		t.Fatal("empty description should remove its metadata override")
	}
}

func TestPluginEditDescriptionWritesPluginSchema(t *testing.T) {
	previousMetadata := tree.GlobalMetadata
	previousSchemaDir := eriipath.PluginSchemaDir
	t.Cleanup(func() {
		tree.GlobalMetadata = previousMetadata
		eriipath.PluginSchemaDir = previousSchemaDir
	})

	eriipath.PluginSchemaDir = filepath.Join(t.TempDir(), ".conf", "plugin-config", "schema")
	if err := os.MkdirAll(eriipath.PluginSchemaDir, 0755); err != nil {
		t.Fatal(err)
	}
	schemaPath := filepath.Join(eriipath.PluginSchemaDir, "music.json")
	if err := os.WriteFile(schemaPath, []byte(`{"__desc__":{"items.entry":"Old description"},"__value__":{}}`), 0644); err != nil {
		t.Fatal(err)
	}
	tree.GlobalMetadata = &tree.Metadata{
		MainDesc:   make(map[string]string),
		PluginDesc: map[string]map[string]string{"music": {"items.entry": "Old description"}},
		CopyPlugin: map[string][]string{"music": {"items"}},
	}

	root := tree.NewBranch("root", "")
	items := tree.NewBranch("items", "")
	entry := tree.NewBranch("entry", "Old description")
	items.AddChild(entry)
	root.AddChild(items)

	model := NewBrowserModel(root, "Music Config", nil, nil).WithPlugin("music")
	model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	model.Update(tea.KeyMsg{Type: tea.KeyCtrlE})
	if !model.editingDesc || model.editDescForm == nil {
		t.Fatal("ctrl+e should edit plugin description")
	}
	for range len("Old description") {
		model.Update(tea.KeyMsg{Type: tea.KeyBackspace})
	}
	model.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("New description")})
	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	runBrowserCommands(t, model, cmd)

	data, err := os.ReadFile(schemaPath)
	if err != nil {
		t.Fatal(err)
	}
	var schema map[string]any
	if err := json.Unmarshal(data, &schema); err != nil {
		t.Fatal(err)
	}
	desc := schema["__desc__"].(map[string]any)
	if got := desc["items.entry"]; got != "New description" {
		t.Fatalf("plugin schema description = %v", got)
	}
	if _, leaked := tree.GlobalMetadata.MainDesc["items.entry"]; leaked {
		t.Fatal("plugin description leaked into main desc metadata")
	}
}

func TestPluginRenameMovesDescriptionWithinPluginSchema(t *testing.T) {
	previousMetadata := tree.GlobalMetadata
	previousSchemaDir := eriipath.PluginSchemaDir
	t.Cleanup(func() {
		tree.GlobalMetadata = previousMetadata
		eriipath.PluginSchemaDir = previousSchemaDir
	})

	eriipath.PluginSchemaDir = filepath.Join(t.TempDir(), ".conf", "plugin-config", "schema")
	if err := os.MkdirAll(eriipath.PluginSchemaDir, 0755); err != nil {
		t.Fatal(err)
	}
	schemaPath := filepath.Join(eriipath.PluginSchemaDir, "music.json")
	if err := os.WriteFile(schemaPath, []byte(`{"__desc__":{"items.entry":"Old description"},"__value__":{}}`), 0644); err != nil {
		t.Fatal(err)
	}
	tree.GlobalMetadata = &tree.Metadata{
		MainDesc:   make(map[string]string),
		PluginDesc: map[string]map[string]string{"music": {"items.entry": "Old description"}},
		CopyPlugin: map[string][]string{"music": {"items"}},
	}

	root := tree.NewBranch("root", "")
	model := NewBrowserModel(root, "Music Config", nil, nil).WithPlugin("music")
	model.saveRenamedDesc("items.entry", "items.renamed", "Renamed description")

	data, err := os.ReadFile(schemaPath)
	if err != nil {
		t.Fatal(err)
	}
	var schema map[string]any
	if err := json.Unmarshal(data, &schema); err != nil {
		t.Fatal(err)
	}
	desc := schema["__desc__"].(map[string]any)
	if _, exists := desc["items.entry"]; exists {
		t.Fatal("old plugin description path was not removed")
	}
	if got := desc["items.renamed"]; got != "Renamed description" {
		t.Fatalf("renamed plugin description = %v", got)
	}
	if _, leaked := tree.GlobalMetadata.MainDesc["items.renamed"]; leaked {
		t.Fatal("renamed plugin description leaked into main desc metadata")
	}
}

func runBrowserCommands(t *testing.T, model *BrowserModel, initial tea.Cmd) {
	t.Helper()
	if initial == nil {
		t.Fatal("form did not return a submission command")
	}
	queue := []tea.Cmd{initial}
	for steps := 0; len(queue) > 0; steps++ {
		if steps > 100 {
			t.Fatal("form submission command chain did not finish")
		}
		cmd := queue[0]
		queue = queue[1:]
		if cmd == nil {
			continue
		}
		msg := cmd()
		if batch, ok := msg.(tea.BatchMsg); ok {
			queue = append(queue, batch...)
			continue
		}
		_, next := model.Update(msg)
		if next != nil {
			queue = append(queue, next)
		}
	}
}
