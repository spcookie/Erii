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

type memeMenuItem struct {
	action string
	title  string
	desc   string
}

func (i memeMenuItem) Title() string       { return i.title }
func (i memeMenuItem) Description() string { return i.desc }
func (i memeMenuItem) FilterValue() string { return i.title }

type MemeMenuModel struct {
	bot    api.BotInfo
	group  api.GroupInfo
	list   list.Model
	keys   menuKeys
	width  int
	height int
}

func NewMemeMenuModel(bot api.BotInfo, group api.GroupInfo) *MemeMenuModel {
	delegate := style.StyleDelegate(list.NewDefaultDelegate())
	l := list.New([]list.Item{}, delegate, 0, 0)
	l.Title = style.Title("Memes")
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	l.SetShowHelp(false)
	l.Styles.Title = style.ListTitle
	l.Styles.HelpStyle = lipgloss.NewStyle().Foreground(style.TextMuted)
	l.SetItems(memeMenuItems(webMenuIconsEnabled()))

	return &MemeMenuModel{
		bot:   bot,
		group: group,
		list:  l,
		keys:  menuDefaultKeys,
	}
}

func memeMenuItems(web bool) []list.Item {
	return []list.Item{
		memeMenuItem{action: "list", title: menuTitle(web, "", "📋", "List"), desc: "Open the existing meme data table"},
		memeMenuItem{action: "search", title: menuTitle(web, "", "🧭", "Vector"), desc: "Embedding search over memes"},
	}
}

func (m *MemeMenuModel) Init() tea.Cmd {
	return nil
}

func (m *MemeMenuModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
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
			if idx < 0 || idx >= len(items) {
				return m, nil
			}
			item := items[idx].(memeMenuItem)
			if item.action == "list" {
				return m, func() tea.Msg {
					return PushTableMsg{
						ResourceType: ResourceMemes,
						Bot:          m.bot,
						Group:        m.group,
					}
				}
			}
			return m, func() tea.Msg {
				return PushMemeSearchMsg{
					Bot:   m.bot,
					Group: m.group,
				}
			}
		}
	}

	var cmd tea.Cmd
	m.list, cmd = m.list.Update(msg)
	return m, cmd
}

func (m *MemeMenuModel) View() string {
	header := lipgloss.NewStyle().
		Foreground(style.Secondary).
		MarginBottom(1).
		Render(fmt.Sprintf("Bot: %s  |  Group: %s", m.bot.BotName, m.group.GroupName))
	return header + "\n" + m.list.View()
}
