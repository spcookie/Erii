package tree

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParsersDoNotStorePresentationDescriptionsOnBranches(t *testing.T) {
	jsonObject := valueToNode("object", "", map[string]any{"enabled": true})
	if got := jsonObject.Description(); got != "" {
		t.Fatalf("JSON object parser description = %q, want empty", got)
	}

	jsonArray := valueToNode("items", "", []any{map[string]any{"enabled": true}})
	array, ok := jsonArray.(*BranchNode)
	if !ok || !array.IsArray() {
		t.Fatal("JSON object array was not parsed as an array branch")
	}
	if got := array.Description(); got != "" {
		t.Fatalf("JSON array parser description = %q, want empty", got)
	}

	hoconRoot := parseHOCON("param {\n  enabled = true\n}\n")
	hoconBranch := hoconRoot.(*BranchNode).Children()[0]
	if got := hoconBranch.Description(); got != "" {
		t.Fatalf("HOCON object parser description = %q, want empty", got)
	}
}

func TestJSONParserDoesNotApplyMainMetadataBeforePluginContextIsKnown(t *testing.T) {
	previousMetadata := GlobalMetadata
	t.Cleanup(func() { GlobalMetadata = previousMetadata })
	GlobalMetadata = &Metadata{
		MainDesc: map[string]string{"onebot": "Main OneBot description"},
	}

	path := filepath.Join(t.TempDir(), "plugin.json")
	if err := os.WriteFile(path, []byte(`{"onebot":{"enabled":true}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	root, err := (&JSONParser{}).Parse(path)
	if err != nil {
		t.Fatal(err)
	}
	onebot := root.(*BranchNode).Children()[0]
	if got := onebot.Description(); got != "" {
		t.Fatalf("raw JSON parser applied main metadata description %q", got)
	}
}
