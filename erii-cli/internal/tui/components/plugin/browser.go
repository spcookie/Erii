package plugin

import (
	"os"
	"path/filepath"
	"strings"

	"erii-cli/internal/config/tree"
	"erii-cli/internal/path"
	"erii-cli/internal/tui/components"
	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// Item PluginItem represents a plugin configuration item in the list.
type Item struct {
	name string
	path string
}

func (i Item) Title() string { return i.name }
func (i Item) Description() string {
	// Get overall description from metadata
	if desc, ok := tree.GlobalMetadata.PluginOverallDesc[i.name]; ok {
		return desc
	}
	return ""
}
func (i Item) FilterValue() string { return i.name }

// BrowserKeyMap defines keybindings for the plugin browser.
type BrowserKeyMap struct {
	Up    key.Binding
	Down  key.Binding
	Enter key.Binding
	Back  key.Binding
	Help  key.Binding
	Quit  key.Binding
}

func (k BrowserKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.Up, k.Down, k.Enter, k.Back, k.Help, k.Quit}
}

func (k BrowserKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.Up, k.Down, k.Enter},
		{k.Back, k.Help, k.Quit},
	}
}

var DefaultBrowserKeys = BrowserKeyMap{
	Up: key.NewBinding(
		key.WithKeys("up", "k"),
		key.WithHelp("↑/k", "up"),
	),
	Down: key.NewBinding(
		key.WithKeys("down", "j"),
		key.WithHelp("↓/j", "down"),
	),
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "open"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc", "left", "h"),
		key.WithHelp("esc/←/h", "back"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "toggle help"),
	),
	Quit: key.NewBinding(
		key.WithKeys("q", "ctrl+c"),
		key.WithHelp("q/ctrl+c", "quit"),
	),
}

// BrowserModel displays a list of plugin configurations.
type BrowserModel struct {
	List     list.Model
	width    int
	height   int
	Keys     BrowserKeyMap
	help     help.Model
	plugins  []Item
	onSelect func(pluginName string, pluginPath string)
	onBack   func()
	loaded   bool
}

func NewBrowserModel(onSelect func(pluginName string, pluginPath string), onBack func()) *BrowserModel {
	return &BrowserModel{
		Keys:     DefaultBrowserKeys,
		help:     help.New(),
		plugins:  []Item{},
		onSelect: onSelect,
		onBack:   onBack,
		width:    80,
		height:   24,
	}
}

func (m *BrowserModel) loadPlugins() {
	m.plugins = []Item{}

	// Load from the plugin configuration directory.
	dir := path.PluginConfigDir
	if stat, err := os.Stat(dir); err == nil && stat.IsDir() {
		entries, err := os.ReadDir(dir)
		if err == nil {
			for _, entry := range entries {
				if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".json") {
					name := strings.TrimSuffix(entry.Name(), ".json")
					m.plugins = append(m.plugins, Item{
						name: name,
						path: filepath.Join(dir, entry.Name()),
					})
				}
			}
		}
	}

	// Sort plugins by name
	for i := 0; i < len(m.plugins); i++ {
		for j := i + 1; j < len(m.plugins); j++ {
			if m.plugins[i].name > m.plugins[j].name {
				m.plugins[i], m.plugins[j] = m.plugins[j], m.plugins[i]
			}
		}
	}

	m.loaded = true
}

func (m *BrowserModel) refreshList() {
	if !m.loaded {
		m.loadPlugins()
	}

	items := make([]list.Item, 0, len(m.plugins))
	for _, p := range m.plugins {
		items = append(items, p)
	}

	delegate := style.StyleDelegate(list.NewDefaultDelegate())

	l := list.New(items, delegate, 0, 0)
	l.Title = style.Title("Plugin Configurations")
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	l.SetShowHelp(false)
	l.Styles.Title = style.ListTitle
	l.Styles.HelpStyle = lipgloss.NewStyle().Foreground(style.TextMuted)

	m.List = l
	m.updateSize()
}

func (m *BrowserModel) updateSize() {
	if m.width > 0 && m.height > 0 {
		m.List.SetSize(m.width, m.height-4)
		m.help.Width = m.width
	}
}

func (m *BrowserModel) Init() tea.Cmd {
	m.loadPlugins()
	m.refreshList()
	return nil
}

func (m *BrowserModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.updateSize()
		return m, nil

	case tea.KeyMsg:
		if key.Matches(msg, m.Keys.Quit) {
			return m, tea.Quit
		}
		if key.Matches(msg, m.Keys.Help) {
			m.help.ShowAll = !m.help.ShowAll
			return m, nil
		}
		if key.Matches(msg, m.Keys.Back) {
			if m.onBack != nil {
				m.onBack()
			}
			return m, func() tea.Msg { return components.PopScreenMsg{} }
		}
		if key.Matches(msg, m.Keys.Enter) {
			if item, ok := m.List.SelectedItem().(Item); ok {
				if m.onSelect != nil {
					m.onSelect(item.name, item.path)
				}
			}
			return m, nil
		}
	}

	var cmd tea.Cmd
	m.List, cmd = m.List.Update(msg)
	return m, cmd
}

func (m *BrowserModel) ShortHelp() []key.Binding {
	return m.Keys.ShortHelp()
}

func (m *BrowserModel) FullHelp() [][]key.Binding {
	return m.Keys.FullHelp()
}

func (m *BrowserModel) View() string {
	return m.List.View() + "\n" + m.help.View(m)
}
