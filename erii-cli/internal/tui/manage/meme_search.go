package manage

import (
	"erii-cli/internal/api"
	style "erii-cli/internal/ui/theme"
	"fmt"
	"strings"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

const defaultMemeSearchLimit = 10

var (
	memeSearchHeaderStyle = lipgloss.NewStyle().
				Foreground(style.Primary).
				Bold(true)
	memeSearchHintStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted)
	memeSearchIndexStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted).
				Bold(true)
	memeSearchDescriptionStyle = lipgloss.NewStyle().
					Foreground(style.Text)
	memeSearchMetaStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted)
	memeSearchScoreStyle = lipgloss.NewStyle().
				Foreground(style.Accent).
				Bold(true).
				Padding(0, 1)
	memeSearchTagStyle = lipgloss.NewStyle().
				Foreground(style.Secondary).
				Bold(true).
				Padding(0, 1)
)

type memeSearchLoadedMsg struct {
	response *api.MemeVectorSearchResponse
	err      error
}

type MemeSearchModel struct {
	api       *api.Client
	bot       api.BotInfo
	group     api.GroupInfo
	input     textinput.Model
	resultVP  viewport.Model
	keys      memorySearchKeys
	help      help.Model
	width     int
	height    int
	loading   bool
	err       error
	lastQuery string
	content   string
}

func NewMemeSearchModel(client *api.Client, bot api.BotInfo, group api.GroupInfo) *MemeSearchModel {
	ti := textinput.New()
	ti.Placeholder = "Type text to search memes..."
	ti.Focus()
	ti.Prompt = "> "
	ti.PromptStyle = lipgloss.NewStyle().Foreground(style.Accent)
	ti.TextStyle = lipgloss.NewStyle().Foreground(style.Text)
	ti.Cursor.Style = lipgloss.NewStyle().Foreground(style.Accent)

	vp := viewport.New(40, 10)
	return &MemeSearchModel{
		api:      client,
		bot:      bot,
		group:    group,
		input:    ti,
		resultVP: vp,
		keys:     defaultMemorySearchKeys,
		help:     help.New(),
		content:  style.Muted("Enter a query to search memes."),
		width:    80,
		height:   24,
	}
}

func (m *MemeSearchModel) Init() tea.Cmd {
	return textinput.Blink
}

func (m *MemeSearchModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.help.Width = msg.Width
		m.resize()
		return m, nil
	case memeSearchLoadedMsg:
		m.loading = false
		m.err = msg.err
		if msg.err != nil {
			m.content = style.ErrorText("Error: " + msg.err.Error())
		} else {
			m.content = renderMemeVectorText(msg.response, m.resultVP.Width)
		}
		m.resultVP.SetContent(m.content)
		m.resultVP.GotoTop()
		return m, nil
	case tea.KeyMsg:
		if key.Matches(msg, m.keys.Quit) {
			return m, tea.Quit
		}
		if key.Matches(msg, m.keys.Back) {
			return m, func() tea.Msg { return PopMsg{} }
		}
		if key.Matches(msg, m.keys.Up) || key.Matches(msg, m.keys.Down) ||
			key.Matches(msg, m.keys.PageUp) || key.Matches(msg, m.keys.PageDown) {
			var cmd tea.Cmd
			m.resultVP, cmd = m.resultVP.Update(msg)
			return m, cmd
		}
		if key.Matches(msg, m.keys.Refresh) {
			if strings.TrimSpace(m.lastQuery) == "" {
				return m, nil
			}
			m.input.SetValue(m.lastQuery)
			return m, m.searchCmd(m.lastQuery)
		}
		if key.Matches(msg, m.keys.Submit) {
			query := strings.TrimSpace(m.input.Value())
			if query == "" {
				return m, nil
			}
			m.lastQuery = query
			return m, m.searchCmd(query)
		}
	}

	var cmd tea.Cmd
	m.input, cmd = m.input.Update(msg)
	return m, cmd
}

func (m *MemeSearchModel) View() string {
	title := TitleBarStyle.Render("Memes / Vector Search")

	left := m.leftPane()
	right := m.rightPane()
	body := lipgloss.JoinHorizontal(lipgloss.Top, left, right)
	if m.width < 72 {
		body = lipgloss.JoinVertical(lipgloss.Left, left, right)
	}
	return strings.Join([]string{title, body, m.help.View(m.keys)}, "\n")
}

func (m *MemeSearchModel) searchCmd(query string) tea.Cmd {
	m.loading = true
	m.err = nil
	m.content = style.Muted("Searching...")
	m.resultVP.SetContent(m.content)

	client := m.api
	botID := m.bot.BotID
	groupID := m.group.GroupID
	req := api.MemeSearchRequest{Query: query, Limit: defaultMemeSearchLimit}

	return func() tea.Msg {
		if client == nil {
			return memeSearchLoadedMsg{err: fmt.Errorf("api client is unavailable")}
		}
		resp, err := client.SearchMemeVector(botID, groupID, req)
		return memeSearchLoadedMsg{response: resp, err: err}
	}
}

