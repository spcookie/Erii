package manage

import (
	"erii-cli/internal/api"
	"fmt"
	"strconv"
	"strings"
	"time"

	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/table"
	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss"
	"github.com/mattn/go-runewidth"
)

type previewHelpKeyMap struct{}

func (previewHelpKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{
		key.NewBinding(key.WithKeys("esc", "enter"), key.WithHelp("esc/enter", "close")),
		key.NewBinding(key.WithKeys("up", "down", "k", "j"), key.WithHelp("↑/↓/k/j", "scroll")),
		key.NewBinding(key.WithKeys("pgup", "pgdown", "b", "f"), key.WithHelp("pgup/pgdown/b/f", "page")),
		key.NewBinding(key.WithKeys("u", "d"), key.WithHelp("u/d", "half page")),
	}
}

func (previewHelpKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{
			key.NewBinding(key.WithKeys("esc", "enter"), key.WithHelp("esc/enter", "close")),
			key.NewBinding(key.WithKeys("up", "down", "k", "j"), key.WithHelp("↑/↓/k/j", "scroll")),
			key.NewBinding(key.WithKeys("pgup", "pgdown", "b", "f"), key.WithHelp("pgup/pgdown/b/f", "page")),
			key.NewBinding(key.WithKeys("u", "d"), key.WithHelp("u/d", "half page")),
		},
	}
}

var previewHelpKeys = previewHelpKeyMap{}

// Messages

type dataLoadedMsg struct {
	Items     []any
	Total     int
	RequestID int
	Error     error
}

type deleteDoneMsg struct {
	Error error
}

type batchDeleteDoneMsg struct {
	Deleted int
	Error   error
}

// DataTableModel

type DataTableModel struct {
	resourceType ResourceType
	botID        string
	groupID      string
	api          *api.Client

	items         []any
	filteredItems []any
	selected      map[string]bool

	searchInput textinput.Model
	searching   bool
	searchQuery string

	loading bool
	err     error

	confirmForm  *huh.Form
	confirmValue bool
	confirmBatch bool
	deleteKey    string

	previewing  bool
	previewItem any
	previewVP   viewport.Model

	table     table.Model
	formatter tableFormatter
	width     int
	height    int
	keys      tableKeys
	help      help.Model

	pageSize              int
	currentPage           int
	totalCount            int
	sortCol               int
	sortAsc               bool
	requestID             int
	cursorBottomAfterLoad bool
}

func NewDataTableModel(api *api.Client, rt ResourceType, bot api.BotInfo, group api.GroupInfo) *DataTableModel {
	ti := textinput.New()
	ti.Placeholder = "Search..."
	ti.PromptStyle = lipgloss.NewStyle().Foreground(style.Accent)
	ti.TextStyle = lipgloss.NewStyle().Foreground(style.Text)
	ti.Cursor.Style = lipgloss.NewStyle().Foreground(style.Accent)

	t := table.New()
	t.SetStyles(DataTableStyles())

	km := table.DefaultKeyMap()
	km.PageDown = key.NewBinding(key.WithKeys("f", "pgdown"))
	t.KeyMap = km

	return &DataTableModel{
		resourceType: rt,
		botID:        bot.BotID,
		groupID:      group.GroupID,
		api:          api,
		selected:     make(map[string]bool),
		searchInput:  ti,
		table:        t,
		formatter:    getFormatter(rt),
		keys:         defaultTableKeys,
		help:         help.New(),
		loading:      true,
		width:        80,
		height:       24,
		pageSize:     20,
		currentPage:  0,
		sortCol:      -1,
		sortAsc:      true,
	}
}

func newConfirmForm(title, description string, value *bool, width int) *huh.Form {
	t := style.DestructiveHuhTheme()
	t.Focused.Base = t.Focused.Base.Align(lipgloss.Center)
	t.Focused.Title = t.Focused.Title.Align(lipgloss.Center)
	t.Focused.Description = t.Focused.Description.Align(lipgloss.Center)
	t.Blurred.Base = t.Blurred.Base.Align(lipgloss.Center)
	t.Blurred.Title = t.Blurred.Title.Align(lipgloss.Center)
	t.Blurred.Description = t.Blurred.Description.Align(lipgloss.Center)
	km := huh.NewDefaultKeyMap()
	km.Quit = key.NewBinding(key.WithKeys("esc"))
	return huh.NewForm(
		huh.NewGroup(
			huh.NewConfirm().
				Title(title).
				Description(description).
				Affirmative("Confirm").
				Negative("Cancel").
				Value(value),
		),
	).WithWidth(width).WithShowHelp(false).WithTheme(t).WithKeyMap(km)
}

func (m *DataTableModel) Init() tea.Cmd {
	return m.loadPage()
}

func (m *DataTableModel) loadPage() tea.Cmd {
	m.requestID++
	requestID := m.requestID
	options := api.ListOptions{
		Offset: m.currentPage * m.pageSize,
		Limit:  m.pageSize,
		Query:  m.searchQuery,
	}
	if m.sortCol >= 0 && m.sortCol < len(m.formatter.sortColumns) {
		options.SortBy = serverSortField(m.resourceType, m.sortCol)
		if m.sortAsc {
			options.Order = "asc"
		} else {
			options.Order = "desc"
		}
	}
	m.loading = true
	m.err = nil
	return func() tea.Msg {
		items, total, err := loadResourceData(m.resourceType, m.api, m.botID, m.groupID, options)
		return dataLoadedMsg{Items: items, Total: total, RequestID: requestID, Error: err}
	}
}

func serverSortField(resourceType ResourceType, sortCol int) string {
	fields := map[ResourceType][]string{
		ResourceFacts:        {"id", "valid", "keyword", "scopeType"},
		ResourceProfiles:     {"id", "userId"},
		ResourceMemes:        {"id", "description", "seenCount", "usageCount"},
		ResourceVocabularies: {"id", "word", "weight"},
		ResourceSummaries:    {"id", "timeRange", "participantCount", "messageCount"},
		ResourceHistory:      {"id", "nick", "messageType", "createdAt"},
		ResourceResource:     {"id", "fileName", "size"},
		ResourceCronTasks:    {"taskId", "taskType", "triggerTime", "status", "firedAt"},
	}
	resourceFields := fields[resourceType]
	if sortCol < 0 || sortCol >= len(resourceFields) {
		return ""
	}
	return resourceFields[sortCol]
}

