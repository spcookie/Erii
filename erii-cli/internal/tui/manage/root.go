package manage

import (
	"erii-cli/internal/api"
	"erii-cli/internal/tui/components"

	"github.com/charmbracelet/bubbletea"
)

// Navigation messages
type (
	PopMsg            struct{}
	PopAndRefreshMsg  struct{}
	PushGroupListMsg  struct{ Bot api.BotInfo }
	PushManageMenuMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushTableMsg struct {
		ResourceType ResourceType
		Bot          api.BotInfo
		Group        api.GroupInfo
	}
	PushMessageMenuMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushMemoryMenuMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushMemorySearchMsg struct {
		Mode  MemorySearchMode
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushMemeMenuMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushMemeSearchMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushEditMsg struct {
		ResourceType ResourceType
		Bot          api.BotInfo
		Group        api.GroupInfo
		Data         any
		IsCreate     bool
	}
	PushStateMenuMsg struct {
		Bot   api.BotInfo
		Group api.GroupInfo
	}
	PushStateDetailMsg struct {
		StateType StateType
		Bot       api.BotInfo
		Group     api.GroupInfo
	}
	RefreshMsg struct{}
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

func (m *RootModel) pushWithSize(screen tea.Model) (tea.Model, tea.Cmd) {
	cmd := components.PushScreen(&m.stack, screen, m.width, m.height)
	return m, cmd
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
		if top := m.Current(); top != nil {
			newTop, cmd := top.Update(msg)
			m.stack[len(m.stack)-1] = newTop
			return m, cmd
		}
		return m, nil

	case PopMsg:
		if !m.Pop() {
			return m, tea.Quit
		}
		if len(m.stack) > 0 && m.width > 0 {
			if top := m.Current(); top != nil {
				newTop, _ := top.Update(tea.WindowSizeMsg{Width: m.width, Height: m.height})
				m.stack[len(m.stack)-1] = newTop
			}
		}
		return m, nil

	case PopAndRefreshMsg:
		if !m.Pop() {
			return m, tea.Quit
		}
		var cmds []tea.Cmd
		if len(m.stack) > 0 {
			if top := m.Current(); top != nil {
				newTop, cmd := top.Update(RefreshMsg{})
				m.stack[len(m.stack)-1] = newTop
				if cmd != nil {
					cmds = append(cmds, cmd)
				}
			}
		}
		if len(m.stack) > 0 && m.width > 0 {
			if top := m.Current(); top != nil {
				newTop, cmd := top.Update(tea.WindowSizeMsg{Width: m.width, Height: m.height})
				m.stack[len(m.stack)-1] = newTop
				if cmd != nil {
					cmds = append(cmds, cmd)
				}
			}
		}
		return m, tea.Batch(cmds...)

	case PushGroupListMsg:
		return m.pushWithSize(NewGroupListModel(getAPI(m.stack[0]), msg.Bot))

	case PushManageMenuMsg:
		return m.pushWithSize(NewManageMenuModel(msg.Bot, msg.Group))

	case PushTableMsg:
		return m.pushWithSize(NewDataTableModel(getAPI(m.stack[0]), msg.ResourceType, msg.Bot, msg.Group))

	case PushMessageMenuMsg:
		return m.pushWithSize(NewMessageMenuModel(msg.Bot, msg.Group))

	case PushMemoryMenuMsg:
		return m.pushWithSize(NewMemoryMenuModel(msg.Bot, msg.Group))

	case PushMemorySearchMsg:
		return m.pushWithSize(NewMemorySearchModel(getAPI(m.stack[0]), msg.Mode, msg.Bot, msg.Group))

	case PushMemeMenuMsg:
		return m.pushWithSize(NewMemeMenuModel(msg.Bot, msg.Group))

	case PushMemeSearchMsg:
		return m.pushWithSize(NewMemeSearchModel(getAPI(m.stack[0]), msg.Bot, msg.Group))

	case PushEditMsg:
		return m.pushWithSize(NewEditFormModel(getAPI(m.stack[0]), msg.ResourceType, msg.Bot, msg.Group, msg.Data, msg.IsCreate))

	case PushStateMenuMsg:
		return m.pushWithSize(NewStateMenuModel(msg.Bot, msg.Group))

	case PushStateDetailMsg:
		return m.pushWithSize(NewStateDetailModel(getAPI(m.stack[0]), msg.StateType, msg.Bot, msg.Group))
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

func getAPI(model tea.Model) *api.Client {
	switch m := model.(type) {
	case *components.BotListModel:
		return m.API()
	default:
		return nil
	}
}
