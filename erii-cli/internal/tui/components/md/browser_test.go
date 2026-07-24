package md

import (
	"os"
	"path/filepath"
	"testing"

	tea "github.com/charmbracelet/bubbletea"
)

func TestBrowserFormEscapeCancelsWithoutUpdatingNilForm(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "test.md"), []byte("# Test\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name       string
		openKey    tea.KeyType
		isActive   func(*BrowserModel) bool
		formExists func(*BrowserModel) bool
	}{
		{
			name:       "rename",
			openKey:    tea.KeyCtrlR,
			isActive:   func(m *BrowserModel) bool { return m.renaming },
			formExists: func(m *BrowserModel) bool { return m.renameForm != nil },
		},
		{
			name:       "delete",
			openKey:    tea.KeyCtrlD,
			isActive:   func(m *BrowserModel) bool { return m.deleting },
			formExists: func(m *BrowserModel) bool { return m.deleteForm != nil },
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			model := NewBrowserModel(dir, "Test")
			model.Update(tea.KeyMsg{Type: tt.openKey})
			if !tt.isActive(model) || !tt.formExists(model) {
				t.Fatalf("%s form was not opened", tt.name)
			}

			model.Update(tea.KeyMsg{Type: tea.KeyEsc})
			if tt.isActive(model) || tt.formExists(model) {
				t.Fatalf("%s form was not cancelled", tt.name)
			}
		})
	}
}
