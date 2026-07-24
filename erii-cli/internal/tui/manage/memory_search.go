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

const defaultMemorySearchLimit = 10

var (
	memorySearchHeaderStyle = lipgloss.NewStyle().
				Foreground(style.Primary).
				Bold(true)
	memorySearchHintStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted)
	memorySearchIndexStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted).
				Bold(true)
	memorySearchKeywordStyle = lipgloss.NewStyle().
					Foreground(style.Text).
					Bold(true)
	memorySearchDescriptionStyle = lipgloss.NewStyle().
					Foreground(style.Text)
	memorySearchMetaStyle = lipgloss.NewStyle().
				Foreground(style.TextMuted)
	memorySearchScoreStyle = lipgloss.NewStyle().
				Foreground(style.Accent).
				Bold(true).
				Padding(0, 1)
	memorySearchFactIDStyle = lipgloss.NewStyle().
				Foreground(style.Secondary).
				Bold(true).
				Padding(0, 1)
	memorySearchBM25Style = lipgloss.NewStyle().
				Foreground(style.Warning).
				Bold(true).
				Padding(0, 1)
	memorySearchVectorStyle = lipgloss.NewStyle().
				Foreground(style.Info).
				Bold(true).
				Padding(0, 1)
	memorySearchSourceFallbackStyle = lipgloss.NewStyle().
					Foreground(style.TextMuted).
					Bold(true).
					Padding(0, 1)
	memorySearchSeedStyle = lipgloss.NewStyle().
				Foreground(style.Primary).
				Bold(true).
				Padding(0, 1)
	memorySearchExpandedStyle = lipgloss.NewStyle().
					Foreground(style.Secondary).
					Bold(true).
					Padding(0, 1)
	memorySearchRelatedStyle = lipgloss.NewStyle().
					Foreground(style.Warning).
					Bold(true).
					Padding(0, 1)
	memorySearchFactTagStyle = lipgloss.NewStyle().
					Foreground(style.Secondary).
					Bold(true).
					Padding(0, 1)
	memorySearchEntityTagStyle = lipgloss.NewStyle().
					Foreground(style.Info).
					Bold(true).
					Padding(0, 1)
	memorySearchEdgeTagStyle = lipgloss.NewStyle().
					Foreground(style.Warning).
					Bold(true).
					Padding(0, 1)
)

type memorySearchLoadedMsg struct {
	vector *api.MemoryVectorSearchResponse
	graph  *api.MemoryGraphSearchResponse
	err    error
}

type memorySearchKeys struct {
	Submit   key.Binding
	Refresh  key.Binding
	Back     key.Binding
	Quit     key.Binding
	Up       key.Binding
	Down     key.Binding
	PageUp   key.Binding
	PageDown key.Binding
}

func (k memorySearchKeys) ShortHelp() []key.Binding {
	return []key.Binding{k.Submit, k.Refresh, k.Up, k.Down, k.Back, k.Quit}
}

func (k memorySearchKeys) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.Submit, k.Refresh, k.Up, k.Down},
		{k.PageUp, k.PageDown, k.Back, k.Quit},
	}
}

var defaultMemorySearchKeys = memorySearchKeys{
	Submit:   key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "search")),
	Refresh:  key.NewBinding(key.WithKeys("r"), key.WithHelp("r", "retry")),
	Back:     key.NewBinding(key.WithKeys("esc"), key.WithHelp("esc", "back")),
	Quit:     key.NewBinding(key.WithKeys("ctrl+c"), key.WithHelp("ctrl+c", "quit")),
	Up:       key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "scroll up")),
	Down:     key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "scroll down")),
	PageUp:   key.NewBinding(key.WithKeys("pgup", "b"), key.WithHelp("pgup/b", "page up")),
	PageDown: key.NewBinding(key.WithKeys("pgdown", "f"), key.WithHelp("pgdn/f", "page down")),
}

