package setup

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"erii-cli/internal/path"
	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/bubbles/paginator"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss"
)

// ---- Data types ----

type Provider struct {
	Name    string `json:"name"`
	Key     string `json:"key"`
	API     string `json:"api"`
	Desc    string `json:"desc"`
	BaseURL string `json:"base-url"`
}

type ToolProvider struct {
	Name  string `json:"name"`
	URL   string `json:"url"`
	Model string `json:"model"`
}

type LLMDefaults struct {
	BaseURL      string                   `json:"base-url"`
	ModelMode    string                   `json:"model-mode"`
	LiteModel    string                   `json:"lite-model"`
	FlashModel   string                   `json:"flash-model"`
	ProModel     string                   `json:"pro-model"`
	AllModel     string                   `json:"all-model"`
	Settings     map[string]string        `json:"settings"`
	Capability   *LLMCapabilityDefaults   `json:"capability"`
	UsagePricing *LLMUsagePricingDefaults `json:"usage-pricing"`
}

type LLMCapabilitySet struct {
	Completion      bool `json:"completion"`
	PromptCaching   bool `json:"prompt-caching"`
	Temperature     bool `json:"temperature"`
	Tools           bool `json:"tools"`
	ToolChoice      bool `json:"tool-choice"`
	MultipleChoices bool `json:"multiple-choices"`
	Thinking        bool `json:"thinking"`
	VisionImage     bool `json:"vision-image"`
}

type LLMCapabilityDefaults struct {
	Default LLMCapabilitySet `json:"default"`
	Lite    LLMCapabilitySet `json:"lite"`
	Flash   LLMCapabilitySet `json:"flash"`
	Pro     LLMCapabilitySet `json:"pro"`
}

type LLMPricingTierDefaults struct {
	InputCacheHit  float64 `json:"input-cache-hit"`
	InputCacheMiss float64 `json:"input-cache-miss"`
	Output         float64 `json:"output"`
}

type LLMUsagePricingDefaults struct {
	PriceUnit string                 `json:"price-unit"`
	Lite      LLMPricingTierDefaults `json:"lite"`
	Flash     LLMPricingTierDefaults `json:"flash"`
	Pro       LLMPricingTierDefaults `json:"pro"`
}

type BotDefaults struct {
	WS    string `json:"ws"`
	Token string `json:"token"`
}

type GroupsDefaults struct {
	DebugGroupID       string `json:"debug-group-id"`
	EnableGroups       string `json:"enable-groups"`
	MessageRedirectMap string `json:"message-redirect-map"`
}

type BrowserDefaults struct {
	Download      bool   `json:"download"`
	PlaywrightURL string `json:"playwright-url"`
	ExternalHost  string `json:"external-host"`
}

type ProxyDefaults struct {
	HTTP  string `json:"http"`
	SOCKS string `json:"socks"`
}

type DefaultsConfig struct {
	LLM             map[string]LLMDefaults  `json:"llm"`
	LLMCapability   LLMCapabilityDefaults   `json:"llm-capability"`
	LLMUsagePricing LLMUsagePricingDefaults `json:"llm-usage-pricing"`
	Bot             BotDefaults             `json:"bot"`
	Groups          GroupsDefaults          `json:"groups"`
	Browser         BrowserDefaults         `json:"browser"`
	Proxy           ProxyDefaults           `json:"proxy"`
}

type ToolProvidersConfig struct {
	Embedding []ToolProvider `json:"embedding"`
	Search    []ToolProvider `json:"search"`
	STT       []ToolProvider `json:"stt"`
	Vision    []ToolProvider `json:"vision"`
	Browser   []ToolProvider `json:"browser"`
}

type SetupFile struct {
	Providers     []Provider          `json:"providers"`
	Defaults      DefaultsConfig      `json:"defaults"`
	ToolProviders ToolProvidersConfig `json:"tool-providers"`
}

type SetupData struct {
	Providers       []Provider
	SelectedProv    int
	LLMDefaults     map[string]LLMDefaults
	APIKey          string
	BaseURL         string
	ModelMode       string
	AllModel        string
	LiteModel       string
	FlashModel      string
	ProModel        string
	AdvancedLLM     bool
	LLMSettings     map[string]string
	LLMCapability   LLMCapabilityDefaults
	LLMUsagePricing LLMUsagePricingDefaults

	EmbeddingEnabled  bool
	EmbeddingAPIKey   string
	EmbeddingURL      string
	EmbeddingProvider string
	EmbeddingModel    string
	SearchEnabled     bool
	SearchAPIKey      string
	SearchURL         string
	SearchProvider    string
	STTEnabled        bool
	STTAPIKey         string
	STTURL            string
	STTProvider       string
	STTModel          string
	VisionEnabled     bool
	VisionAPIKey      string
	VisionURL         string
	VisionProvider    string
	BrowserEnabled    bool
	BrowserDownload   bool
	PlaywrightURL     string
	ExternalHost      string
	ProxyEnabled      bool
	HTTPProxy         string
	SOCKSProxy        string

	BotWS     string
	BotToken  string
	BotAdmins string

	DebugGroupID       string
	EnableGroups       string
	MessageRedirectMap string
	ServerUsername     string
	ServerPassword     string

	ToolProviders ToolProvidersConfig
}

// ---- Steps ----

type Step int

const (
	StepProviderSelect Step = iota
	StepLLMConfig
	StepLLMAdvancedAsk
	StepLLMProviderSettings
	StepLLMCapabilityDefault
	StepLLMCapabilityLite
	StepLLMCapabilityFlash
	StepLLMCapabilityPro
	StepLLMUsagePricing
	StepToolsMenu
	StepToolsEmbedding
	StepToolsSearch
	StepToolsSTT
	StepToolsVision
	StepToolsBrowser
	StepToolsProxy
	StepBot
	StepGroups
	StepCoreAuth
	StepDone
)