func (m *DataTableModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	if m.confirmForm != nil {
		if keyMsg, ok := msg.(tea.KeyMsg); ok && keyMsg.Type == tea.KeyCtrlC {
			return m, tea.Quit
		}
		if wmsg, ok := msg.(tea.WindowSizeMsg); ok {
			m.width = wmsg.Width
			m.height = wmsg.Height
			m.confirmForm.WithWidth(m.width)
		}
		newModel, cmd := m.confirmForm.Update(msg)
		m.confirmForm = newModel.(*huh.Form)
		switch m.confirmForm.State {
		case huh.StateCompleted:
			m.confirmForm = nil
			confirmed := m.confirmValue
			isBatch := m.confirmBatch
			key := m.deleteKey
			m.confirmBatch = false
			m.deleteKey = ""
			if confirmed {
				if isBatch {
					return m, m.doBatchDelete()
				}
				return m, m.doDelete(key)
			}
			return m, nil
		case huh.StateAborted:
			m.confirmForm = nil
			m.confirmBatch = false
			m.deleteKey = ""
			return m, nil
		}
		return m, cmd
	}

	if m.previewing {
		switch msg := msg.(type) {
		case tea.KeyMsg:
			if msg.Type == tea.KeyCtrlC {
				return m, tea.Quit
			}
			if key.Matches(msg, m.keys.Back) || msg.Type == tea.KeyEnter {
				m.previewing = false
				m.previewItem = nil
				return m, nil
			}
			var cmd tea.Cmd
			m.previewVP, cmd = m.previewVP.Update(msg)
			return m, cmd
		case tea.WindowSizeMsg:
			m.width = msg.Width
			m.height = msg.Height
			m.previewVP.Width = msg.Width - 10
			if m.previewVP.Width < 20 {
				m.previewVP.Width = 20
			}
			m.previewVP.Height = msg.Height - 6
			if m.previewVP.Height < 5 {
				m.previewVP.Height = 5
			}
			return m, nil
		}
		return m, nil
	}

	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.help.Width = msg.Width
		m.updateTableSize()
		return m, nil

	case dataLoadedMsg:
		if msg.RequestID != m.requestID {
			return m, nil
		}
		m.loading = false
		if msg.Error != nil {
			m.err = msg.Error
			return m, nil
		}
		m.items = msg.Items
		m.filteredItems = msg.Items
		m.totalCount = msg.Total
		if len(msg.Items) == 0 && msg.Total > 0 && m.currentPage >= m.pageCount() {
			m.currentPage = m.pageCount() - 1
			return m, m.loadPage()
		}
		m.updateTableRows()
		if m.cursorBottomAfterLoad && len(m.table.Rows()) > 0 {
			m.table.SetCursor(len(m.table.Rows()) - 1)
		} else {
			m.table.SetCursor(0)
		}
		m.cursorBottomAfterLoad = false
		return m, nil

	case deleteDoneMsg:
		m.loading = false
		if msg.Error != nil {
			m.err = msg.Error
			return m, nil
		}
		return m, m.loadPage()

	case batchDeleteDoneMsg:
		m.loading = false
		if msg.Error != nil {
			m.err = msg.Error
		}
		m.selected = make(map[string]bool)
		return m, m.loadPage()

	case RefreshMsg:
		return m, m.loadPage()

	case tea.KeyMsg:
		if key.Matches(msg, m.keys.Quit) {
			return m, tea.Quit
		}

		if m.searching {
			switch msg.Type {
			case tea.KeyEsc:
				m.searching = false
				m.searchInput.Blur()
				m.searchInput.SetValue("")
				m.searchQuery = ""
				m.currentPage = 0
				m.updateTableSize()
				return m, m.loadPage()
			case tea.KeyEnter:
				m.searching = false
				m.searchInput.Blur()
				m.searchQuery = strings.TrimSpace(m.searchInput.Value())
				m.currentPage = 0
				m.updateTableSize()
				return m, m.loadPage()
			default:
				var cmd tea.Cmd
				m.searchInput, cmd = m.searchInput.Update(msg)
				return m, cmd
			}
		}

		if key.Matches(msg, m.keys.Help) {
			m.help.ShowAll = !m.help.ShowAll
			m.updateTableSize()
			return m, nil
		}
		if key.Matches(msg, m.keys.Back) {
			return m, func() tea.Msg { return PopMsg{} }
		}
		if key.Matches(msg, m.keys.Search) {
			m.searching = true
			m.searchInput.Focus()
			m.updateTableSize()
			return m, textinput.Blink
		}
		if key.Matches(msg, m.keys.Refresh) {
			return m, m.loadPage()
		}
		if key.Matches(msg, m.keys.New) && m.formatter.canCreate {
			return m, func() tea.Msg {
				return PushEditMsg{
					ResourceType: m.resourceType,
					Bot:          api.BotInfo{BotID: m.botID},
					Group:        api.GroupInfo{GroupID: m.groupID},
					Data:         nil,
					IsCreate:     true,
				}
			}
		}
		if key.Matches(msg, m.keys.Enter) {
			if len(m.filteredItems) == 0 {
				return m, nil
			}
			idx := m.cursorItemIndex()
			if idx < 0 || idx >= len(m.filteredItems) {
				return m, nil
			}
			item := m.filteredItems[idx]
			if m.formatter.canEdit {
				return m, func() tea.Msg {
					return PushEditMsg{
						ResourceType: m.resourceType,
						Bot:          api.BotInfo{BotID: m.botID},
						Group:        api.GroupInfo{GroupID: m.groupID},
						Data:         item,
						IsCreate:     false,
					}
				}
			}
			m.enterPreview(item)
			return m, nil
		}
		if key.Matches(msg, m.keys.Preview) {
			if len(m.filteredItems) == 0 {
				return m, nil
			}
			idx := m.cursorItemIndex()
			if idx < 0 || idx >= len(m.filteredItems) {
				return m, nil
			}
			m.enterPreview(m.filteredItems[idx])
			return m, nil
		}
		if key.Matches(msg, m.keys.Select) {
			if len(m.filteredItems) == 0 {
				return m, nil
			}
			idx := m.cursorItemIndex()
			if idx >= 0 && idx < len(m.filteredItems) {
				item := m.filteredItems[idx]
				k := m.formatter.getKey(item)
				if m.selected[k] {
					delete(m.selected, k)
				} else {
					m.selected[k] = true
				}
				m.updateTableRows()
				return m, nil
			}
		}
		if key.Matches(msg, m.keys.Delete) && m.formatter.canDelete {
			if len(m.filteredItems) == 0 {
				return m, nil
			}
			m.deleteKey = m.getKeyAtCursor()
			m.confirmBatch = false
			m.confirmValue = false
			m.confirmForm = newConfirmForm("Confirm Delete", "Delete this item?", &m.confirmValue, m.width)
			return m, m.confirmForm.Init()
		}
		if key.Matches(msg, m.keys.BatchDel) && m.formatter.canDelete {
			if len(m.selected) == 0 {
				return m, nil
			}
			m.confirmBatch = true
			m.confirmValue = false
			m.confirmForm = newConfirmForm("Confirm Batch Delete", fmt.Sprintf("Delete %d selected items?", len(m.selected)), &m.confirmValue, m.width)
			return m, m.confirmForm.Init()
		}

		if key.Matches(msg, m.keys.Up) {
			return m.moveUp()
		}
		if key.Matches(msg, m.keys.Down) {
			return m.moveDown()
		}
		if key.Matches(msg, m.keys.PageUp) {
			return m.prevPage()
		}
		if key.Matches(msg, m.keys.PageDown) {
			return m.nextPage()
		}
		if key.Matches(msg, m.keys.Sort1) {
			return m.applySortBy(0)
		}
		if key.Matches(msg, m.keys.Sort2) {
			return m.applySortBy(1)
		}
		if key.Matches(msg, m.keys.Sort3) {
			return m.applySortBy(2)
		}
		if key.Matches(msg, m.keys.Sort4) {
			return m.applySortBy(3)
		}
		if key.Matches(msg, m.keys.SortToggle) {
			if m.sortCol >= 0 {
				m.sortAsc = !m.sortAsc
				m.currentPage = 0
				return m, m.loadPage()
			}
			return m, nil
		}
	}

	var cmd tea.Cmd
	m.table, cmd = m.table.Update(msg)
	return m, cmd
}

