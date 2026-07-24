package md

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	tea "github.com/charmbracelet/bubbletea"
)

func TestSoulFieldBrowserEnterSubmitsEditor(t *testing.T) {
	model := NewFieldBrowserModel(
		t.TempDir()+"/souls/test.md",
		"test.md",
		map[string]string{"name": "Erii"},
	)

	model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	if model.fieldEditor == nil {
		t.Fatal("enter did not open the selected frontmatter field")
	}

	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	if cmd == nil {
		t.Fatal("enter did not return the huh form submission command")
	}
	runFieldBrowserCommands(t, model, cmd)
	if model.fieldEditor != nil {
		t.Fatal("frontmatter field editor did not close after submission")
	}
}

func TestContentEditorEnterReturnsFormCommand(t *testing.T) {
	model := NewContentEditorModel(
		t.TempDir()+"/test.md",
		"content",
		"",
		nil,
		nil,
	)

	_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	if cmd == nil {
		t.Fatal("enter did not return the huh form submission command")
	}
}

func TestFieldBrowserSavesBooleanValuesForSoulsAndRules(t *testing.T) {
	tests := []struct {
		name      string
		directory string
		key       string
		initial   string
		saved     bool
	}{
		{name: "rules global false", directory: "rules", key: "global", initial: "true", saved: false},
		{name: "soul boolean true", directory: "souls", key: "enabled", initial: "false", saved: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), tt.directory)
			if err := os.MkdirAll(dir, 0o755); err != nil {
				t.Fatal(err)
			}
			filePath := filepath.Join(dir, "test.md")
			content := "---\n" + tt.key + ": " + tt.initial + "\n---\n\ncontent\n"
			if err := os.WriteFile(filePath, []byte(content), 0o644); err != nil {
				t.Fatal(err)
			}

			model := NewFieldBrowserModel(filePath, "test.md", map[string]string{tt.key: tt.initial})
			model.Update(tea.KeyMsg{Type: tea.KeyEnter})
			if model.fieldEditor == nil || !model.fieldEditor.isBoolean {
				t.Fatal("boolean field did not open with a confirm editor")
			}
			model.fieldEditor.boolValue = tt.saved

			_, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
			runFieldBrowserCommands(t, model, cmd)

			want := tt.key + ": " + strconv.FormatBool(tt.saved)
			data, err := os.ReadFile(filePath)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(string(data), want) {
				t.Fatalf("saved frontmatter = %q, want it to contain %q", data, want)
			}
		})
	}
}

func runFieldBrowserCommands(t *testing.T, model *FieldBrowserModel, initial tea.Cmd) {
	t.Helper()
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