var nodeLabels = []string{"LLM Configuration", "Tools & Features", "Default Bot (erii)", "Groups", "Core Authentication"}

func (s Step) node() int {
	switch {
	case s <= StepLLMUsagePricing:
		return 0
	case s <= StepToolsProxy:
		return 1
	case s <= StepBot:
		return 2
	case s <= StepGroups:
		return 3
	case s <= StepCoreAuth:
		return 4
	default:
		return len(nodeLabels)
	}
}

var llmAdvancedTabDefs = []struct {
	label string
	step  Step
}{
	{label: "All", step: StepLLMCapabilityDefault},
	{label: "Lite", step: StepLLMCapabilityLite},
	{label: "Flash", step: StepLLMCapabilityFlash},
	{label: "Pro", step: StepLLMCapabilityPro},
}

func newLLMTabs() paginator.Model {
	p := paginator.New(
		paginator.WithPerPage(1),
		paginator.WithTotalPages(len(llmAdvancedTabDefs)),
	)
	p.Type = paginator.Dots
	p.KeyMap = paginator.KeyMap{
		PrevPage: key.NewBinding(
			key.WithKeys("["),
			key.WithHelp("[", "prev tab"),
		),
		NextPage: key.NewBinding(
			key.WithKeys("]"),
			key.WithHelp("]", "next tab"),
		),
	}
	return p
}

func newCapabilityPager() paginator.Model {
	p := paginator.New(
		paginator.WithPerPage(capabilityPageSize),
		paginator.WithTotalPages(capabilityPageCount),
	)
	p.Type = paginator.Arabic
	p.ArabicFormat = "%d/%d"
	p.KeyMap = paginator.KeyMap{
		PrevPage: key.NewBinding(
			key.WithKeys("pgup"),
			key.WithHelp("pgup", "previous page"),
		),
		NextPage: key.NewBinding(
			key.WithKeys("pgdown"),
			key.WithHelp("pgdown", "next page"),
		),
	}
	return p
}

func (s Step) llmAdvancedTabPage() int {
	for i, tab := range llmAdvancedTabDefs {
		if tab.step == s {
			return i
		}
	}
	return -1
}

func llmAdvancedStepForPage(page int) Step {
	if page < 0 || page >= len(llmAdvancedTabDefs) {
		return StepLLMCapabilityDefault
	}
	return llmAdvancedTabDefs[page].step
}

func hoconKeyToChoiceModel(key string) string {
	return strings.ReplaceAll(strings.ToUpper(key), "-", "_")
}

func providerAPI(p Provider) string {
	api := strings.ToLower(strings.TrimSpace(p.API))
	if api != "" {
		return api
	}
	switch strings.ToLower(strings.TrimSpace(p.Key)) {
	case "openai":
		return "openai"
	case "anthropic":
		return "anthropic"
	default:
		return ""
	}
}

func providerCoreKey(p Provider) string {
	return providerAPI(p)
}

func validProviderAPI(api string) bool {
	switch strings.ToLower(strings.TrimSpace(api)) {
	case "openai", "anthropic":
		return true
	default:
		return false
	}
}

func defaultLLMCapability() LLMCapabilityDefaults {
	all := LLMCapabilitySet{
		Completion: true, PromptCaching: true, Temperature: true, Tools: true,
		ToolChoice: true, MultipleChoices: true, Thinking: true, VisionImage: true,
	}
	lite := all
	lite.Thinking = false
	lite.VisionImage = false
	return LLMCapabilityDefaults{Default: all, Lite: lite, Flash: all, Pro: all}
}

func defaultLLMUsagePricing() LLMUsagePricingDefaults {
	return LLMUsagePricingDefaults{
		PriceUnit: "USD",
		Lite:      LLMPricingTierDefaults{InputCacheHit: 0.01875, InputCacheMiss: 0.075, Output: 0.30},
		Flash:     LLMPricingTierDefaults{InputCacheHit: 0.025, InputCacheMiss: 0.10, Output: 0.40},
		Pro:       LLMPricingTierDefaults{InputCacheHit: 0.3125, InputCacheMiss: 1.25, Output: 10.00},
	}
}

func mergeCapabilityDefaults(v LLMCapabilityDefaults) LLMCapabilityDefaults {
	if v == (LLMCapabilityDefaults{}) {
		return defaultLLMCapability()
	}
	return v
}

func mergeUsagePricingDefaults(v LLMUsagePricingDefaults) LLMUsagePricingDefaults {
	if v.PriceUnit == "" && v.Lite == (LLMPricingTierDefaults{}) && v.Flash == (LLMPricingTierDefaults{}) && v.Pro == (LLMPricingTierDefaults{}) {
		return defaultLLMUsagePricing()
	}
	if v.PriceUnit == "" {
		v.PriceUnit = "USD"
	}
	return v
}

// ---- Key bindings ----

type SetupKeyMap struct {
	Enter key.Binding
	Nav   key.Binding
	Tab   key.Binding
	Page  key.Binding
	Back  key.Binding
	Quit  key.Binding
	Help  key.Binding
}

func (k SetupKeyMap) ShortHelp() []key.Binding {
	if k.Tab.Enabled() {
		return []key.Binding{k.Enter, k.Nav, k.Tab, k.Page, k.Back, k.Quit, k.Help}
	}
	return []key.Binding{k.Enter, k.Nav, k.Back, k.Quit, k.Help}
}

