package usage

import (
	"erii-cli/internal/api"
	"fmt"
	"strconv"
	"strings"
	"time"

	"erii-cli/internal/tui/components"
	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// ── Navigation messages ──

type (
	PopMsg           struct{}
	PushGroupListMsg struct{ Bot api.BotInfo }
	PushUsageViewMsg struct {
		BotID     string
		BotName   string
		GroupID   string
		GroupName string
	}
)

// ── BotList / GroupList wrappers ──

func NewBotListModel(client *api.Client) *components.BotListModel {
	return components.NewBotListModel(client, components.BotListConfig{
		ShowAll: true,
		EnterAction: func(idx int, bots []api.BotInfo) tea.Cmd {
			if idx == 0 {
				return func() tea.Msg {
					return PushUsageViewMsg{BotID: "", BotName: "All Bots", GroupID: "", GroupName: "All Groups"}
				}
			}
			bot := bots[idx-1]
			return func() tea.Msg { return PushGroupListMsg{Bot: bot} }
		},
	})
}

func NewGroupListModel(client *api.Client, bot api.BotInfo) *components.GroupListModel {
	return components.NewGroupListModel(client, bot, components.GroupListConfig{
		ShowAll: true,
		EnterAction: func(idx int, bot api.BotInfo, groups []api.GroupInfo) tea.Cmd {
			if idx == 0 {
				return func() tea.Msg {
					return PushUsageViewMsg{BotID: bot.BotID, BotName: bot.BotName, GroupID: "", GroupName: "All Groups"}
				}
			}
			g := groups[idx-1]
			return func() tea.Msg {
				return PushUsageViewMsg{BotID: bot.BotID, BotName: bot.BotName, GroupID: g.GroupID, GroupName: g.GroupName}
			}
		},
		BackAction:    func() tea.Cmd { return func() tea.Msg { return PopMsg{} } },
		ErrorCardHint: "press r to retry    esc back    q quit",
	})
}

// ── Symbols ──

const SymArrow = ">"

// ── UsageViewModel ──

type usageKeyMap struct {
	components.NavKeys
	OlderDate key.Binding
	NewerDate key.Binding
}

func newUsageKeyMap() usageKeyMap {
	return usageKeyMap{
		NavKeys: components.DefaultNavKeys,
		OlderDate: key.NewBinding(
			key.WithKeys("tab"),
			key.WithHelp("tab", "older day"),
		),
		NewerDate: key.NewBinding(
			key.WithKeys("shift+tab"),
			key.WithHelp("shift+tab", "newer day"),
		),
	}
}

func (k usageKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.Up, k.Down, k.OlderDate, k.NewerDate, k.Back, k.Help, k.Quit}
}

func (k usageKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.Up, k.Down},
		{k.OlderDate, k.NewerDate},
		{k.Back, k.Help, k.Quit},
	}
}

type UsageViewModel struct {
	api         *api.Client
	botID       string
	botName     string
	groupID     string
	groupName   string
	data        *api.TokenUsageSummary
	keys        usageKeyMap
	selectedDay int
	help        help.Model
	viewport    viewport.Model
	width       int
	height      int
	loading     bool
	err         error

	// chart strings (built on data load / resize)
	sceneChart string
	modelChart string
	lineChart  string
	heatmap    string
}

func NewUsageViewModel(api *api.Client, botID, botName, groupID, groupName string) *UsageViewModel {
	return &UsageViewModel{
		api:       api,
		botID:     botID,
		botName:   botName,
		groupID:   groupID,
		groupName: groupName,
		keys:      newUsageKeyMap(),
		help:      help.New(),
		viewport:  viewport.New(0, 0),
		loading:   true,
	}
}

func (m *UsageViewModel) Init() tea.Cmd {
	return func() tea.Msg {
		data, err := m.api.GetUsage(m.botID, m.groupID)
		if err != nil {
			return err
		}
		return data
	}
}

