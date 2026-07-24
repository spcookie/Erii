package status

import (
	"erii-cli/internal/tui/components"

	"github.com/charmbracelet/bubbletea"
)

type RootModel struct {
	stack  components.ScreenStack
	width  int
	height int
}

func NewRootModel(initial tea.Model) *RootModel {
	return &RootModel{
		stack: components.ScreenStack{initial},
	}
}

func (m *RootModel) Push(screen tea.Model) {
	m.stack = append(m.stack, screen)
}

func (m *RootModel) Pop() bool {
	if len(m.stack) <= 1 {
		return false
	}
	m.stack = m.stack[:len(m.stack)-1]
	return true
}

func (m *RootModel) Current() tea.Model {
	if len(m.stack) == 0 {
		return nil
	}
	return m.stack[len(m.stack)-1]
}

func (m *RootModel) Init() tea.Cmd {
	if top := m.Current(); top != nil {
		return top.Init()
	}
	return nil
}

func (m *RootModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		// Forward to current child
		if top := m.Current(); top != nil {
			newTop, _ := top.Update(msg)
			m.stack[len(m.stack)-1] = newTop
		}
		return m, nil
	case PopMsg:
		if !components.PopScreen(&m.stack, m.width, m.height) {
			return m, tea.Quit
		}
		return m, nil
	case PushGroupListMsg:
		return m, components.PushScreen(&m.stack,
			NewGroupListModel(m.stack[0].(*components.BotListModel).API(), msg.Bot),
			m.width, m.height)
	case PushStatusViewMsg:
		return m, components.PushScreen(&m.stack,
			NewStatusViewModel(m.stack[0].(*components.BotListModel).API(), msg.Bot, msg.Group),
			m.width, m.height)
	}

	if top := m.Current(); top != nil {
		newTop, cmd := top.Update(msg)
		m.stack[len(m.stack)-1] = newTop
		return m, cmd
	}
	return m, nil
}

func (m *RootModel) View() string {
	if top := m.Current(); top != nil {
		return top.View()
	}
	return ""
}
