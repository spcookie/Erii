package config

import (
	"testing"

	"erii-cli/internal/config/tree"
)

func TestFormatNumberValueUsesPlainDecimal(t *testing.T) {
	tests := []struct {
		name  string
		value any
		want  string
	}{
		{name: "integral float64", value: float64(33554432), want: "33554432"},
		{name: "decimal float64", value: 12.5, want: "12.5"},
		{name: "small decimal", value: 0.0000001, want: "0.0000001"},
		{name: "integer", value: int64(6700), want: "6700"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := formatNumberValue(tt.value); got != tt.want {
				t.Fatalf("formatNumberValue(%v) = %q, want %q", tt.value, got, tt.want)
			}
		})
	}
}

func TestNumberEditorUsesIntegerStyleForFloat64Value(t *testing.T) {
	leaf := tree.NewLeaf("intents", "", tree.TypeNumber, float64(33554432))

	model := NewLeafEditorModel(leaf, nil)

	if model.formValue != "33554432" {
		t.Fatalf("form value = %q, want %q", model.formValue, "33554432")
	}
}

func TestNumberEditorUsesIntegerStyleForDefault(t *testing.T) {
	leaf := tree.NewLeaf("intents", "", tree.TypeNumber, nil)
	leaf.SetValueConfig(&tree.ValueConfig{
		Type:    "number",
		Default: float64(33554432),
	})

	model := NewLeafEditorModel(leaf, nil)

	if model.formValue != "33554432" {
		t.Fatalf("default form value = %q, want %q", model.formValue, "33554432")
	}
}

func TestNumberBrowserUsesIntegerStyleForFloat64Value(t *testing.T) {
	leaf := tree.NewLeaf("intents", "", tree.TypeNumber, float64(33554432))

	if got := formatLeafValue(leaf); got != "33554432" {
		t.Fatalf("browser value = %q, want %q", got, "33554432")
	}
}