func (m *DataTableModel) updateTableRows() {
	rows := make([]table.Row, len(m.filteredItems))
	for i, item := range m.filteredItems {
		k := m.formatter.getKey(item)
		rows[i] = m.formatter.getRow(item, m.selected[k])
	}
	m.table.SetRows(rows)
}

func (m *DataTableModel) updateTableSize() {
	widths := m.formatter.calcWidths(m.width)
	cols := m.formatter.columns(widths)
	m.table.SetColumns(cols)

	fixed := 2 // title + status (header is inside table height)
	if m.searching {
		fixed++
	}
	helpLines := strings.Count(m.help.View(m.keys), "\n") + 1
	fixed += helpLines
	fixed += 2 // lipgloss border around table (top + bottom)

	h := m.height - fixed
	if h < 3 {
		h = 3
	}
	m.table.SetHeight(h)
	m.table.SetWidth(m.width - 2)

}

func (m *DataTableModel) cursorItemIndex() int {
	if len(m.filteredItems) == 0 {
		return -1
	}
	idx := m.table.Cursor()
	if idx < 0 || idx >= len(m.filteredItems) {
		return -1
	}
	return idx
}

func (m *DataTableModel) getKeyAtCursor() string {
	idx := m.cursorItemIndex()
	if idx < 0 {
		return ""
	}
	return m.formatter.getKey(m.filteredItems[idx])
}

func (m *DataTableModel) pageCount() int {
	if m.totalCount == 0 {
		return 1
	}
	return (m.totalCount-1)/m.pageSize + 1
}

func (m *DataTableModel) prevPage() (tea.Model, tea.Cmd) {
	if m.currentPage > 0 {
		m.currentPage--
		return m, m.loadPage()
	}
	return m, nil
}

func (m *DataTableModel) nextPage() (tea.Model, tea.Cmd) {
	totalPages := m.pageCount()
	if m.currentPage < totalPages-1 {
		m.currentPage++
		return m, m.loadPage()
	}
	return m, nil
}

func (m *DataTableModel) moveUp() (tea.Model, tea.Cmd) {
	if len(m.table.Rows()) == 0 {
		return m, nil
	}
	if m.table.Cursor() > 0 {
		m.table.MoveUp(1)
		return m, nil
	}
	if m.currentPage > 0 {
		m.currentPage--
		m.cursorBottomAfterLoad = true
		return m, m.loadPage()
	}
	return m, nil
}

func (m *DataTableModel) moveDown() (tea.Model, tea.Cmd) {
	if len(m.table.Rows()) == 0 {
		return m, nil
	}
	if m.table.Cursor() < len(m.table.Rows())-1 {
		m.table.MoveDown(1)
		return m, nil
	}
	totalPages := m.pageCount()
	if m.currentPage < totalPages-1 {
		m.currentPage++
		return m, m.loadPage()
	}
	return m, nil
}

func (m *DataTableModel) applySortBy(colIdx int) (tea.Model, tea.Cmd) {
	if colIdx < 0 || colIdx >= len(m.formatter.sortColumns) {
		return m, nil
	}
	if m.sortCol == colIdx {
		m.sortAsc = !m.sortAsc
	} else {
		m.sortCol = colIdx
		m.sortAsc = true
	}
	m.currentPage = 0
	return m, m.loadPage()
}