func (m *UsageViewModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.help.Width = msg.Width
		m.viewport.Width = msg.Width
		m.viewport.Height = msg.Height - 3
		if !m.loading && m.err == nil && m.data != nil {
			m.buildCharts()
			m.viewport.SetContent(m.buildContent())
		}
		return m, nil

	case tea.KeyMsg:
		if key.Matches(msg, m.keys.Quit) {
			return m, tea.Quit
		}
		if key.Matches(msg, m.keys.Back) {
			return m, func() tea.Msg { return PopMsg{} }
		}
		if key.Matches(msg, m.keys.Help) {
			m.help.ShowAll = !m.help.ShowAll
			return m, nil
		}
		if key.Matches(msg, m.keys.OlderDate) {
			m.selectDay(m.selectedDay + 1)
			return m, nil
		}
		if key.Matches(msg, m.keys.NewerDate) {
			m.selectDay(m.selectedDay - 1)
			return m, nil
		}
		if key.Matches(msg, m.keys.Up) || key.Matches(msg, m.keys.Down) {
			var cmd tea.Cmd
			m.viewport, cmd = m.viewport.Update(msg)
			return m, cmd
		}
		return m, nil

	case *api.TokenUsageSummary:
		m.data = msg
		m.loading = false
		m.prepareDailyViews()
		m.buildCharts()
		m.viewport.SetContent(m.buildContent())
		return m, nil

	case error:
		m.err = msg
		m.loading = false
		return m, nil
	}

	var cmd tea.Cmd
	m.viewport, cmd = m.viewport.Update(msg)
	return m, cmd
}

func (m *UsageViewModel) View() string {
	if m.loading {
		return "\n  " + style.Muted("Loading token usage data...")
	}
	if m.err != nil {
		return "\n  " + style.ErrorText("Error: "+m.err.Error())
	}
	if m.data == nil {
		return "\n  " + style.Muted("No data available")
	}
	return m.viewport.View() + "\n" + m.help.View(m.keys)
}

// ── Chart colors & styles ──

var (
	barHitColor  = style.ChartBlue
	barMissColor = style.ChartCyan
	barOutColor  = style.TextMuted
	lineColor    = style.ChartBlue
)

var heatBlueScale = style.HeatScale

var (
	barHitStyle  = lipgloss.NewStyle().Foreground(barHitColor).Background(barHitColor)
	barMissStyle = lipgloss.NewStyle().Foreground(barMissColor).Background(barMissColor)
	barOutStyle  = lipgloss.NewStyle().Foreground(barOutColor).Background(barOutColor)
	lineStyle    = lipgloss.NewStyle().Foreground(lineColor)
	axisStyle    = lipgloss.NewStyle().Foreground(style.TextMuted)
	labelStyle   = lipgloss.NewStyle().Foreground(style.TextMuted)
)

func (m *UsageViewModel) buildCharts() {
	if m.data == nil {
		return
	}
	if len(m.data.DailyViews) == 0 {
		m.prepareDailyViews()
	}
	day := m.selectedDailyView()
	if day == nil {
		return
	}
	fullW := m.chartWidth()
	panelW := fullW - 2
	halfW := (panelW - 4) / 2
	m.sceneChart = m.buildBarChart("Scene Tokens", translateSceneNames(sortSceneRows(day.SceneBars)), halfW)
	m.modelChart = m.buildColumnChart("Model Tokens", day.ModelBars, halfW)
	var heatH int
	m.heatmap, heatH = m.buildHeatmap(halfW)
	m.lineChart = m.buildLineChart(halfW, heatH)
}

func (m *UsageViewModel) chartWidth() int {
	w := m.width - 6
	if w < 50 {
		w = 80
	}
	return w
}

// ── Content builder ──