type MemorySearchModel struct {
	api       *api.Client
	mode      MemorySearchMode
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

func NewMemorySearchModel(client *api.Client, mode MemorySearchMode, bot api.BotInfo, group api.GroupInfo) *MemorySearchModel {
	ti := textinput.New()
	ti.Placeholder = "Type text to search memory..."
	ti.Focus()
	ti.Prompt = "> "
	ti.PromptStyle = lipgloss.NewStyle().Foreground(style.Accent)
	ti.TextStyle = lipgloss.NewStyle().Foreground(style.Text)
	ti.Cursor.Style = lipgloss.NewStyle().Foreground(style.Accent)

	vp := viewport.New(40, 10)
	return &MemorySearchModel{
		api:      client,
		mode:     mode,
		bot:      bot,
		group:    group,
		input:    ti,
		resultVP: vp,
		keys:     defaultMemorySearchKeys,
		help:     help.New(),
		content:  style.Muted("Enter a query to search memory."),
		width:    80,
		height:   24,
	}
}

func (m *MemorySearchModel) Init() tea.Cmd {
	return textinput.Blink
}

func (m *MemorySearchModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.help.Width = msg.Width
		m.resize()
		return m, nil
	case memorySearchLoadedMsg:
		m.loading = false
		m.err = msg.err
		if msg.err != nil {
			m.content = style.ErrorText("Error: " + msg.err.Error())
		} else if m.mode == MemorySearchVector {
			m.content = renderVectorText(msg.vector, m.resultVP.Width)
		} else {
			m.content = renderGraphSearchText(msg.graph, m.resultVP.Width)
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

func (m *MemorySearchModel) View() string {
	title := lipgloss.NewStyle().Foreground(style.Accent).Bold(true).Padding(0, 1).Render(fmt.Sprintf("Memory / %s", m.mode.Title()))

	left := m.leftPane()
	right := m.rightPane()
	body := lipgloss.JoinHorizontal(lipgloss.Top, left, right)
	if m.width < 72 {
		body = lipgloss.JoinVertical(lipgloss.Left, left, right)
	}
	return strings.Join([]string{title, body, m.help.View(m.keys)}, "\n")
}

func (m *MemorySearchModel) searchCmd(query string) tea.Cmd {
	m.loading = true
	m.err = nil
	m.content = style.Muted("Searching...")
	m.resultVP.SetContent(m.content)

	client := m.api
	mode := m.mode
	botID := m.bot.BotID
	groupID := m.group.GroupID
	req := api.MemorySearchRequest{Query: query, Limit: defaultMemorySearchLimit}

	return func() tea.Msg {
		if client == nil {
			return memorySearchLoadedMsg{err: fmt.Errorf("api client is unavailable")}
		}
		if mode == MemorySearchVector {
			resp, err := client.SearchMemoryVector(botID, groupID, req)
			return memorySearchLoadedMsg{vector: resp, err: err}
		}
		resp, err := client.SearchMemoryGraph(botID, groupID, req)
		return memorySearchLoadedMsg{graph: resp, err: err}
	}
}

func (m *MemorySearchModel) resize() {
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

func (m *MemorySearchModel) leftPane() string {
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

func (m *MemorySearchModel) rightPane() string {
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

func (m *MemorySearchModel) panelHeight() int {
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

func renderVectorText(response *api.MemoryVectorSearchResponse, width int) string {
	if response == nil || len(response.Results) == 0 {
		return "No vector results."
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n", memorySearchHeaderStyle.Render(fmt.Sprintf("Vector results for %q", response.Query)))
	fmt.Fprintf(&b, "%s\n\n", memorySearchHintStyle.Render("Hybrid rank: BM25 + vector, sorted by score"))
	for i, item := range response.Results {
		score := "-"
		if item.Score != nil {
			score = fmt.Sprintf("%.4f", *item.Score)
		}
		source := renderMemorySearchSourceTag(item.Source)
		scoreTag := memorySearchScoreStyle.Render("score " + score)
		factIDTag := memorySearchFactIDStyle.Render(fmt.Sprintf("#%d", item.Fact.ID))
		index := memorySearchIndexStyle.Render(fmt.Sprintf("%02d", i+1))
		keyword := memorySearchKeywordStyle.Render(item.Fact.Keyword)

		fmt.Fprintf(&b, "%s %s %s %s  %s\n", index, source, scoreTag, factIDTag, keyword)
		fmt.Fprintf(&b, "   %s\n", memorySearchDescriptionStyle.Render(TruncateEnd(item.Fact.Description, safeTextWidth(width, 6))))
		entities := factEntitiesDisplay(item.Fact)
		if entities == "" {
			entities = "-"
		}
		meta := fmt.Sprintf("scope %s  entities %s", item.Fact.ScopeType, TruncateEnd(entities, safeTextWidth(width, 24)))
		fmt.Fprintf(&b, "   %s\n\n", memorySearchMetaStyle.Render(meta))
	}
	return strings.TrimRight(b.String(), "\n")
}

func renderMemorySearchSourceTag(source string) string {
	switch strings.ToLower(strings.TrimSpace(source)) {
	case "bm25":
		return memorySearchBM25Style.Render("BM25")
	case "vector":
		return memorySearchVectorStyle.Render("VECTOR")
	case "":
		return memorySearchSourceFallbackStyle.Render("SOURCE")
	default:
		return memorySearchSourceFallbackStyle.Render(strings.ToUpper(source))
	}
}

func renderGraphSearchText(response *api.MemoryGraphSearchResponse, width int) string {
	if response == nil || (len(response.SeedResults) == 0 && len(response.ExpandedResults) == 0) {
		return "No graph results."
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n", memorySearchHeaderStyle.Render(fmt.Sprintf("Graph results for %q", response.Query)))
	fmt.Fprintf(&b, "%s\n\n", memorySearchHintStyle.Render("One-hop graph: seed facts -> entities -> related facts"))
	fmt.Fprintf(&b, "%s\n", memorySearchSectionTitle("Seed facts", len(response.SeedResults)))
	writeFactResults(&b, response.SeedResults, width, memorySearchSeedStyle.Render("SEED"))
	fmt.Fprintf(&b, "\n%s\n", memorySearchSectionTitle("Expanded facts", len(response.ExpandedResults)))
	writeFactResults(&b, response.ExpandedResults, width, memorySearchExpandedStyle.Render("EXPANDED"))
	b.WriteString("\n")
	b.WriteString(renderGraphPaths(response, width))
	b.WriteString("\n\n")
	b.WriteString(renderGraphText(response, width))
	return strings.TrimRight(b.String(), "\n")
}

func writeFactResults(b *strings.Builder, results []api.MemoryFactSearchResult, width int, sourceTag string) {
	if len(results) == 0 {
		fmt.Fprintf(b, "  %s\n", memorySearchMetaStyle.Render("none"))
		return
	}
	for i, item := range results {
		scoreTag := ""
		if item.Score != nil {
			scoreTag = " " + memorySearchScoreStyle.Render(fmt.Sprintf("score %.4f", *item.Score))
		}
		index := memorySearchIndexStyle.Render(fmt.Sprintf("%02d", i+1))
		factIDTag := memorySearchFactIDStyle.Render(fmt.Sprintf("#%d", item.Fact.ID))
		keyword := memorySearchKeywordStyle.Render(item.Fact.Keyword)
		fmt.Fprintf(b, "%s %s%s %s  %s\n", index, sourceTag, scoreTag, factIDTag, keyword)
		fmt.Fprintf(b, "   %s\n", memorySearchDescriptionStyle.Render(TruncateEnd(item.Fact.Description, safeTextWidth(width, 6))))
	}
}

func renderGraphText(response *api.MemoryGraphSearchResponse, width int) string {
	if response == nil {
		return memorySearchSectionTitle("Node index", 0) + "\n  " + memorySearchMetaStyle.Render("none")
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n", memorySearchSectionTitle("Node index", len(response.Nodes)))
	b.WriteString(memorySearchMetaStyle.Render("Nodes") + "\n")
	if len(response.Nodes) == 0 {
		fmt.Fprintf(&b, "  %s\n", memorySearchMetaStyle.Render("none"))
	} else {
		for _, node := range response.Nodes {
			tag := memorySearchEntityTagStyle.Render("ENTITY")
			if strings.EqualFold(node.Type, "fact") {
				tag = memorySearchFactTagStyle.Render("FACT")
			}
			source := ""
			if node.Source != "" {
				source = " " + renderGraphSourceTag(node.Source)
			}
			fmt.Fprintf(&b, "  %s%s %s  %s\n", tag, source, memorySearchMetaStyle.Render(node.ID), TruncateEnd(node.Label, safeTextWidth(width, 16+len(node.ID))))
		}
	}
	b.WriteString("\n" + memorySearchMetaStyle.Render("Edges") + "\n")
	if len(response.Edges) == 0 {
		fmt.Fprintf(&b, "  %s", memorySearchMetaStyle.Render("none"))
	} else {
		for _, edge := range response.Edges {
			line := fmt.Sprintf("%s -> %s", edge.From, edge.To)
			fmt.Fprintf(&b, "  %s %s  %s\n", memorySearchEdgeTagStyle.Render("EDGE"), TruncateEnd(line, safeTextWidth(width, 18)), memorySearchMetaStyle.Render(edge.Label))
		}
	}
	return strings.TrimRight(b.String(), "\n")
}

func renderGraphPaths(response *api.MemoryGraphSearchResponse, width int) string {
	if response == nil || len(response.SeedResults) == 0 {
		return memorySearchSectionTitle("Paths", 0) + "\n  " + memorySearchMetaStyle.Render("none")
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n", memorySearchSectionTitle("Paths", len(response.SeedResults)))

	expandedByEntity := map[string][]api.MemoryFactSearchResult{}
	for _, result := range response.ExpandedResults {
		for _, entity := range result.Fact.Entities {
			expandedByEntity[entity] = append(expandedByEntity[entity], result)
		}
	}

	for seedIndex, seed := range response.SeedResults {
		if seedIndex > 0 {
			b.WriteString("\n")
		}
		seedLine := fmt.Sprintf("#%d %s", seed.Fact.ID, seed.Fact.Keyword)
		fmt.Fprintf(&b, "%s %s\n", memorySearchSeedStyle.Render("SEED"), TruncateEnd(seedLine, safeTextWidth(width, 8)))
		if len(seed.Fact.Entities) == 0 {
			fmt.Fprintf(&b, "  %s %s\n", "└─", memorySearchMetaStyle.Render("no entities"))
			continue
		}
		for entityIndex, entity := range seed.Fact.Entities {
			entityBranch := "├─"
			childPrefix := "│ "
			if entityIndex == len(seed.Fact.Entities)-1 {
				entityBranch = "└─"
				childPrefix = "  "
			}
			fmt.Fprintf(&b, "  %s %s %s\n", entityBranch, memorySearchEntityTagStyle.Render("ENTITY"), TruncateEnd(entity, safeTextWidth(width, 18)))
			related := expandedByEntity[entity]
			if len(related) == 0 {
				fmt.Fprintf(&b, "  %s  └─ %s\n", childPrefix, memorySearchMetaStyle.Render("no related facts"))
				continue
			}
			for relatedIndex, result := range related {
				relatedBranch := "├─"
				if relatedIndex == len(related)-1 {
					relatedBranch = "└─"
				}
				relatedLine := fmt.Sprintf("#%d %s", result.Fact.ID, result.Fact.Keyword)
				fmt.Fprintf(
					&b,
					"  %s  %s %s %s\n",
					childPrefix,
					relatedBranch,
					memorySearchRelatedStyle.Render("RELATED"),
					TruncateEnd(relatedLine, safeTextWidth(width, 28)),
				)
			}
		}
	}
	return strings.TrimRight(b.String(), "\n")
}

func memorySearchSectionTitle(title string, count int) string {
	return memorySearchHeaderStyle.Render(fmt.Sprintf("%s (%d)", title, count))
}

func renderGraphSourceTag(source string) string {
	switch strings.ToLower(strings.TrimSpace(source)) {
	case "seed":
		return memorySearchSeedStyle.Render("SEED")
	case "expanded":
		return memorySearchExpandedStyle.Render("EXPANDED")
	case "":
		return ""
	default:
		return memorySearchSourceFallbackStyle.Render(strings.ToUpper(source))
	}
}

func safeTextWidth(width, padding int) int {
	w := width - padding
	if w < 16 {
		return 16
	}
	return w
}