func (m *DataTableModel) doDelete(key string) tea.Cmd {
	rt := m.resourceType
	client := m.api
	botID := m.botID
	groupID := m.groupID
	return func() tea.Msg {
		var err error
		switch rt {
		case ResourceFacts:
			id, _ := strconv.Atoi(key)
			err = client.DeleteFact(botID, groupID, id)
		case ResourceProfiles:
			err = client.DeleteUserProfile(botID, groupID, key)
		case ResourceMemes:
			id, _ := strconv.Atoi(key)
			err = client.DeleteMeme(botID, groupID, id)
		case ResourceVocabularies:
			id, _ := strconv.Atoi(key)
			err = client.DeleteVocabulary(botID, groupID, id)
		case ResourceSummaries:
			id, _ := strconv.Atoi(key)
			err = client.DeleteSummary(botID, groupID, id)
		case ResourceHistory:
			id, _ := strconv.Atoi(key)
			err = client.DeleteHistory(botID, groupID, id)
		case ResourceCronTasks:
			err = client.DeleteCronTask(botID, groupID, key)
		}
		return deleteDoneMsg{Error: err}
	}
}

func (m *DataTableModel) doBatchDelete() tea.Cmd {
	rt := m.resourceType
	client := m.api
	botID := m.botID
	groupID := m.groupID
	selected := make(map[string]bool)
	for k, v := range m.selected {
		selected[k] = v
	}
	return func() tea.Msg {
		var lastErr error
		deleted := 0
		for key := range selected {
			var err error
			switch rt {
			case ResourceFacts:
				id, _ := strconv.Atoi(key)
				err = client.DeleteFact(botID, groupID, id)
			case ResourceProfiles:
				err = client.DeleteUserProfile(botID, groupID, key)
			case ResourceMemes:
				id, _ := strconv.Atoi(key)
				err = client.DeleteMeme(botID, groupID, id)
			case ResourceVocabularies:
				id, _ := strconv.Atoi(key)
				err = client.DeleteVocabulary(botID, groupID, id)
			case ResourceSummaries:
				id, _ := strconv.Atoi(key)
				err = client.DeleteSummary(botID, groupID, id)
			case ResourceHistory:
				id, _ := strconv.Atoi(key)
				err = client.DeleteHistory(botID, groupID, id)
			case ResourceCronTasks:
				err = client.DeleteCronTask(botID, groupID, key)
			}
			if err != nil {
				lastErr = err
			} else {
				deleted++
			}
		}
		return batchDeleteDoneMsg{Deleted: deleted, Error: lastErr}
	}
}

// ── View ──

func (m *DataTableModel) View() string {
	if m.loading {
		return "\n  " + style.Muted("Loading...")
	}
	if m.err != nil {
		return "\n  " + style.ErrorText("Error: "+m.err.Error()) + "\n\n  " + style.Muted("Press r to retry, ESC to go back")
	}
	if m.confirmForm != nil {
		view := m.confirmForm.View()
		lines := strings.Split(view, "\n")
		padTop := (m.height - len(lines)) / 2
		if padTop < 0 {
			padTop = 0
		}
		return strings.Repeat("\n", padTop) + view
	}

	if m.previewing {
		borderStyle := lipgloss.NewStyle().
			BorderStyle(lipgloss.RoundedBorder()).
			BorderForeground(style.Accent).
			Padding(0, 1)
		panel := borderStyle.Render(m.previewVP.View())

		helpView := m.help.View(previewHelpKeys)
		helpLines := strings.Count(helpView, "\n") + 1

		panelLines := strings.Split(panel, "\n")
		totalHeight := len(panelLines) + helpLines + 1
		padTop := (m.height - totalHeight) / 2
		if padTop < 0 {
			padTop = 0
		}
		padLeft := (m.width - m.previewVP.Width - 6) / 2
		if padLeft < 0 {
			padLeft = 0
		}

		leftPad := strings.Repeat(" ", padLeft)
		var result []string
		for i := 0; i < padTop; i++ {
			result = append(result, "")
		}
		for _, line := range panelLines {
			result = append(result, leftPad+line)
		}
		result = append(result, "")
		for _, line := range strings.Split(helpView, "\n") {
			result = append(result, leftPad+line)
		}
		return strings.Join(result, "\n")
	}

	var parts []string
	parts = append(parts, m.renderTitleBar())
	if m.searching {
		parts = append(parts, SearchBarStyle.Width(m.width).Render(m.searchInput.View()))
	}

	tableView := lipgloss.NewStyle().
		BorderStyle(lipgloss.NormalBorder()).
		BorderForeground(style.BorderStrong).
		Render(m.table.View())
	parts = append(parts, tableView)

	parts = append(parts, m.renderStatusBar())
	parts = append(parts, m.help.View(m.keys))
	return strings.Join(parts, "\n")
}

func (m *DataTableModel) renderTitleBar() string {
	icon := m.formatter.title
	label := fmt.Sprintf("%s  \xe2\x80\x94  %d items", icon, m.totalCount)
	if m.searching {
		label += "  " + SearchActiveStyle.Render(fmt.Sprintf("[Search: %s]", m.searchInput.Value()))
	}
	label = TruncateEnd(label, m.width-2)
	return TitleBarStyle.Width(m.width).Render(label)
}

func (m *DataTableModel) renderStatusBar() string {
	selectedInfo := fmt.Sprintf("%d selected", len(m.selected))

	var parts []string
	cursorIdx := m.cursorItemIndex()
	if cursorIdx >= 0 {
		itemLabel := fmt.Sprintf("%d/%d", m.currentPage*m.pageSize+cursorIdx+1, m.totalCount)
		parts = append(parts, itemLabel+" item")
	} else {
		parts = append(parts, "0/0 item")
	}
	if m.totalCount > m.pageSize {
		parts = append(parts, fmt.Sprintf("Page %d/%d", m.currentPage+1, m.pageCount()))
	}
	parts = append(parts, selectedInfo)
	if m.sortCol >= 0 && m.sortCol < len(m.formatter.sortColumns) {
		dir := "↑"
		if !m.sortAsc {
			dir = "↓"
		}
		parts = append(parts, fmt.Sprintf("Sort: %s %s", m.formatter.sortColumns[m.sortCol].name, dir))
	}
	if m.searchQuery != "" && !m.searching {
		parts = append(parts, "Filter: "+m.searchQuery)
	}

	text := "  " + strings.Join(parts, "  \xe2\x94\x82  ")
	text = TruncateEnd(text, m.width-2)
	return StatusBarStyle.Width(m.width).Render(text)
}