func (k SetupKeyMap) FullHelp() [][]key.Binding {
	if k.Tab.Enabled() {
		return [][]key.Binding{
			{k.Enter, k.Nav, k.Tab, k.Page},
			{k.Back, k.Quit, k.Help},
		}
	}
	return [][]key.Binding{
		{k.Enter, k.Nav},
		{k.Back, k.Quit, k.Help},
	}
}

var DefaultSetupKeys = SetupKeyMap{
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "confirm"),
	),
	Nav: key.NewBinding(
		key.WithKeys("j", "k", "up", "down"),
		key.WithHelp("j/k/↑/↓", "navigate"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "back"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "toggle help"),
	),
}

var FormKeys = SetupKeyMap{
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "confirm"),
	),
	Nav: key.NewBinding(
		key.WithKeys("tab", "shift+tab"),
		key.WithHelp("tab/shift+tab", "navigate"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "back"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "toggle help"),
	),
}

var AdvancedFormKeys = SetupKeyMap{
	Enter: FormKeys.Enter,
	Nav:   FormKeys.Nav,
	Tab: key.NewBinding(
		key.WithKeys("[", "]"),
		key.WithHelp("[/]", "switch tab"),
	),
	Page: key.NewBinding(
		key.WithKeys("pgup", "pgdown"),
		key.WithHelp("pgup/pgdn", "capability page"),
	),
	Back: FormKeys.Back,
	Quit: FormKeys.Quit,
	Help: FormKeys.Help,
}

var DoneKeys = SetupKeyMap{
	Enter: key.NewBinding(
		key.WithKeys("y"),
		key.WithHelp("y", "confirm"),
	),
	Nav: key.NewBinding(
		key.WithKeys("n"),
		key.WithHelp("n", "cancel"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "back"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "toggle help"),
	),
}

func (m Model) currentKeys() SetupKeyMap {
	if m.step == StepProviderSelect || m.step == StepToolsMenu {
		return DefaultSetupKeys
	}
	if m.step == StepDone {
		return DoneKeys
	}
	if m.isLLMAdvancedStep() {
		return AdvancedFormKeys
	}
	return FormKeys
}

// ---- List item ----

type providerItem struct {
	name    string
	key     string
	api     string
	desc    string
	baseURL string
	index   int
}

func (i providerItem) Title() string { return i.name }
func (i providerItem) Description() string {
	return fmt.Sprintf("%s  |  api=%s  |  %s", i.desc, i.api, i.baseURL)
}
func (i providerItem) FilterValue() string { return i.name }

type toolItem struct {
	name   string
	desc   string
	step   Step
	isDone bool
}

func (i toolItem) Title() string { return i.name }
func (i toolItem) Description() string {
	if i.isDone {
		return ""
	}
	return i.desc
}
func (i toolItem) FilterValue() string { return i.name }

// ---- Model ----

type Model struct {
	step Step
	data *SetupData

	width  int
	height int

	list    list.Model
	form    *huh.Form
	confirm *huh.Form

	help help.Model
	keys SetupKeyMap

	wrote    bool
	writeErr error
	quitting bool

	lastEmbeddingProvider string
	lastSearchProvider    string
	lastSTTProvider       string
	lastVisionProvider    string
	llmTabs               paginator.Model
	capabilityPager       paginator.Model
	history               []setupNavigationState
	vp                    viewport.Model
}

type setupNavigationState struct {
	step           Step
	capabilityPage int
}

func (m Model) navigationState() setupNavigationState {
	return setupNavigationState{
		step:           m.step,
		capabilityPage: m.capabilityPager.Page,
	}
}

func (m *Model) navigateTo(step Step) {
	if step == m.step {
		return
	}
	m.history = append(m.history, m.navigationState())
	m.step = step
}

func (m *Model) navigateBack() bool {
	if len(m.history) == 0 {
		return false
	}
	last := len(m.history) - 1
	state := m.history[last]
	m.history = m.history[:last]
	m.step = state.step
	m.capabilityPager.Page = state.capabilityPage
	return true
}

func (m *Model) returnToStep(step Step) {
	if len(m.history) > 0 && m.history[len(m.history)-1].step == step {
		m.navigateBack()
		return
	}
	m.navigateTo(step)
}

func isToolConfigurationStep(step Step) bool {
	return step >= StepToolsEmbedding && step <= StepToolsProxy
}

func newModel(providers []Provider, defaults DefaultsConfig, toolProviders ToolProvidersConfig) Model {
	defaults.LLMCapability = mergeCapabilityDefaults(defaults.LLMCapability)
	defaults.LLMUsagePricing = mergeUsagePricingDefaults(defaults.LLMUsagePricing)
	data := &SetupData{
		Providers:          providers,
		LLMDefaults:        defaults.LLM,
		LLMCapability:      defaults.LLMCapability,
		LLMUsagePricing:    defaults.LLMUsagePricing,
		LLMSettings:        map[string]string{},
		BotWS:              defaults.Bot.WS,
		BotToken:           defaults.Bot.Token,
		BrowserDownload:    defaults.Browser.Download,
		PlaywrightURL:      defaults.Browser.PlaywrightURL,
		ExternalHost:       defaults.Browser.ExternalHost,
		HTTPProxy:          defaults.Proxy.HTTP,
		SOCKSProxy:         defaults.Proxy.SOCKS,
		DebugGroupID:       defaults.Groups.DebugGroupID,
		EnableGroups:       defaults.Groups.EnableGroups,
		MessageRedirectMap: defaults.Groups.MessageRedirectMap,
		ServerUsername:     defaultServerUsername,
		ServerPassword:     defaultServerPassword,
		ToolProviders:      toolProviders,
	}

	m := Model{
		step:            StepProviderSelect,
		data:            data,
		help:            help.New(),
		keys:            DefaultSetupKeys,
		llmTabs:         newLLMTabs(),
		capabilityPager: newCapabilityPager(),
		vp:              viewport.New(0, 0),
	}
	m.buildProviderList()
	return m
}

