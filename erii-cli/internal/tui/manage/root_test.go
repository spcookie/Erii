package manage

import (
	"testing"

	tea "github.com/charmbracelet/bubbletea"
)

type resizeCommandModel struct{}

func (resizeCommandModel) Init() tea.Cmd { return nil }
func (resizeCommandModel) View() string  { return "" }
func (m resizeCommandModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	if _, ok := msg.(tea.WindowSizeMsg); ok {
		return m, func() tea.Msg { return RefreshMsg{} }
	}
	return m, nil
}

func TestRootModelForwardsResizeCommand(t *testing.T) {
	root := NewRootModel(resizeCommandModel{})
	_, cmd := root.Update(tea.WindowSizeMsg{Width: 120, Height: 30})
	if cmd == nil {
		t.Fatal("root model discarded the current screen's resize command")
	}
	if _, ok := cmd().(RefreshMsg); !ok {
		t.Fatal("root model returned an unexpected resize command")
	}
}