func (m *DataTableModel) enterPreview(item any) {
	m.previewItem = item
	m.previewing = true
	m.previewVP.SetContent(m.buildPreviewContent())
	m.previewVP.Width = m.width - 10
	if m.previewVP.Width < 20 {
		m.previewVP.Width = 20
	}
	m.previewVP.Height = m.height - 6
	if m.previewVP.Height < 5 {
		m.previewVP.Height = 5
	}
	m.previewVP.GotoTop()
}

func (m *DataTableModel) buildPreviewContent() string {
	panelWidth := m.width - 8
	if panelWidth > 80 {
		panelWidth = 80
	}
	if panelWidth < 30 {
		panelWidth = 30
	}

	innerWidth := panelWidth - 4

	wrap := func(label, value string) string {
		lines := wrapText(value, innerWidth-len(label)-2)
		var result []string
		for i, line := range lines {
			if i == 0 {
				result = append(result, fmt.Sprintf("%s: %s", label, line))
			} else {
				result = append(result, strings.Repeat(" ", len(label)+2)+line)
			}
		}
		return strings.Join(result, "\n")
	}

	ptrStr := func(s *string) string {
		if s == nil {
			return ""
		}
		return *s
	}

	var title string
	var contentLines []string

	switch r := m.previewItem.(type) {
	case api.FactRecord:
		title = r.Keyword
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("Status", factStatusDisplay(r)),
			wrap("Keyword", r.Keyword),
			wrap("Description", r.Description),
			wrap("Entities", factEntitiesDisplay(r)),
			wrap("Subjects", r.Subjects),
			wrap("Scope", r.ScopeType),
			wrap("ValidFrom", r.ValidFrom),
			wrap("ValidTo", ptrStr(r.ValidTo)),
			wrap("Created", r.CreatedAt),
		}
	case api.UserProfileRecord:
		title = r.UserID
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("UserID", r.UserID),
			wrap("Profile", r.Profile),
			wrap("Preferences", r.Preferences),
			wrap("Created", r.CreatedAt),
		}
	case api.MemeRecord:
		title = fmt.Sprintf("Meme #%d", r.ID)
		resourceIDStr := fmt.Sprintf("%d", r.ResourceID)
		if r.ResourceID == 0 {
			resourceIDStr = "(null)"
		}
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("ResourceID", resourceIDStr),
			wrap("Md5", r.Md5),
			wrap("SeenCount", fmt.Sprintf("%d", r.SeenCount)),
			wrap("Description", ptrStr(r.Description)),
			wrap("Purpose", ptrStr(r.Purpose)),
			wrap("Tags", ptrStr(r.Tags)),
			wrap("UsageCount", fmt.Sprintf("%d", r.UsageCount)),
			wrap("Created", r.CreatedAt),
			wrap("Updated", r.UpdatedAt),
		}
	case api.VocabRecord:
		title = r.Word
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("Word", r.Word),
			wrap("Type", r.Type),
			wrap("Meaning", r.Meaning),
			wrap("Example", r.Example),
			wrap("Weight", fmt.Sprintf("%d", r.Weight)),
			wrap("LastSeen", r.LastSeen),
			wrap("Created", r.CreatedAt),
		}
	case api.SummaryRecord:
		title = r.TimeRange
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("TimeRange", r.TimeRange),
			wrap("Content", r.Content),
			wrap("KeyPoints", r.KeyPoints),
			wrap("EmotionalTone", ptrStr(r.EmotionalTone)),
			wrap("Participants", fmt.Sprintf("%d", r.ParticipantCount)),
			wrap("Messages", fmt.Sprintf("%d", r.MessageCount)),
			wrap("Created", r.CreatedAt),
		}
	case api.HistoryRecord:
		title = fmt.Sprintf("History #%d", r.ID)
		content := ""
		if r.Content != nil {
			content = *r.Content
		}
		resource := ""
		if r.Resource != nil {
			resource = fmt.Sprintf("%s (%s)", r.Resource.FileName, r.Resource.URL)
		}
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("UserID", r.UserID),
			wrap("Nick", r.Nick),
			wrap("Type", r.MessageType),
			wrap("Content", content),
			wrap("Resource", resource),
			wrap("Created", r.CreatedAt),
		}
	case api.ResourceRecord:
		title = r.FileName
		contentLines = []string{
			wrap("ID", fmt.Sprintf("%d", r.ID)),
			wrap("FileName", r.FileName),
			wrap("URL", r.URL),
			wrap("Size", fmt.Sprintf("%d", r.Size)),
			wrap("MD5", r.Md5),
			wrap("Created", r.CreatedAt),
		}
	case api.CronTaskRecord:
		title = fmt.Sprintf("Cron Task %s", TruncateEnd(r.TaskID, 12))
		typeLabel := r.TaskType
		if r.TaskType == "TASK_TRIGGER" && r.TriggerType != "" {
			typeLabel = fmt.Sprintf("%s (%s)", r.TaskType, r.TriggerType)
		}
		targetStr := r.TargetUserID
		if targetStr == "" {
			targetStr = "(all)"
		}
		triggerTime := formatUnixTime(r.TriggerTime)
		createdTime := formatUnixTime(r.CreatedAt)
		firedStr := ""
		if r.FiredAt != nil {
			firedStr = formatUnixTime(*r.FiredAt)
		}
		contentLines = []string{
			wrap("TaskID", r.TaskID),
			wrap("Type", typeLabel),
			wrap("Content", r.Content),
			wrap("Trigger", triggerTime),
			wrap("Cron", r.CronExpression),
			wrap("TargetUser", targetStr),
			wrap("Status", r.Status),
			wrap("Fired", firedStr),
			wrap("Created", createdTime),
		}
	default:
		return ""
	}

	content := strings.Join(contentLines, "\n")

	return style.Title(title) + "\n\n" + content + "\n"
}

func wrapText(text string, maxWidth int) []string {
	if maxWidth <= 0 {
		return []string{text}
	}
	wrapped := runewidth.Wrap(text, maxWidth)
	return strings.Split(wrapped, "\n")
}

// ── Formatter ──

type sortColumn struct {
	name string
	less func(a, b any) bool
}