// ---- Entry point ----

func Start() error {
	configPath := filepath.Join(path.ConfMetaDir, "setup.json")
	data, err := os.ReadFile(configPath)
	if err != nil {
		return fmt.Errorf("cannot read setup.json (%s): %w", configPath, err)
	}

	var sf SetupFile
	if err := json.Unmarshal(data, &sf); err != nil {
		return fmt.Errorf("cannot parse setup.json: %w", err)
	}

	if len(sf.Providers) == 0 {
		return fmt.Errorf("no providers configured in setup.json")
	}
	for i := range sf.Providers {
		if sf.Providers[i].API == "" {
			sf.Providers[i].API = providerAPI(sf.Providers[i])
		}
		if !validProviderAPI(sf.Providers[i].API) {
			return fmt.Errorf("invalid api for provider %q: %q (must be openai or anthropic)", sf.Providers[i].Key, sf.Providers[i].API)
		}
	}

	m := newModel(sf.Providers, sf.Defaults, sf.ToolProviders)
	if username, password := readCoreCredentials(path.EnvFile); username != "" || password != "" {
		if username != "" {
			m.data.ServerUsername = username
		}
		if password != "" {
			m.data.ServerPassword = password
		}
	}
	p := tea.NewProgram(m, tea.WithAltScreen())
	_, err = p.Run()
	return err
}

// ---- tea.Model ----

func (m Model) Init() tea.Cmd {
	return tea.SetWindowTitle("Erii Setup")
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.syncSizes()
		footerHeight := lipgloss.Height(m.help.View(m.currentKeys()))
		vpHeight := msg.Height - footerHeight - 1
		if vpHeight < 3 {
			vpHeight = 3
		}
		m.vp.Width = msg.Width
		m.vp.Height = vpHeight
		m.syncViewportContent()
		return m, nil

	case tea.KeyMsg:
		if m.step == StepDone {
			switch msg.String() {
			case "y", "Y":
				m.writeConfig()
				m.quitting = true
				return m, tea.Quit
			case "n", "N", "q":
				m.quitting = true
				return m, tea.Quit
			}
			if key.Matches(msg, m.keys.Quit) {
				m.quitting = true
				return m, tea.Quit
			}
			if key.Matches(msg, m.keys.Back) {
				if m.navigateBack() {
					m.rebuildCurrentStep()
					return m, m.currentInitCmd()
				}
				return m, nil
			}

			var cmd tea.Cmd
			m.vp, cmd = m.vp.Update(msg)
			return m, cmd
		}

		if key.Matches(msg, m.keys.Quit) {
			m.quitting = true
			return m, tea.Quit
		}

		if key.Matches(msg, m.keys.Help) {
			m.help.ShowAll = !m.help.ShowAll
			return m, nil
		}

		if handled, cmd := m.handleCapabilityPageKey(msg); handled {
			return m, cmd
		}

		if handled, cmd := m.handleLLMTabKey(msg); handled {
			return m, cmd
		}

		if key.Matches(msg, m.keys.Back) {
			if m.isLLMAdvancedStep() && !m.capabilityPager.OnFirstPage() {
				m.capabilityPager.PrevPage()
				m.rebuildCurrentStep()
				return m, m.currentInitCmd()
			}
			if m.navigateBack() {
				m.rebuildCurrentStep()
				return m, m.currentInitCmd()
			}
			return m, nil
		}
	}

	switch m.step {
	case StepProviderSelect:
		return m.updateProviderSelect(msg)
	case StepLLMConfig:
		return m.updateForm(msg, StepLLMAdvancedAsk)
	case StepLLMAdvancedAsk:
		return m.updateLLMAdvancedAsk(msg)
	case StepLLMProviderSettings:
		return m.updateForm(msg, StepLLMCapabilityDefault)
	case StepLLMCapabilityDefault:
		return m.updateCapabilityForm(msg, StepLLMCapabilityLite)
	case StepLLMCapabilityLite:
		return m.updateCapabilityForm(msg, StepLLMCapabilityFlash)
	case StepLLMCapabilityFlash:
		return m.updateCapabilityForm(msg, StepLLMCapabilityPro)
	case StepLLMCapabilityPro:
		return m.updateCapabilityForm(msg, StepLLMUsagePricing)
	case StepLLMUsagePricing:
		return m.updateForm(msg, StepToolsMenu)
	case StepToolsMenu:
		return m.updateToolsMenu(msg)
	case StepToolsEmbedding, StepToolsSearch, StepToolsSTT, StepToolsVision, StepToolsBrowser, StepToolsProxy:
		return m.updateForm(msg, StepToolsMenu)
	case StepBot:
		return m.updateForm(msg, StepGroups)
	case StepGroups:
		return m.updateForm(msg, StepCoreAuth)
	case StepCoreAuth:
		return m.updateForm(msg, StepDone)
	case StepDone:
		// All interactions handled in tea.KeyMsg block above
	default:
		panic("unhandled default case")
	}

	return m, nil
}