func (m *UsageViewModel) buildContent() string {
	d := m.data
	w := m.chartWidth()
	panelW := w - 2
	halfW := (panelW - 4) / 2

	var rows []string

	// Header
	header := lipgloss.NewStyle().Bold(true).Foreground(style.Primary).Render("Token Usage")
	rows = append(rows, header)
	rows = append(rows, "")
	rows = append(rows, m.buildDateNavigator())
	rows = append(rows, "")

	if m.botName != "" || m.groupName != "" {
		scope := ""
		if m.botName != "" {
			scope += fmt.Sprintf("Bot: %s", style.InfoText(m.botName))
			if m.botID != "" {
				scope += " " + style.Muted("("+m.botID+")")
			}
		}
		if m.groupName != "" {
			if scope != "" {
				scope += "    "
			}
			scope += fmt.Sprintf("Group: %s", style.InfoText(m.groupName))
			if m.groupID != "" {
				scope += " " + style.Muted("("+m.groupID+")")
			}
		}
		rows = append(rows, scope)
	}
	rows = append(rows, style.Muted(fmt.Sprintf("Unit: %s", d.PriceUnit)))
	rows = append(rows, m.buildPricingTable()...)
	rows = append(rows, "")

	// KPI grid (5 columns)
	rows = append(rows, m.buildKPIGrid(panelW)...)
	rows = append(rows, "")

	// Accumulated totals
	rows = append(rows, sectionTitle("Accumulated Totals"))
	rows = append(rows, "")
	rows = append(rows, m.buildLedger(panelW)...)
	rows = append(rows, "")

	// Distribution: dual-column (Scene | Model)
	// Title + legend top-aligned; bar bodies bottom-aligned.
	sceneLines := strings.Split(m.sceneChart, "\n")
	modelLines := strings.Split(m.modelChart, "\n")

	// Split into header (first 3: title + legend + spacer) and body (bars)
	sceneHead, sceneBody := sceneLines[:3], sceneLines[3:]
	modelHead, modelBody := modelLines[:3], modelLines[3:]

	// Pad shorter body at top so bar bottoms align
	maxBody := len(sceneBody)
	if len(modelBody) > maxBody {
		maxBody = len(modelBody)
	}
	padScene := maxBody - len(sceneBody)
	padModel := maxBody - len(modelBody)
	scenePadded := make([]string, 0, 2+maxBody)
	modelPadded := make([]string, 0, 2+maxBody)
	scenePadded = append(scenePadded, sceneHead...)
	for range padScene {
		scenePadded = append(scenePadded, "")
	}
	scenePadded = append(scenePadded, sceneBody...)
	modelPadded = append(modelPadded, modelHead...)
	for range padModel {
		modelPadded = append(modelPadded, "")
	}
	modelPadded = append(modelPadded, modelBody...)

	rows = append(rows, sectionTitle("Distribution"))
	rows = append(rows, "")
	rows = append(rows, lipgloss.JoinHorizontal(lipgloss.Top,
		strings.Join(scenePadded, "\n"), "    ",
		strings.Join(modelPadded, "\n")))
	rows = append(rows, "")

	// Weekly trend + Daily heatmap: dual-column
	trendCol := sectionTitle("Weekly Trend") + "\n\n" + m.lineChart
	intensityCol := buildDailyIntensityColumn(m.heatmap, halfW)
	rows = append(rows, lipgloss.JoinHorizontal(lipgloss.Bottom, trendCol, "    ", intensityCol))
	rows = append(rows, "")

	return strings.Join(rows, "\n")
}

func buildDailyIntensityColumn(heatmap string, width int) string {
	centeredHeatmap := lipgloss.NewStyle().
		Width(width).
		Align(lipgloss.Center).
		Render(heatmap)
	return sectionTitle("Daily Intensity") + "\n\n" + centeredHeatmap
}

func (m *UsageViewModel) buildPricingTable() []string {
	if m.data == nil || m.data.Pricing == nil {
		return nil
	}

	titleStyle := lipgloss.NewStyle().Foreground(style.Text).Bold(true)
	priceStyle := lipgloss.NewStyle().Foreground(style.TextBody)
	item := func(tier string, pricing api.TokenUsageTierPricing) string {
		values := strings.Join([]string{
			formatPrice(pricing.InputCacheMiss),
			formatPrice(pricing.InputCacheHit),
			formatPrice(pricing.Output),
		}, "/")
		return titleStyle.Render(tier) + priceStyle.Render("("+values+")")
	}

	pricing := m.data.Pricing
	return []string{
		strings.Join([]string{
			item("Lite", pricing.Lite),
			item("Flash", pricing.Flash),
			item("Pro", pricing.Pro),
		}, "  ") + style.Muted(" / 1M tokens"),
	}
}

func (m *UsageViewModel) buildKPIGrid(width int) []string {
	d := m.selectedDailyView()
	if d == nil {
		return nil
	}
	colW := width/5 - 2
	if colW < 12 {
		colW = 12
	}

	kpiBox := func(accent bool) lipgloss.Style {
		s := lipgloss.NewStyle().
			BorderStyle(lipgloss.NormalBorder()).
			Width(colW)
		if accent {
			s = s.BorderForeground(barHitColor)
		} else {
			s = s.BorderForeground(style.BorderStrong)
		}
		return s
	}

	labelStyle := lipgloss.NewStyle().Foreground(style.TextMuted).Width(colW - 4)
	valueStyle := lipgloss.NewStyle().Bold(true).Foreground(style.Text).Width(colW - 4)

	costSymbol := currencySymbol(m.data.PriceUnit)

	cols := []string{
		kpiBox(false).Render(labelStyle.Render("Input Hit/Miss") + "\n" + valueStyle.Render(compactNumber(d.CacheHitInput)+" / "+compactNumber(d.CacheMissInput))),
		kpiBox(false).Render(labelStyle.Render("Output") + "\n" + valueStyle.Render(compactNumber(d.Output))),
		kpiBox(false).Render(labelStyle.Render("Total") + "\n" + valueStyle.Render(compactNumber(d.CacheHitInput+d.CacheMissInput+d.Output))),
		kpiBox(true).Render(labelStyle.Render("Cost") + "\n" + valueStyle.Render(fmt.Sprintf("%s%.4f", costSymbol, d.Cost))),
		kpiBox(false).Render(labelStyle.Render("Hit Rate") + "\n" + valueStyle.Render(fmt.Sprintf("%.1f%%", d.CacheHitRate))),
	}

	row := lipgloss.JoinHorizontal(lipgloss.Top, cols...)
	return []string{row}
}

