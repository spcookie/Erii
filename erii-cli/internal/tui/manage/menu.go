package manage

import (
	"erii-cli/internal/api"
	style "erii-cli/internal/ui/theme"
	"fmt"

	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

type menuItem struct {
	resourceType ResourceType
	action       string
	title        string
	desc         string
}

func (i menuItem) Title() string       { return i.title }
func (i menuItem) Description() string { return i.desc }
func (i menuItem) FilterValue() string { return i.title }

type menuKeys struct {
	Up    key.Binding
	Down  key.Binding
	Enter key.Binding
	Back  key.Binding
	Quit  key.Binding
}

func (k menuKeys) ShortHelp() []key.Binding {
	return []key.Binding{k.Up, k.Down, k.Enter, k.Back, k.Quit}
}

func (k menuKeys) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.Up, k.Down, k.Enter},
		{k.Back, k.Quit},
	}
}

var menuDefaultKeys = menuKeys{
	Up: key.NewBinding(
		key.WithKeys("up", "k"),
		key.WithHelp("\xe2\x86\x91/k", "up"),
	),
	Down: key.NewBinding(
		key.WithKeys("down", "j"),
		key.WithHelp("\xe2\x86\x93/j", "down"),
	),
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "select"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc", "backspace"),
		key.WithHelp("esc", "back"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
}

type ManageMenuModel struct {
	bot    api.BotInfo
	group  api.GroupInfo
	list   list.Model
	keys   menuKeys
	width  int
	height int
}

func NewManageMenuModel(bot api.BotInfo, group api.GroupInfo) *ManageMenuModel {
	delegate := style.StyleDelegate(list.NewDefaultDelegate())
	l := list.New([]list.Item{}, delegate, 0, 0)
	l.Title = style.Title("Select Data Type")
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	l.SetShowHelp(false)
	l.Styles.Title = style.ListTitle
	l.Styles.HelpStyle = lipgloss.NewStyle().Foreground(style.TextMuted)

	l.SetItems(manageMenuItems(webMenuIconsEnabled()))

	return &ManageMenuModel{
		bot:   bot,
		group: group,
		list:  l,
		keys:  menuDefaultKeys,
	}
}

func manageMenuItems(web bool) []list.Item {
	return []list.Item{
		menuItem{resourceType: ResourceFacts, action: "pushMemoryMenu", title: menuTitle(web, "\uee9c", "🗂️", "Memory"), desc: "Manage and search group memory"},
		menuItem{resourceType: ResourceProfiles, action: "pushTable", title: menuTitle(web, "\uf0c0", "🪪", "User Profiles"), desc: "Manage user profiles"},
		menuItem{resourceType: ResourceSummaries, action: "pushTable", title: menuTitle(web, "\uf15c", "📑", "Summaries"), desc: "Manage conversation summaries"},
		menuItem{resourceType: ResourceMemes, action: "pushMemeMenu", title: menuTitle(web, "\uf03e", "🎭", "Memes"), desc: "Manage meme metadata and search"},
		menuItem{resourceType: ResourceVocabularies, action: "pushTable", title: menuTitle(web, "\uede2", "📖", "Vocabulary"), desc: "Manage learned vocabulary"},
		menuItem{action: "pushMessageMenu", title: menuTitle(web, "\uf086", "💬", "Messages"), desc: "Manage message history and resources"},
		menuItem{action: "pushStateMenu", title: menuTitle(web, "\uf201", "📊", "State"), desc: "View and edit emotion, flow, volition"},
		menuItem{resourceType: ResourceCronTasks, action: "pushTable", title: menuTitle(web, "\uf017", "⏰", "Cron Tasks"), desc: "Manage scheduled tasks and reminders"},
	}
}

func (m *ManageMenuModel) Init() tea.Cmd {
	return nil
}

func (m *ManageMenuModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.list.SetSize(msg.Width, msg.Height-2)
		return m, nil

	case tea.KeyMsg:
		if key.Matches(msg, m.keys.Quit) {
			return m, tea.Quit
		}
		if key.Matches(msg, m.keys.Back) {
			return m, func() tea.Msg { return PopMsg{} }
		}
		if key.Matches(msg, m.keys.Enter) {
			idx := m.list.Index()
			items := m.list.Items()
			if idx >= 0 && idx < len(items) {
				item := items[idx].(menuItem)
				switch item.action {
				case "pushMemoryMenu":
					return m, func() tea.Msg {
						return PushMemoryMenuMsg{
							Bot:   m.bot,
							Group: m.group,
						}
					}
				case "pushMessageMenu":
					return m, func() tea.Msg {
						return PushMessageMenuMsg{
							Bot:   m.bot,
							Group: m.group,
						}
					}
				case "pushStateMenu":
					return m, func() tea.Msg {
						return PushStateMenuMsg{
							Bot:   m.bot,
							Group: m.group,
						}
					}
				case "pushMemeMenu":
					return m, func() tea.Msg {
						return PushMemeMenuMsg{
							Bot:   m.bot,
							Group: m.group,
						}
					}
				default:
					return m, func() tea.Msg {
						return PushTableMsg{
							ResourceType: item.resourceType,
							Bot:          m.bot,
							Group:        m.group,
						}
					}
				}
			}
		}
	}

	var cmd tea.Cmd
	m.list, cmd = m.list.Update(msg)
	return m, cmd
}

func (m *ManageMenuModel) View() string {
	header := lipgloss.NewStyle().
		Foreground(style.Secondary).
		MarginBottom(1).
		Render(fmt.Sprintf("Bot: %s  |  Group: %s", m.bot.BotName, m.group.GroupName))
	return header + "\n" + m.list.View()
}