func (m Model) View() string {
	if m.width == 0 {
		return "Loading..."
	}

	if m.quitting {
		if m.wrote {
			return style.SuccessText("Configuration saved.\n\nPress any key to exit...")
		}
		if m.writeErr != nil {
			return style.ErrorText("Failed to write config: " + m.writeErr.Error() + "\n\nPress any key to exit...")
		}
		return lipgloss.NewStyle().Foreground(style.Text).Render("Setup cancelled.\n\nPress any key to exit...")
	}

	footer := m.help.View(m.currentKeys())
	view := lipgloss.JoinVertical(lipgloss.Left,
		m.vp.View(),
		footer,
	)
	return view
}

// ---- Step rendering ----

func (m Model) stepTitle() string {
	switch m.step {
	case StepProviderSelect:
		return "LLM Configuration"
	case StepLLMConfig, StepLLMAdvancedAsk, StepLLMProviderSettings, StepLLMCapabilityDefault, StepLLMCapabilityLite, StepLLMCapabilityFlash, StepLLMCapabilityPro, StepLLMUsagePricing:
		return "LLM Configuration"
	case StepToolsMenu:
		return "Tools & Features"
	case StepToolsEmbedding:
		return "Embedding Configuration"
	case StepToolsSearch:
		return "Search Configuration"
	case StepToolsSTT:
		return "Speech-to-Text Configuration"
	case StepToolsVision:
		return "Vision Configuration"
	case StepToolsBrowser:
		return "Browser Configuration"
	case StepToolsProxy:
		return "Proxy Configuration"
	case StepBot:
		return "Default Bot (erii)"
	case StepGroups:
		return "Groups"
	case StepCoreAuth:
		return "Core Authentication"
	default:
		return ""
	}
}

func (m Model) renderContent() string {
	switch m.step {
	case StepProviderSelect, StepToolsMenu:
		return renderStepPage(m.stepTitle(), m.list.View())
	case StepLLMConfig, StepLLMAdvancedAsk, StepLLMProviderSettings, StepLLMCapabilityDefault, StepLLMCapabilityLite, StepLLMCapabilityFlash, StepLLMCapabilityPro, StepLLMUsagePricing, StepToolsEmbedding, StepToolsSearch, StepToolsSTT, StepToolsVision, StepToolsBrowser, StepToolsProxy, StepBot, StepGroups, StepCoreAuth:
		return renderFormStep(m.stepTitle(), m.form, m.llmAdvancedTabs())
	case StepDone:
		return m.renderSummary()
	}
	return ""
}

func (m Model) llmAdvancedTabs() []tabItem {
	activePage := m.step.llmAdvancedTabPage()
	if activePage < 0 {
		return nil
	}
	tabs := make([]tabItem, 0, len(llmAdvancedTabDefs))
	for i, tab := range llmAdvancedTabDefs {
		tabs = append(tabs, tabItem{Label: tab.label, Active: i == activePage})
	}
	return tabs
}

// ---- Summary ----

func (m Model) renderSummary() string {
	var b strings.Builder
	b.WriteString(style.Title("Configuration Summary"))
	b.WriteString("\n\n")

	d := m.data
	prov := d.Providers[d.SelectedProv]

	b.WriteString(style.Subtitle("LLM"))
	b.WriteString("\n")
	b.WriteString(fmt.Sprintf("  Provider: %s\n", prov.Name))
	b.WriteString(fmt.Sprintf("  Base URL: %s\n", d.BaseURL))
	if d.ModelMode == "all" {
		b.WriteString(fmt.Sprintf("  Model (all tiers): %s\n", d.AllModel))
	} else {
		b.WriteString(fmt.Sprintf("  Lite: %s\n", d.LiteModel))
		b.WriteString(fmt.Sprintf("  Flash: %s\n", d.FlashModel))
		b.WriteString(fmt.Sprintf("  Pro: %s\n", d.ProModel))
	}
	if d.AdvancedLLM {
		b.WriteString(fmt.Sprintf("  Advanced: configured (price-unit=%s)\n", d.LLMUsagePricing.PriceUnit))
	}
	b.WriteString("\n")

	b.WriteString(style.Subtitle("Tools & Features"))
	b.WriteString("\n")
	if d.EmbeddingEnabled {
		b.WriteString(fmt.Sprintf("  Embedding: provider=%s model=%s\n", d.EmbeddingProvider, d.EmbeddingModel))
	}
	if d.SearchEnabled {
		b.WriteString(fmt.Sprintf("  Search: provider=%s\n", d.SearchProvider))
	}
	if d.STTEnabled {
		b.WriteString(fmt.Sprintf("  STT: provider=%s model=%s\n", d.STTProvider, d.STTModel))
	}
	if d.VisionEnabled {
		b.WriteString(fmt.Sprintf("  Vision: provider=%s\n", d.VisionProvider))
	}
	if d.BrowserEnabled {
		b.WriteString(fmt.Sprintf("  Browser: download=%v playwright-url=%s\n", d.BrowserDownload, d.PlaywrightURL))
	}
	if d.ProxyEnabled {
		b.WriteString(fmt.Sprintf("  Proxy: http=%s socks=%s\n", d.HTTPProxy, d.SOCKSProxy))
	}
	if !d.EmbeddingEnabled && !d.SearchEnabled && !d.STTEnabled && !d.VisionEnabled && !d.BrowserEnabled && !d.ProxyEnabled {
		b.WriteString(style.Muted("  (not configured)\n"))
	}
	b.WriteString("\n")

	b.WriteString(style.Subtitle("Bot"))
	b.WriteString("\n")
	b.WriteString(fmt.Sprintf("  WS: %s\n", d.BotWS))
	b.WriteString(fmt.Sprintf("  Token: %s\n", maskString(d.BotToken)))
	if d.BotAdmins != "" {
		b.WriteString(fmt.Sprintf("  Admins: %s\n", d.BotAdmins))
	}
	b.WriteString("\n")

	b.WriteString(style.Subtitle("Groups"))
	b.WriteString("\n")
	b.WriteString(fmt.Sprintf("  Enabled Groups: %s\n", d.EnableGroups))
	b.WriteString("\n")

	b.WriteString(style.Subtitle("Core Authentication"))
	b.WriteString("\n")
	b.WriteString(fmt.Sprintf("  Username: %s\n", d.ServerUsername))
	b.WriteString(fmt.Sprintf("  Password: %s\n", maskString(d.ServerPassword)))

	b.WriteString("\n")
	b.WriteString(style.Title("Save configuration? (Y/n)"))

	return b.String()
}