func (m *UsageViewModel) buildLedger(width int) []string {
	d := m.data
	colW := width/5 - 2
	if colW < 12 {
		colW = 12
	}

	cell := lipgloss.NewStyle().
		BorderStyle(lipgloss.NormalBorder()).
		BorderForeground(style.BorderStrong).
		Width(colW)

	labelStyle := lipgloss.NewStyle().Foreground(style.TextMuted).Width(colW - 4)
	valueStyle := lipgloss.NewStyle().Bold(true).Foreground(style.Text).Width(colW - 4)

	costSymbol := currencySymbol(d.PriceUnit)

	cols := []string{
		cell.Render(labelStyle.Render("Input Hit") + "\n" + valueStyle.Render(compactNumber(d.TotalCacheHitInput))),
		cell.Render(labelStyle.Render("Input Miss") + "\n" + valueStyle.Render(compactNumber(d.TotalCacheMissInput))),
		cell.Render(labelStyle.Render("Output") + "\n" + valueStyle.Render(compactNumber(d.TotalOutput))),
		cell.Render(labelStyle.Render("Cost") + "\n" + valueStyle.Render(fmt.Sprintf("%s%.4f", costSymbol, d.TotalCost))),
		cell.Render(labelStyle.Render("Total") + "\n" + valueStyle.Render(compactNumber(d.TotalCacheHitInput+d.TotalCacheMissInput+d.TotalOutput))),
	}

	row := lipgloss.JoinHorizontal(lipgloss.Top, cols...)
	return []string{row}
}

// ── Helpers ──

func (m *UsageViewModel) prepareDailyViews() {
	if m.data == nil {
		return
	}
	if len(m.data.DailyViews) == 0 {
		m.data.DailyViews = []api.DailyTokenUsageSummary{{
			Date:           time.Now().Format("2006-01-02"),
			CacheHitInput:  m.data.TodayCacheHitInput,
			CacheMissInput: m.data.TodayCacheMissInput,
			Output:         m.data.TodayOutput,
			Cost:           m.data.TodayCost,
			CacheHitRate:   m.data.TodayCacheHitRate,
			SceneBars:      m.data.SceneBars,
			ModelBars:      m.data.ModelBars,
		}}
	}
	if m.selectedDay < 0 || m.selectedDay >= len(m.data.DailyViews) {
		m.selectedDay = 0
	}
	m.syncDateKeys()
}

func (m *UsageViewModel) selectedDailyView() *api.DailyTokenUsageSummary {
	if m.data == nil || len(m.data.DailyViews) == 0 {
		return nil
	}
	if m.selectedDay < 0 || m.selectedDay >= len(m.data.DailyViews) {
		m.selectedDay = 0
	}
	return &m.data.DailyViews[m.selectedDay]
}

func (m *UsageViewModel) selectDay(index int) {
	if m.data == nil || index < 0 || index >= len(m.data.DailyViews) || index == m.selectedDay {
		return
	}
	m.selectedDay = index
	m.syncDateKeys()
	m.buildCharts()
	m.viewport.SetContent(m.buildContent())
}

func (m *UsageViewModel) syncDateKeys() {
	count := 0
	if m.data != nil {
		count = len(m.data.DailyViews)
	}
	m.keys.OlderDate.SetEnabled(count > 1 && m.selectedDay < count-1)
	m.keys.NewerDate.SetEnabled(count > 1 && m.selectedDay > 0)
}

