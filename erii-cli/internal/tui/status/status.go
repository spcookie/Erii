package status

import (
	"erii-cli/internal/api"
	"erii-cli/internal/tui/components"
	"fmt"

	"github.com/charmbracelet/bubbletea"
)

func Start() error {
	for {
		client, err := api.NewClientFromIPC()
		if err != nil {
			model := components.NewConnectionErrorModel(err)
			p := tea.NewProgram(model, tea.WithAltScreen())
			if _, err := p.Run(); err != nil {
				return fmt.Errorf("failed to run TUI: %w", err)
			}
			if !model.ShouldRetry() {
				return nil
			}
			continue
		}

		initial := NewBotListModel(client)
		root := NewRootModel(initial)

		p := tea.NewProgram(root, tea.WithAltScreen())
		if _, err := p.Run(); err != nil {
			return fmt.Errorf("failed to run TUI: %w", err)
		}

		return nil
	}
}