// ---- Update helpers ----

func (m *Model) updateProviderSelect(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmd tea.Cmd
	m.list, cmd = m.list.Update(msg)

	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "enter":
			if item, ok := m.list.SelectedItem().(providerItem); ok {
				m.data.SelectedProv = item.index
				prov := m.data.Providers[item.index]
				if item.baseURL != "" {
					m.data.BaseURL = item.baseURL
				}
				// Populate model defaults for the selected provider
				if cfg, ok := m.data.LLMDefaults[item.key]; ok {
					if cfg.BaseURL != "" {
						m.data.BaseURL = cfg.BaseURL
					}
					m.data.ModelMode = cfg.ModelMode
					m.data.LiteModel = cfg.LiteModel
					m.data.FlashModel = cfg.FlashModel
					m.data.ProModel = cfg.ProModel
					m.data.AllModel = cfg.AllModel
					m.data.LLMSettings = copyStringMap(cfg.Settings)
					if cfg.Capability != nil {
						m.data.LLMCapability = mergeCapabilityDefaults(*cfg.Capability)
					}
					if cfg.UsagePricing != nil {
						m.data.LLMUsagePricing = mergeUsagePricingDefaults(*cfg.UsagePricing)
					}
				}
				if m.data.ModelMode == "" {
					m.data.ModelMode = "separate"
				}
				if m.data.LLMSettings == nil {
					m.data.LLMSettings = defaultSettingsForAPI(providerAPI(prov))
				}
				m.navigateTo(StepLLMConfig)
				m.rebuildCurrentStep()
				return m, m.currentInitCmd()
			}
		}
	}
	m.syncViewportContent()
	return m, cmd
}

func (m *Model) updateToolsMenu(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmd tea.Cmd
	m.list, cmd = m.list.Update(msg)

	switch msg := msg.(type) {
	case tea.KeyMsg:
		switch msg.String() {
		case "enter":
			if item, ok := m.list.SelectedItem().(toolItem); ok {
				if item.isDone {
					m.navigateTo(StepBot)
					m.rebuildCurrentStep()
					return m, m.currentInitCmd()
				}
				m.setToolEnabled(item.step)
				m.navigateTo(item.step)
				m.rebuildCurrentStep()
				return m, m.currentInitCmd()
			}
		}
	}
	m.syncViewportContent()
	return m, cmd
}

func (m *Model) updateLLMAdvancedAsk(msg tea.Msg) (tea.Model, tea.Cmd) {
	if m.form == nil {
		return m, nil
	}

	f, cmd := m.form.Update(msg)
	if f2, ok := f.(*huh.Form); ok {
		m.form = f2
	}

	if m.form.State == huh.StateCompleted {
		if m.data.AdvancedLLM {
			m.navigateTo(StepLLMProviderSettings)
		} else {
			m.navigateTo(StepToolsMenu)
		}
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}

	if m.form.State == huh.StateAborted {
		m.navigateBack()
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}

	m.syncViewportContent()
	return m, cmd
}

func (m Model) isLLMAdvancedStep() bool {
	return m.step.llmAdvancedTabPage() >= 0
}

func (m *Model) handleLLMTabKey(msg tea.KeyMsg) (bool, tea.Cmd) {
	if !m.isLLMAdvancedStep() {
		return false, nil
	}
	if !key.Matches(msg, m.llmTabs.KeyMap.PrevPage, m.llmTabs.KeyMap.NextPage) {
		return false, nil
	}

	if page := m.step.llmAdvancedTabPage(); page >= 0 {
		m.llmTabs.Page = page
	}

	var cmd tea.Cmd
	m.llmTabs, cmd = m.llmTabs.Update(msg)
	nextStep := llmAdvancedStepForPage(m.llmTabs.Page)
	if nextStep == m.step {
		m.syncViewportContent()
		return true, cmd
	}

	m.capabilityPager.Page = 0
	m.step = nextStep
	m.rebuildCurrentStep()
	return true, tea.Batch(cmd, m.currentInitCmd())
}

func (m *Model) handleCapabilityPageKey(msg tea.KeyMsg) (bool, tea.Cmd) {
	if !m.isLLMAdvancedStep() || !key.Matches(msg, m.keys.Page) {
		return false, nil
	}
	previousPage := m.capabilityPager.Page
	var cmd tea.Cmd
	m.capabilityPager, cmd = m.capabilityPager.Update(msg)
	if m.capabilityPager.Page == previousPage {
		return true, nil
	}
	m.rebuildCurrentStep()
	return true, tea.Batch(cmd, m.currentInitCmd())
}

func (m *Model) setToolEnabled(step Step) {
	switch step {
	case StepToolsEmbedding:
		m.data.EmbeddingEnabled = true
	case StepToolsSearch:
		m.data.SearchEnabled = true
	case StepToolsSTT:
		m.data.STTEnabled = true
	case StepToolsVision:
		m.data.VisionEnabled = true
	case StepToolsBrowser:
		m.data.BrowserEnabled = true
	case StepToolsProxy:
		m.data.ProxyEnabled = true
	default:
		panic("unhandled default case")
	}
}