func (m *UsageViewModel) buildDateNavigator() string {
	if m.data == nil || len(m.data.DailyViews) == 0 {
		return ""
	}
	today := time.Now().Format("2006-01-02")
	selectedStyle := lipgloss.NewStyle().Bold(true).Foreground(style.Primary)
	labels := make([]string, 0, len(m.data.DailyViews))
	for i := len(m.data.DailyViews) - 1; i >= 0; i-- {
		day := m.data.DailyViews[i]
		label := formatUsageDate(day.Date, day.Date == today)
		if i == m.selectedDay {
			labels = append(labels, selectedStyle.Render("["+label+"]"))
		} else {
			labels = append(labels, style.Muted(label))
		}
	}
	return style.Muted("Date:") + " " + strings.Join(labels, "  ")
}

func formatUsageDate(value string, today bool) string {
	date, err := time.Parse("2006-01-02", value)
	if err != nil {
		if today {
			return "Today (" + value + ")"
		}
		return value
	}
	label := date.Format("01-02")
	if today {
		return "Today (" + label + ")"
	}
	return label
}

func sectionTitle(title string) string {
	return lipgloss.NewStyle().Foreground(style.Secondary).Bold(true).Render(title)
}

func compactNumber(v int64) string {
	if v < 1000 {
		return fmt.Sprintf("%d", v)
	}
	suffixes := []string{"", "K", "M", "B", "T"}
	var magnitude int
	num := float64(v)
	for num >= 1000 && magnitude < len(suffixes)-1 {
		num /= 1000
		magnitude++
	}
	formatted := fmt.Sprintf("%.1f", num)
	formatted = strings.TrimSuffix(formatted, ".0")
	return formatted + suffixes[magnitude]
}

func formatPrice(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}

func currencySymbol(unit string) string {
	switch strings.ToUpper(unit) {
	case "USD":
		return "$"
	case "CNY", "RMB":
		return "¥"
	case "EUR":
		return "€"
	case "JPY":
		return "¥"
	case "GBP":
		return "£"
	default:
		return unit
	}
}

var sceneOrder = []string{"聊天", "搜索", "插件", "记忆", "摘要", "情绪", "心流", "冲动", "偏好", "热词", "表情", "路由", "其他"}

var sceneNameEN = map[string]string{
	"聊天": "Chat",
	"搜索": "Search",
	"插件": "Plugin",
	"记忆": "Memory",
	"摘要": "Summary",
	"情绪": "Emotion",
	"心流": "Flow",
	"冲动": "Impulse",
	"偏好": "Prefs",
	"热词": "Trends",
	"表情": "Memes",
	"路由": "Router",
	"其他": "Other",
}

func sortSceneRows(rows []api.TokenUsageChartPoint) []api.TokenUsageChartPoint {
	index := make(map[string]int)
	for i, name := range sceneOrder {
		index[name] = i
	}
	sorted := make([]api.TokenUsageChartPoint, len(rows))
	copy(sorted, rows)

	// Sort using insertion-like logic: known scenes first in order, then unknowns sorted by total
	known := make([]api.TokenUsageChartPoint, 0)
	unknown := make([]api.TokenUsageChartPoint, 0)
	for _, r := range sorted {
		if _, ok := index[r.Name]; ok {
			known = append(known, r)
		} else {
			unknown = append(unknown, r)
		}
	}

	// Sort known by sceneOrder
	for i := 0; i < len(known); i++ {
		for j := i + 1; j < len(known); j++ {
			if index[known[i].Name] > index[known[j].Name] {
				known[i], known[j] = known[j], known[i]
			}
		}
	}

	// Sort unknown by total descending
	for i := 0; i < len(unknown); i++ {
		for j := i + 1; j < len(unknown); j++ {
			totalI := unknown[i].CacheHitInput + unknown[i].CacheMissInput + unknown[i].Output
			totalJ := unknown[j].CacheHitInput + unknown[j].CacheMissInput + unknown[j].Output
			if totalI < totalJ {
				unknown[i], unknown[j] = unknown[j], unknown[i]
			}
		}
	}

	result := make([]api.TokenUsageChartPoint, 0, len(known)+len(unknown))
	result = append(result, known...)
	result = append(result, unknown...)
	return result
}

func translateSceneNames(rows []api.TokenUsageChartPoint) []api.TokenUsageChartPoint {
	result := make([]api.TokenUsageChartPoint, len(rows))
	for i, r := range rows {
		result[i] = r
		if en, ok := sceneNameEN[r.Name]; ok {
			result[i].Name = en
		}
	}
	return result
}