func (m *MemeSearchModel) resize() {
	leftW := 32
	if m.width < 72 {
		leftW = m.width - 4
	} else if m.width > 110 {
		leftW = 38
	}
	if leftW < 24 {
		leftW = 24
	}
	rightW := m.width - leftW - 6
	if m.width < 72 {
		rightW = m.width - 4
	}
	if rightW < 24 {
		rightW = 24
	}
	vpHeight := m.height - 5
	if m.width < 72 {
		vpHeight = m.height - 12
	}
	if vpHeight < 6 {
		vpHeight = 6
	}
	m.input.Width = leftW - 6
	m.resultVP.Width = rightW - 2
	m.resultVP.Height = vpHeight - 2
	m.resultVP.SetContent(m.content)
}

func (m *MemeSearchModel) leftPane() string {
	w := 32
	if m.width < 72 {
		w = m.width - 4
	} else if m.width > 110 {
		w = 38
	}
	if w < 24 {
		w = 24
	}
	lines := []string{
		style.Title("Query"),
		m.input.View(),
		"",
		style.Muted("Bot: " + m.bot.BotName),
		style.Muted("Group: " + m.group.GroupName),
	}
	if m.loading {
		lines = append(lines, "", style.Muted("Searching..."))
	}
	if m.err != nil {
		lines = append(lines, "", style.ErrorText("Last query failed"))
	}
	return lipgloss.NewStyle().
		Width(w).
		Height(m.panelHeight()).
		Border(lipgloss.RoundedBorder()).
		BorderForeground(style.BorderStrong).
		Padding(0, 1).
		Render(strings.Join(lines, "\n"))
}

func (m *MemeSearchModel) rightPane() string {
	w := m.width - 38
	if m.width < 72 {
		w = m.width - 4
	} else if m.width > 110 {
		w = m.width - 44
	}
	if w < 24 {
		w = 24
	}
	return lipgloss.NewStyle().
		Width(w).
		Height(m.panelHeight()).
		Border(lipgloss.RoundedBorder()).
		BorderForeground(style.BorderStrong).
		Padding(0, 1).
		Render(m.resultVP.View())
}

func (m *MemeSearchModel) panelHeight() int {
	h := m.height - 4
	if h < 8 {
		h = 8
	}
	if m.width < 72 {
		h = (m.height - 5) / 2
		if h < 6 {
			h = 6
		}
	}
	return h
}

func renderMemeVectorText(response *api.MemeVectorSearchResponse, width int) string {
	if response == nil || len(response.Results) == 0 {
		return "No vector results."
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n", memeSearchHeaderStyle.Render(fmt.Sprintf("Vector results for %q", response.Query)))
	fmt.Fprintf(&b, "%s\n\n", memeSearchHintStyle.Render("Vector search, sorted by score"))
	for i, item := range response.Results {
		score := "-"
		if item.Score != nil {
			score = fmt.Sprintf("%.4f", *item.Score)
		}
		scoreTag := memeSearchScoreStyle.Render("score " + score)
		index := memeSearchIndexStyle.Render(fmt.Sprintf("%02d", i+1))

		keyword := ""
		if item.Meme.Description != nil && *item.Meme.Description != "" {
			keyword = *item.Meme.Description
		} else if item.Meme.Purpose != nil && *item.Meme.Purpose != "" {
			keyword = *item.Meme.Purpose
		} else {
			keyword = fmt.Sprintf("Meme #%d", item.Meme.ID)
		}

		fmt.Fprintf(&b, "%s %s  %s\n", index, scoreTag, memeSearchDescriptionStyle.Render(TruncateEnd(keyword, safeTextWidth(width, 12))))
		if item.Meme.Description != nil && *item.Meme.Description != "" {
			fmt.Fprintf(&b, "   %s\n", memeSearchMetaStyle.Render(TruncateEnd("Desc: "+*item.Meme.Description, safeTextWidth(width, 8))))
		}
		if item.Meme.Purpose != nil && *item.Meme.Purpose != "" {
			fmt.Fprintf(&b, "   %s\n", memeSearchMetaStyle.Render(TruncateEnd("Purpose: "+*item.Meme.Purpose, safeTextWidth(width, 12))))
		}
		if item.Meme.Tags != nil && *item.Meme.Tags != "" {
			fmt.Fprintf(&b, "   %s  %s", memeSearchTagStyle.Render("Tags"), memeSearchMetaStyle.Render(TruncateEnd(*item.Meme.Tags, safeTextWidth(width, 10))))
		}
		fmt.Fprintf(&b, "\n   %s\n\n",
			memeSearchMetaStyle.Render(fmt.Sprintf("ID #%d | seen %d  used %d", item.Meme.ID, item.Meme.SeenCount, item.Meme.UsageCount)),
		)
	}
	return strings.TrimRight(b.String(), "\n")
}