type tableFormatter struct {
	title       string
	minWidths   []int
	columns     func(widths []int) []table.Column
	getRow      func(any, bool) table.Row
	getID       func(any) int
	getKey      func(any) string
	searchMatch func(any, string) bool
	canCreate   bool
	canEdit     bool
	canDelete   bool
	sortColumns []sortColumn
}

func (f tableFormatter) calcWidths(totalWidth int) []int {
	n := len(f.minWidths)
	totalCols := n + 1 // +checkbox

	avail := totalWidth - 2 - totalCols*2
	if avail < totalCols*3 {
		avail = totalCols * 3
	}

	minW := make([]int, totalCols)
	minW[0] = 3 // checkbox
	for i, w := range f.minWidths {
		minW[i+1] = w
	}

	widths := make([]int, totalCols)
	used := 0
	for i := range widths {
		widths[i] = minW[i]
		used += minW[i]
	}

	remaining := avail - used
	if remaining >= 0 {
		extra := remaining / totalCols
		remainder := remaining % totalCols
		for i := range widths {
			widths[i] += extra
		}
		for i := 0; i < remainder && i < totalCols; i++ {
			widths[i]++
		}
	} else {
		ratio := float64(avail) / float64(used)
		for i := range widths {
			widths[i] = int(float64(minW[i]) * ratio)
			if widths[i] < 3 {
				widths[i] = 3
			}
		}
	}
	return widths
}