func (m *Model) updateForm(msg tea.Msg, nextStep Step) (tea.Model, tea.Cmd) {
	if m.form == nil {
		return m, nil
	}

	f, cmd := m.form.Update(msg)
	if f2, ok := f.(*huh.Form); ok {
		m.form = f2
	}

	if m.syncProviderURL() {
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}

	if m.form.State == huh.StateCompleted {
		m.collectFormData()
		if isToolConfigurationStep(m.step) && nextStep == StepToolsMenu {
			m.returnToStep(nextStep)
		} else {
			m.navigateTo(nextStep)
		}
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}

	if m.form.State == huh.StateAborted {
		m.navigateBack()
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}

	m.syncViewportContent()
	return m, cmd
}

func (m *Model) updateCapabilityForm(msg tea.Msg, nextStep Step) (tea.Model, tea.Cmd) {
	if m.form == nil {
		return m, nil
	}
	f, cmd := m.form.Update(msg)
	if f2, ok := f.(*huh.Form); ok {
		m.form = f2
	}
	if m.form.State == huh.StateCompleted {
		if !m.capabilityPager.OnLastPage() {
			m.capabilityPager.NextPage()
			m.rebuildCurrentStep()
			return m, m.currentInitCmd()
		}
		m.navigateTo(nextStep)
		m.capabilityPager.Page = 0
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}
	if m.form.State == huh.StateAborted {
		m.navigateBack()
		m.rebuildCurrentStep()
		return m, m.currentInitCmd()
	}
	m.syncViewportContent()
	return m, cmd
}

func (m *Model) collectFormData() {
	// Data is bound via pointers in form builders — nothing to extract here.
}

func (m *Model) syncProviderURL() bool {
	switch m.step {
	case StepToolsEmbedding:
		if m.syncToolURL(m.data.ToolProviders.Embedding, &m.lastEmbeddingProvider, &m.data.EmbeddingProvider, &m.data.EmbeddingURL) {
			m.data.EmbeddingModel = defaultToolModel(m.data.ToolProviders.Embedding, m.data.EmbeddingProvider)
			return true
		}
	case StepToolsSearch:
		return m.syncToolURL(m.data.ToolProviders.Search, &m.lastSearchProvider, &m.data.SearchProvider, &m.data.SearchURL)
	case StepToolsSTT:
		if m.syncToolURL(m.data.ToolProviders.STT, &m.lastSTTProvider, &m.data.STTProvider, &m.data.STTURL) {
			m.data.STTModel = defaultToolModel(m.data.ToolProviders.STT, m.data.STTProvider)
			return true
		}
	case StepToolsVision:
		return m.syncToolURL(m.data.ToolProviders.Vision, &m.lastVisionProvider, &m.data.VisionProvider, &m.data.VisionURL)
	}
	return false
}

func (m *Model) syncToolURL(providers []ToolProvider, lastProv, curProv, curURL *string) bool {
	if *curProv == *lastProv {
		return false
	}
	lastDefault := ""
	if *lastProv != "" {
		lastDefault = defaultToolURL(providers, *lastProv)
	}
	shouldRebuild := false
	if *curURL == "" || *curURL == lastDefault {
		*curURL = defaultToolURL(providers, *curProv)
		shouldRebuild = true
	}
	*lastProv = *curProv
	return shouldRebuild
}

// ---- Form builders ----

func (m *Model) buildProviderList() {
	items := make([]list.Item, len(m.data.Providers))
	for i, p := range m.data.Providers {
		items[i] = providerItem{name: p.Name, key: p.Key, api: providerAPI(p), desc: p.Desc, baseURL: p.BaseURL, index: i}
	}
	delegate := style.StyleDelegate(list.NewDefaultDelegate())
	l := list.New(items, delegate, 0, 0)
	l.SetShowTitle(false)
	l.SetShowHelp(false)
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	m.list = l
}

func (m *Model) buildToolsList() {
	d := m.data
	items := []list.Item{
		toolItem{name: "Done →", isDone: true},
		toolItem{name: "Embedding", desc: d.toolStatus(StepToolsEmbedding), step: StepToolsEmbedding},
		toolItem{name: "Search", desc: d.toolStatus(StepToolsSearch), step: StepToolsSearch},
		toolItem{name: "Speech-to-Text", desc: d.toolStatus(StepToolsSTT), step: StepToolsSTT},
		toolItem{name: "Vision", desc: d.toolStatus(StepToolsVision), step: StepToolsVision},
		toolItem{name: "Browser", desc: d.toolStatus(StepToolsBrowser), step: StepToolsBrowser},
		toolItem{name: "Proxy", desc: d.toolStatus(StepToolsProxy), step: StepToolsProxy},
	}

	delegate := style.StyleDelegate(list.NewDefaultDelegate())
	l := list.New(items, delegate, 0, 0)
	l.SetShowTitle(false)
	l.SetShowHelp(false)
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	m.list = l
}