func getFormatter(rt ResourceType) tableFormatter {
	switch rt {
	case ResourceFacts:
		return tableFormatter{
			title:     "Memory (Facts)",
			minWidths: []int{5, 8, 12, 15, 10, 10, 6},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "Status", Width: widths[2]},
					{Title: "Keyword", Width: widths[3]},
					{Title: "Description", Width: widths[4]},
					{Title: "Entities", Width: widths[5]},
					{Title: "Subjects", Width: widths[6]},
					{Title: "Scope", Width: widths[7]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.FactRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					factStatusDisplay(r),
					r.Keyword,
					r.Description,
					factEntitiesDisplay(r),
					r.Subjects,
					r.ScopeType,
				}
			},
			getID:  func(a any) int { return a.(api.FactRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.FactRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.FactRecord)
				return strings.Contains(strings.ToLower(r.Keyword), kw) ||
					strings.Contains(strings.ToLower(factStatusDisplay(r)), kw) ||
					strings.Contains(strings.ToLower(r.Description), kw) ||
					strings.Contains(strings.ToLower(factEntitiesDisplay(r)), kw) ||
					strings.Contains(strings.ToLower(r.Subjects), kw) ||
					strings.Contains(strings.ToLower(r.ScopeType), kw)
			},
			canCreate: true,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.FactRecord).ID < b.(api.FactRecord).ID }},
				{name: "Status", less: func(a, b any) bool {
					return !a.(api.FactRecord).Valid && b.(api.FactRecord).Valid
				}},
				{name: "Keyword", less: func(a, b any) bool {
					return strings.Compare(a.(api.FactRecord).Keyword, b.(api.FactRecord).Keyword) < 0
				}},
				{name: "Scope", less: func(a, b any) bool {
					return strings.Compare(a.(api.FactRecord).ScopeType, b.(api.FactRecord).ScopeType) < 0
				}},
			},
		}

	case ResourceProfiles:
		return tableFormatter{
			title:     "User Profiles",
			minWidths: []int{5, 12, 20, 20},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "UserID", Width: widths[2]},
					{Title: "Profile", Width: widths[3]},
					{Title: "Preferences", Width: widths[4]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.UserProfileRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					r.UserID,
					r.Profile,
					r.Preferences,
				}
			},
			getID:  func(a any) int { return a.(api.UserProfileRecord).ID },
			getKey: func(a any) string { return a.(api.UserProfileRecord).UserID },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.UserProfileRecord)
				return strings.Contains(strings.ToLower(r.UserID), kw) ||
					strings.Contains(strings.ToLower(r.Profile), kw) ||
					strings.Contains(strings.ToLower(r.Preferences), kw)
			},
			canCreate: false,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.UserProfileRecord).ID < b.(api.UserProfileRecord).ID }},
				{name: "UserID", less: func(a, b any) bool {
					return strings.Compare(a.(api.UserProfileRecord).UserID, b.(api.UserProfileRecord).UserID) < 0
				}},
			},
		}

	case ResourceMemes:
		return tableFormatter{
			title:     "Memes",
			minWidths: []int{5, 15, 6, 10, 10, 6, 6},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "Res", Width: widths[2]},
					{Title: "Description", Width: widths[3]},
					{Title: "Purpose", Width: widths[4]},
					{Title: "Tags", Width: widths[5]},
					{Title: "Seen", Width: widths[6]},
					{Title: "Usage", Width: widths[7]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.MemeRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				resID := fmt.Sprintf("%d", r.ResourceID)
				if r.ResourceID == 0 {
					resID = "(null)"
				}
				desc := ""
				if r.Description != nil {
					desc = *r.Description
				}
				purpose := ""
				if r.Purpose != nil {
					purpose = *r.Purpose
				}
				tags := ""
				if r.Tags != nil {
					tags = *r.Tags
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					resID,
					desc,
					purpose,
					tags,
					fmt.Sprintf("%d", r.SeenCount),
					fmt.Sprintf("%d", r.UsageCount),
				}
			},
			getID:  func(a any) int { return a.(api.MemeRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.MemeRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.MemeRecord)
				if r.Description != nil && strings.Contains(strings.ToLower(*r.Description), kw) {
					return true
				}
				if r.Purpose != nil && strings.Contains(strings.ToLower(*r.Purpose), kw) {
					return true
				}
				if r.Tags != nil && strings.Contains(strings.ToLower(*r.Tags), kw) {
					return true
				}
				return strings.Contains(strings.ToLower(fmt.Sprintf("%d", r.ID)), kw)
			},
			canCreate: false,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.MemeRecord).ID < b.(api.MemeRecord).ID }},
				{name: "Description", less: func(a, b any) bool {
					da, db := "", ""
					if a.(api.MemeRecord).Description != nil {
						da = *a.(api.MemeRecord).Description
					}
					if b.(api.MemeRecord).Description != nil {
						db = *b.(api.MemeRecord).Description
					}
					return strings.Compare(da, db) < 0
				}},
				{name: "Seen", less: func(a, b any) bool { return a.(api.MemeRecord).SeenCount < b.(api.MemeRecord).SeenCount }},
				{name: "Usage", less: func(a, b any) bool { return a.(api.MemeRecord).UsageCount < b.(api.MemeRecord).UsageCount }},
			},
		}

	case ResourceVocabularies:
		return tableFormatter{
			title:     "Vocabulary",
			minWidths: []int{5, 12, 8, 20, 6},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "Word", Width: widths[2]},
					{Title: "Type", Width: widths[3]},
					{Title: "Meaning", Width: widths[4]},
					{Title: "Weight", Width: widths[5]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.VocabRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					r.Word,
					r.Type,
					r.Meaning,
					fmt.Sprintf("%d", r.Weight),
				}
			},
			getID:  func(a any) int { return a.(api.VocabRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.VocabRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.VocabRecord)
				return strings.Contains(strings.ToLower(r.Word), kw) ||
					strings.Contains(strings.ToLower(r.Type), kw) ||
					strings.Contains(strings.ToLower(r.Meaning), kw) ||
					strings.Contains(strings.ToLower(r.Example), kw)
			},
			canCreate: true,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.VocabRecord).ID < b.(api.VocabRecord).ID }},
				{name: "Word", less: func(a, b any) bool { return strings.Compare(a.(api.VocabRecord).Word, b.(api.VocabRecord).Word) < 0 }},
				{name: "Weight", less: func(a, b any) bool { return a.(api.VocabRecord).Weight < b.(api.VocabRecord).Weight }},
			},
		}

	case ResourceSummaries:
		return tableFormatter{
			title:     "Summaries",
			minWidths: []int{5, 12, 15, 15, 8, 8, 8},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "TimeRange", Width: widths[2]},
					{Title: "Content", Width: widths[3]},
					{Title: "KeyPoints", Width: widths[4]},
					{Title: "Tone", Width: widths[5]},
					{Title: "Participants", Width: widths[6]},
					{Title: "Messages", Width: widths[7]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.SummaryRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				tone := ""
				if r.EmotionalTone != nil {
					tone = *r.EmotionalTone
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					r.TimeRange,
					r.Content,
					r.KeyPoints,
					tone,
					fmt.Sprintf("%d", r.ParticipantCount),
					fmt.Sprintf("%d", r.MessageCount),
				}
			},
			getID:  func(a any) int { return a.(api.SummaryRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.SummaryRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.SummaryRecord)
				if strings.Contains(strings.ToLower(r.TimeRange), kw) {
					return true
				}
				if strings.Contains(strings.ToLower(r.Content), kw) {
					return true
				}
				if strings.Contains(strings.ToLower(r.KeyPoints), kw) {
					return true
				}
				if r.EmotionalTone != nil && strings.Contains(strings.ToLower(*r.EmotionalTone), kw) {
					return true
				}
				return false
			},
			canCreate: false,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.SummaryRecord).ID < b.(api.SummaryRecord).ID }},
				{name: "TimeRange", less: func(a, b any) bool {
					return strings.Compare(a.(api.SummaryRecord).TimeRange, b.(api.SummaryRecord).TimeRange) < 0
				}},
				{name: "Participants", less: func(a, b any) bool {
					return a.(api.SummaryRecord).ParticipantCount < b.(api.SummaryRecord).ParticipantCount
				}},
				{name: "Messages", less: func(a, b any) bool { return a.(api.SummaryRecord).MessageCount < b.(api.SummaryRecord).MessageCount }},
			},
		}

	case ResourceHistory:
		return tableFormatter{
			title:     "History",
			minWidths: []int{8, 12, 10, 8, 15, 12, 16},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "UserID", Width: widths[2]},
					{Title: "Nick", Width: widths[3]},
					{Title: "Type", Width: widths[4]},
					{Title: "Content", Width: widths[5]},
					{Title: "Resource", Width: widths[6]},
					{Title: "Created", Width: widths[7]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.HistoryRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				content := ""
				if r.Content != nil {
					content = *r.Content
				}
				resource := ""
				if r.Resource != nil {
					resource = r.Resource.FileName
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					r.UserID,
					r.Nick,
					r.MessageType,
					TruncateEnd(content, 30),
					resource,
					r.CreatedAt,
				}
			},
			getID:  func(a any) int { return a.(api.HistoryRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.HistoryRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.HistoryRecord)
				if strings.Contains(strings.ToLower(r.UserID), kw) ||
					strings.Contains(strings.ToLower(r.Nick), kw) ||
					strings.Contains(strings.ToLower(r.MessageType), kw) {
					return true
				}
				if r.Content != nil && strings.Contains(strings.ToLower(*r.Content), kw) {
					return true
				}
				if r.Resource != nil && strings.Contains(strings.ToLower(r.Resource.FileName), kw) {
					return true
				}
				return false
			},
			canCreate: false,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.HistoryRecord).ID < b.(api.HistoryRecord).ID }},
				{name: "Nick", less: func(a, b any) bool {
					return strings.Compare(a.(api.HistoryRecord).Nick, b.(api.HistoryRecord).Nick) < 0
				}},
				{name: "Type", less: func(a, b any) bool {
					return strings.Compare(a.(api.HistoryRecord).MessageType, b.(api.HistoryRecord).MessageType) < 0
				}},
				{name: "Created", less: func(a, b any) bool {
					return strings.Compare(a.(api.HistoryRecord).CreatedAt, b.(api.HistoryRecord).CreatedAt) < 0
				}},
			},
		}

	case ResourceCronTasks:
		return tableFormatter{
			title:     "Cron Tasks",
			minWidths: []int{14, 10, 25, 20, 8, 8, 16},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "TaskID", Width: widths[1]},
					{Title: "Type", Width: widths[2]},
					{Title: "Content", Width: widths[3]},
					{Title: "Trigger", Width: widths[4]},
					{Title: "Cron", Width: widths[5]},
					{Title: "Status", Width: widths[6]},
					{Title: "Fired", Width: widths[7]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.CronTaskRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				triggerTime := formatUnixTime(r.TriggerTime)
				firedStr := ""
				if r.FiredAt != nil {
					firedStr = formatUnixTime(*r.FiredAt)
				}
				return table.Row{
					cb,
					TruncateEnd(r.TaskID, 12),
					cronTypeLabel(r),
					TruncateEnd(r.Content, 30),
					triggerTime,
					r.CronExpression,
					r.Status,
					firedStr,
				}
			},
			getID:  func(a any) int { return 0 },
			getKey: func(a any) string { return a.(api.CronTaskRecord).TaskID },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.CronTaskRecord)
				return strings.Contains(strings.ToLower(r.TaskID), kw) ||
					strings.Contains(strings.ToLower(r.Content), kw) ||
					strings.Contains(strings.ToLower(r.TargetUserID), kw) ||
					strings.Contains(strings.ToLower(r.TaskType), kw) ||
					strings.Contains(strings.ToLower(r.TriggerType), kw) ||
					strings.Contains(strings.ToLower(r.Status), kw)
			},
			canCreate: false,
			canEdit:   true,
			canDelete: true,
			sortColumns: []sortColumn{
				{name: "TaskID", less: func(a, b any) bool {
					return strings.Compare(a.(api.CronTaskRecord).TaskID, b.(api.CronTaskRecord).TaskID) < 0
				}},
				{name: "Type", less: func(a, b any) bool {
					return strings.Compare(a.(api.CronTaskRecord).TaskType, b.(api.CronTaskRecord).TaskType) < 0
				}},
				{name: "Trigger", less: func(a, b any) bool {
					return a.(api.CronTaskRecord).TriggerTime < b.(api.CronTaskRecord).TriggerTime
				}},
				{name: "Status", less: func(a, b any) bool {
					return strings.Compare(a.(api.CronTaskRecord).Status, b.(api.CronTaskRecord).Status) < 0
				}},
				{name: "Fired", less: func(a, b any) bool {
					fa := a.(api.CronTaskRecord).FiredAt
					fb := b.(api.CronTaskRecord).FiredAt
					if fa == nil && fb == nil {
						return false
					}
					if fa == nil {
						return true
					}
					if fb == nil {
						return false
					}
					return *fa < *fb
				}},
			},
		}

	case ResourceResource:
		return tableFormatter{
			title:     "Resources",
			minWidths: []int{5, 8, 20, 10, 12, 20},
			columns: func(widths []int) []table.Column {
				return []table.Column{
					{Title: "", Width: widths[0]},
					{Title: "ID", Width: widths[1]},
					{Title: "FileName", Width: widths[2]},
					{Title: "Size", Width: widths[3]},
					{Title: "MD5", Width: widths[4]},
					{Title: "URL", Width: widths[5]},
					{Title: "Created", Width: widths[6]},
				}
			},
			getRow: func(a any, selected bool) table.Row {
				r := a.(api.ResourceRecord)
				cb := "[ ]"
				if selected {
					cb = "[x]"
				}
				return table.Row{
					cb,
					fmt.Sprintf("%d", r.ID),
					r.FileName,
					fmt.Sprintf("%d", r.Size),
					TruncateEnd(r.Md5, 12),
					TruncateMiddle(r.URL, 30),
					r.CreatedAt,
				}
			},
			getID:  func(a any) int { return a.(api.ResourceRecord).ID },
			getKey: func(a any) string { return fmt.Sprintf("%d", a.(api.ResourceRecord).ID) },
			searchMatch: func(a any, kw string) bool {
				r := a.(api.ResourceRecord)
				return strings.Contains(strings.ToLower(r.FileName), kw) ||
					strings.Contains(strings.ToLower(r.Md5), kw) ||
					strings.Contains(strings.ToLower(r.URL), kw)
			},
			canCreate: false,
			canEdit:   false,
			canDelete: false,
			sortColumns: []sortColumn{
				{name: "ID", less: func(a, b any) bool { return a.(api.ResourceRecord).ID < b.(api.ResourceRecord).ID }},
				{name: "FileName", less: func(a, b any) bool {
					return strings.Compare(a.(api.ResourceRecord).FileName, b.(api.ResourceRecord).FileName) < 0
				}},
				{name: "Size", less: func(a, b any) bool { return a.(api.ResourceRecord).Size < b.(api.ResourceRecord).Size }},
			},
		}
	}
	return tableFormatter{}
}