func (d *SetupData) toolStatus(step Step) string {
	switch step {
	case StepToolsEmbedding:
		if d.EmbeddingEnabled && d.EmbeddingAPIKey != "" {
			return "configured"
		}
	case StepToolsSearch:
		if d.SearchEnabled && d.SearchAPIKey != "" {
			return "configured"
		}
	case StepToolsSTT:
		if d.STTEnabled && d.STTAPIKey != "" {
			return "configured"
		}
	case StepToolsVision:
		if d.VisionEnabled && d.VisionAPIKey != "" {
			return "configured"
		}
	case StepToolsBrowser:
		if d.BrowserEnabled && (d.PlaywrightURL != "" || d.BrowserDownload) {
			return "configured"
		}
	case StepToolsProxy:
		if d.ProxyEnabled && (d.HTTPProxy != "" || d.SOCKSProxy != "") {
			return "configured"
		}
	}
	return "not configured"
}

func (m *Model) syncViewportContent() {
	timeline := renderTimeline(m.step.node(), m.data)
	content := m.renderContent()
	body := lipgloss.JoinVertical(lipgloss.Left, timeline, content)
	m.vp.SetContent(body)
	m.vp.GotoTop()
}

func (m *Model) rebuildCurrentStep() {
	m.vp.GotoTop()
	m.keys = m.currentKeys()
	if page := m.step.llmAdvancedTabPage(); page >= 0 {
		m.llmTabs.TotalPages = len(llmAdvancedTabDefs)
		m.llmTabs.Page = page
		m.capabilityPager.TotalPages = capabilityPageCount
	}
	switch m.step {
	case StepProviderSelect:
		m.form = nil
		m.buildProviderList()
	case StepLLMConfig:
		m.form = buildLLMForm(m.data)
	case StepLLMAdvancedAsk:
		m.form = buildLLMAdvancedAskForm(m.data)
	case StepLLMProviderSettings:
		m.form = buildLLMProviderSettingsForm(m.data, providerAPI(m.data.Providers[m.data.SelectedProv]))
	case StepLLMCapabilityDefault:
		m.form = buildLLMCapabilityForm("All", &m.data.LLMCapability.Default, m.capabilityPager)
	case StepLLMCapabilityLite:
		m.form = buildLLMCapabilityForm("Lite", &m.data.LLMCapability.Lite, m.capabilityPager)
	case StepLLMCapabilityFlash:
		m.form = buildLLMCapabilityForm("Flash", &m.data.LLMCapability.Flash, m.capabilityPager)
	case StepLLMCapabilityPro:
		m.form = buildLLMCapabilityForm("Pro", &m.data.LLMCapability.Pro, m.capabilityPager)
	case StepLLMUsagePricing:
		m.form = buildLLMUsagePricingForm(m.data)
	case StepToolsMenu:
		m.form = nil
		m.buildToolsList()
	case StepToolsEmbedding:
		m.lastEmbeddingProvider = m.data.EmbeddingProvider
		m.form = buildEmbeddingForm(m.data)
	case StepToolsSearch:
		m.lastSearchProvider = m.data.SearchProvider
		m.form = buildSearchForm(m.data)
	case StepToolsSTT:
		m.lastSTTProvider = m.data.STTProvider
		m.form = buildSTTForm(m.data)
	case StepToolsVision:
		m.lastVisionProvider = m.data.VisionProvider
		m.form = buildVisionForm(m.data)
	case StepToolsBrowser:
		m.form = buildBrowserForm(m.data)
	case StepToolsProxy:
		m.form = buildProxyForm(m.data)
	case StepBot:
		m.form = buildBotForm(m.data)
	case StepGroups:
		m.form = buildGroupsForm(m.data)
	case StepCoreAuth:
		m.form = buildCoreAuthForm(m.data)
	default:
		m.form = nil
	}
	m.syncSizes()
	m.syncViewportContent()
}

func (m *Model) syncSizes() {
	if m.width == 0 || m.height == 0 {
		return
	}
	m.help.Width = m.width

	listHeight := m.height - 12
	if m.step == StepProviderSelect || m.step == StepToolsMenu {
		// renderStepPage adds title + "\n\n" = 3 extra lines
		listHeight = m.height - 13
	}
	if listHeight < 5 {
		listHeight = 5
	}
	m.list.SetSize(m.width-4, listHeight)

	if m.form != nil {
		contentHeight := m.height - 13
		if contentHeight < 10 {
			contentHeight = 10
		}
		m.form.WithWidth(m.width)
		m.form.WithHeight(contentHeight)
	}
}

func (m *Model) currentInitCmd() tea.Cmd {
	if m.form != nil {
		return m.form.Init()
	}
	return nil
}

func (m *Model) nextEnabledTool() Step {
	d := m.data
	switch m.step {
	case StepToolsMenu:
		if d.EmbeddingEnabled {
			return StepToolsEmbedding
		}
		fallthrough
	case StepToolsEmbedding:
		if d.SearchEnabled {
			return StepToolsSearch
		}
		fallthrough
	case StepToolsSearch:
		if d.STTEnabled {
			return StepToolsSTT
		}
		fallthrough
	case StepToolsSTT:
		if d.VisionEnabled {
			return StepToolsVision
		}
		fallthrough
	case StepToolsVision:
		if d.BrowserEnabled {
			return StepToolsBrowser
		}
		fallthrough
	case StepToolsBrowser:
		if d.ProxyEnabled {
			return StepToolsProxy
		}
		fallthrough
	default:
		return StepBot
	}
}

// ---- Config write ----

func (m *Model) writeConfig() {
	m.writeErr = modifyConfig(m.data, path.AppFile)
	if m.writeErr != nil {
		return
	}
	m.writeErr = writeEnvLocal(m.data, path.EnvFile)
	m.wrote = (m.writeErr == nil)
}

// ---- Helpers ----

func maskString(s string) string {
	if s == "" {
		return "(empty)"
	}
	if len(s) <= 4 {
		return "****"
	}
	return s[:2] + "****" + s[len(s)-2:]
}