// ── Data loading ──

func toAnySlice[T any](items []T) []any {
	out := make([]any, len(items))
	for i, item := range items {
		out[i] = item
	}
	return out
}

func loadResourceData(
	rt ResourceType,
	api *api.Client,
	botID string,
	groupID string,
	options api.ListOptions,
) ([]any, int, error) {
	switch rt {
	case ResourceFacts:
		resp, err := api.GetFacts(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceProfiles:
		resp, err := api.GetUserProfiles(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceMemes:
		resp, err := api.GetMemes(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceVocabularies:
		resp, err := api.GetVocabularies(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceSummaries:
		resp, err := api.GetSummaries(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceHistory:
		resp, err := api.GetHistory(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceResource:
		resp, err := api.GetResources(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	case ResourceCronTasks:
		resp, err := api.GetCronTasks(botID, groupID, options)
		if err != nil {
			return nil, 0, err
		}
		return toAnySlice(resp.Items), resp.Total, nil
	}
	return nil, 0, fmt.Errorf("unknown resource type: %d", rt)
}

func cronTypeLabel(r api.CronTaskRecord) string {
	if r.TaskType == "TASK_TRIGGER" && r.TriggerType != "" {
		return r.TriggerType
	}
	return r.TaskType
}

func factEntitiesDisplay(r api.FactRecord) string {
	if len(r.Entities) > 0 {
		return strings.Join(r.Entities, ", ")
	}
	return r.Values
}

func factStatusDisplay(r api.FactRecord) string {
	if r.Valid {
		return "Valid"
	}
	return "Invalid"
}

func formatUnixTime(ts int64) string {
	if ts == 0 {
		return ""
	}
	t := time.UnixMilli(ts)
	return t.Format("2006-01-02 15:04")
}
