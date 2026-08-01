package setup

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/charmbracelet/bubbles/paginator"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss"
)

// wrapForm creates a huh.Form with the unified theme and help disabled.
func wrapForm(groups ...*huh.Group) *huh.Form {
	return huh.NewForm(groups...).WithTheme(huhTheme())
}

func buildLLMForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("API Key").
				Value(&d.APIKey).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter API key (leave empty for env var)").
				Validate(func(s string) error {
					if s == "" {
						return validationError("API Key is required")
					}
					return nil
				}),
		).Title("Authentication").WithShowHelp(false),
		huh.NewGroup(
			huh.NewInput().
				Title("Base URL").
				Value(&d.BaseURL).
				Placeholder(placeholderOrValue(d.BaseURL)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Base URL is required")
					}
					return nil
				}),
			huh.NewSelect[string]().
				Title("Model configuration mode").
				Options(
					huh.NewOption("Configure separately (lite / flash / pro)", "separate"),
					huh.NewOption("Use one model for all tiers", "all"),
				).
				Value(&d.ModelMode),
		).Title("Endpoint").WithShowHelp(false),
		huh.NewGroup(
			huh.NewInput().
				Title("Model (all tiers)").
				Value(&d.AllModel).
				Placeholder(placeholderOrValue(d.AllModel)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Model is required")
					}
					return nil
				}),
		).Title("Models").
			WithShowHelp(false).
			WithHideFunc(func() bool { return d.ModelMode != "all" }),

		huh.NewGroup(
			huh.NewInput().
				Title("Lite Model").
				Value(&d.LiteModel).
				Placeholder(placeholderOrValue(d.LiteModel)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Lite Model is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Flash Model").
				Value(&d.FlashModel).
				Placeholder(placeholderOrValue(d.FlashModel)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Flash Model is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Pro Model").
				Value(&d.ProModel).
				Placeholder(placeholderOrValue(d.ProModel)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Pro Model is required")
					}
					return nil
				}),
		).Title("Models").
			WithShowHelp(false).
			WithHideFunc(func() bool { return d.ModelMode != "separate" }),
	)
}

func buildLLMAdvancedAskForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewConfirm().
				Title("Configure advanced LLM settings?").
				Description("Provider paths, model capabilities, and usage pricing. Defaults will be used if disabled.").
				Affirmative("Yes").
				Negative("No").
				Value(&d.AdvancedLLM),
		).Title("Advanced").WithShowHelp(false),
	)
}

func buildLLMProviderSettingsForm(d *SetupData, api string) *huh.Form {
	if d.LLMSettings == nil {
		d.LLMSettings = defaultSettingsForAPI(api)
	}
	settings := defaultSettingsForAPI(api)
	for k, v := range d.LLMSettings {
		settings[k] = v
	}
	d.LLMSettings = settings

	fields := make([]huh.Field, 0, len(settings))
	for _, key := range settingsKeysForAPI(api) {
		k := key
		value := settings[k]
		fields = append(fields, huh.NewInput().
			Title(k).
			Value(&value).
			Placeholder(placeholderOrValue(d.LLMSettings[k])).
			Validate(func(s string) error {
				if s == "" {
					return validationError("%s is required", k)
				}
				d.LLMSettings[k] = s
				return nil
			}))
	}
	return wrapForm(huh.NewGroup(fields...).Title("Provider Settings").WithShowHelp(false))
}

const (
	capabilityPageSize   = 3
	capabilityFieldCount = 8
	capabilityPageCount  = (capabilityFieldCount + capabilityPageSize - 1) / capabilityPageSize
)

func buildLLMCapabilityForm(label string, c *LLMCapabilitySet, pager paginator.Model) *huh.Form {
	fields := capabilityFields(c)
	pager.SetTotalPages(len(fields))
	start, end := pager.GetSliceBounds(len(fields))
	title := fmt.Sprintf("%s Capabilities  %s", label, pager.View())
	return wrapForm(huh.NewGroup(fields[start:end]...).Title(title).WithShowHelp(false))
}

func capabilityFields(c *LLMCapabilitySet) []huh.Field {
	return []huh.Field{
		compactCapabilityConfirm("Completion", &c.Completion),
		compactCapabilityConfirm("Prompt Caching", &c.PromptCaching),
		compactCapabilityConfirm("Temperature", &c.Temperature),
		compactCapabilityConfirm("Tools", &c.Tools),
		compactCapabilityConfirm("Tool Choice", &c.ToolChoice),
		compactCapabilityConfirm("Multiple Choices", &c.MultipleChoices),
		compactCapabilityConfirm("Thinking", &c.Thinking),
		compactCapabilityConfirm("Vision Image", &c.VisionImage),
	}
}

func compactCapabilityConfirm(title string, value *bool) huh.Field {
	return &compactConfirm{
		Confirm: huh.NewConfirm().
			Title(title).
			Affirmative("Yes").
			Negative("No").
			Value(value),
		title: title,
		value: value,
	}
}

type compactConfirm struct {
	*huh.Confirm
	title   string
	value   *bool
	focused bool
	width   int
	height  int
	theme   *huh.Theme
}

func (c *compactConfirm) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	model, cmd := c.Confirm.Update(msg)
	if confirm, ok := model.(*huh.Confirm); ok {
		c.Confirm = confirm
	}
	return c, cmd
}

func (c *compactConfirm) View() string {
	theme := c.theme
	if theme == nil {
		theme = huhTheme()
	}
	styles := theme.Blurred
	if c.focused {
		styles = theme.Focused
	}

	yesStyle := compactButtonStyle(styles.BlurredButton)
	noStyle := compactButtonStyle(styles.BlurredButton)
	if *c.value {
		yesStyle = compactButtonStyle(styles.FocusedButton)
	} else {
		noStyle = compactButtonStyle(styles.FocusedButton)
	}

	content := styles.Title.Render(c.title) + "\n" +
		yesStyle.Render("Yes") + "     " + noStyle.Render("No")
	base := styles.Base
	if c.width > 0 {
		base = base.Width(c.width)
	}
	if c.height > 0 {
		base = base.Height(c.height)
	}
	return base.Render(content)
}

func compactButtonStyle(s lipgloss.Style) lipgloss.Style {
	return s.UnsetBackground().UnsetPadding().UnsetMargins()
}

func (c *compactConfirm) Focus() tea.Cmd {
	c.focused = true
	return c.Confirm.Focus()
}

func (c *compactConfirm) Blur() tea.Cmd {
	c.focused = false
	return c.Confirm.Blur()
}

func (c *compactConfirm) WithTheme(theme *huh.Theme) huh.Field {
	c.theme = theme
	c.Confirm.WithTheme(theme)
	return c
}

func (c *compactConfirm) WithWidth(width int) huh.Field {
	c.width = width
	c.Confirm.WithWidth(width)
	return c
}

func (c *compactConfirm) WithHeight(height int) huh.Field {
	c.height = height
	c.Confirm.WithHeight(height)
	return c
}

func buildLLMUsagePricingForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("Price Unit").
				Value(&d.LLMUsagePricing.PriceUnit).
				Placeholder(placeholderOrValue(d.LLMUsagePricing.PriceUnit)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("Price Unit is required")
					}
					return nil
				}),
		).Title("Currency").WithShowHelp(false),
		pricingGroup("Lite Pricing", &d.LLMUsagePricing.Lite),
		pricingGroup("Flash Pricing", &d.LLMUsagePricing.Flash),
		pricingGroup("Pro Pricing", &d.LLMUsagePricing.Pro),
	)
}

func pricingGroup(title string, p *LLMPricingTierDefaults) *huh.Group {
	inputHit := fmt.Sprintf("%g", p.InputCacheHit)
	inputMiss := fmt.Sprintf("%g", p.InputCacheMiss)
	output := fmt.Sprintf("%g", p.Output)
	return huh.NewGroup(
		huh.NewInput().
			Title("Input Cache Hit").
			Value(&inputHit).
			Validate(func(s string) error {
				v, err := parseRequiredFloat("Input Cache Hit", s)
				if err != nil {
					return err
				}
				p.InputCacheHit = v
				return nil
			}).
			Key(title+"-input-hit"),
		huh.NewInput().
			Title("Input Cache Miss").
			Value(&inputMiss).
			Validate(func(s string) error {
				v, err := parseRequiredFloat("Input Cache Miss", s)
				if err != nil {
					return err
				}
				p.InputCacheMiss = v
				return nil
			}).
			Key(title+"-input-miss"),
		huh.NewInput().
			Title("Output").
			Value(&output).
			Validate(func(s string) error {
				v, err := parseRequiredFloat("Output", s)
				if err != nil {
					return err
				}
				p.Output = v
				return nil
			}).
			Key(title+"-output"),
	).Title(title).WithShowHelp(false)
}

func buildEmbeddingForm(d *SetupData) *huh.Form {
	if d.EmbeddingProvider == "" && len(d.ToolProviders.Embedding) > 0 {
		d.EmbeddingProvider = d.ToolProviders.Embedding[0].Name
	}
	if d.EmbeddingURL == "" {
		d.EmbeddingURL = defaultToolURL(d.ToolProviders.Embedding, d.EmbeddingProvider)
	}
	if d.EmbeddingModel == "" {
		d.EmbeddingModel = defaultToolModel(d.ToolProviders.Embedding, d.EmbeddingProvider)
	}
	return wrapForm(
		huh.NewGroup(
			huh.NewSelect[string]().
				Title("Provider").
				Options(buildToolProviderOptions(d.ToolProviders.Embedding)...).
				Value(&d.EmbeddingProvider),
			huh.NewInput().
				Title("API Key").
				Value(&d.EmbeddingAPIKey).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter API key"),
			huh.NewInput().
				Title("URL").
				Value(&d.EmbeddingURL).
				Placeholder(placeholderOrValue(d.EmbeddingURL)),
			huh.NewInput().
				Title("Model").
				Value(&d.EmbeddingModel).
				Placeholder(placeholderOrValue(d.EmbeddingModel)),
		).WithShowHelp(false),
	)
}

func buildSearchForm(d *SetupData) *huh.Form {
	if d.SearchProvider == "" && len(d.ToolProviders.Search) > 0 {
		d.SearchProvider = d.ToolProviders.Search[0].Name
	}
	if d.SearchURL == "" {
		d.SearchURL = defaultToolURL(d.ToolProviders.Search, d.SearchProvider)
	}
	return wrapForm(
		huh.NewGroup(
			huh.NewSelect[string]().
				Title("Provider").
				Options(buildToolProviderOptions(d.ToolProviders.Search)...).
				Value(&d.SearchProvider),
			huh.NewInput().
				Title("API Key").
				Value(&d.SearchAPIKey).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter API key"),
			huh.NewInput().
				Title("URL").
				Value(&d.SearchURL).
				Placeholder(placeholderOrValue(d.SearchURL)),
		).WithShowHelp(false),
	)
}

func buildSTTForm(d *SetupData) *huh.Form {
	if d.STTProvider == "" && len(d.ToolProviders.STT) > 0 {
		d.STTProvider = d.ToolProviders.STT[0].Name
	}
	if d.STTURL == "" {
		d.STTURL = defaultToolURL(d.ToolProviders.STT, d.STTProvider)
	}
	if d.STTModel == "" {
		d.STTModel = defaultToolModel(d.ToolProviders.STT, d.STTProvider)
	}
	return wrapForm(
		huh.NewGroup(
			huh.NewSelect[string]().
				Title("Provider").
				Options(buildToolProviderOptions(d.ToolProviders.STT)...).
				Value(&d.STTProvider),
			huh.NewInput().
				Title("API Key").
				Value(&d.STTAPIKey).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter API key"),
			huh.NewInput().
				Title("URL").
				Value(&d.STTURL).
				Placeholder(placeholderOrValue(d.STTURL)),
			huh.NewInput().
				Title("Resource ID").
				Value(&d.STTModel).
				Placeholder(placeholderOrValue(d.STTModel)),
		).WithShowHelp(false),
	)
}

func buildVisionForm(d *SetupData) *huh.Form {
	if d.VisionProvider == "" && len(d.ToolProviders.Vision) > 0 {
		d.VisionProvider = d.ToolProviders.Vision[0].Name
	}
	if d.VisionURL == "" {
		d.VisionURL = defaultToolURL(d.ToolProviders.Vision, d.VisionProvider)
	}
	return wrapForm(
		huh.NewGroup(
			huh.NewSelect[string]().
				Title("Provider").
				Options(buildToolProviderOptions(d.ToolProviders.Vision)...).
				Value(&d.VisionProvider),
			huh.NewInput().
				Title("API Key").
				Value(&d.VisionAPIKey).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter API key"),
			huh.NewInput().
				Title("URL").
				Value(&d.VisionURL).
				Placeholder(placeholderOrValue(d.VisionURL)),
		).WithShowHelp(false),
	)
}

func buildBrowserForm(d *SetupData) *huh.Form {
	if d.PlaywrightURL == "" && len(d.ToolProviders.Browser) > 0 {
		d.PlaywrightURL = d.ToolProviders.Browser[0].URL
	}
	return wrapForm(
		huh.NewGroup(
			huh.NewSelect[bool]().
				Title("Download").
				Options(huh.NewOption("Disable", false), huh.NewOption("Enable", true)).
				Value(&d.BrowserDownload),
			huh.NewInput().
				Title("Playwright URL").
				Value(&d.PlaywrightURL).
				Placeholder(placeholderOrValue(d.PlaywrightURL)),
			huh.NewInput().
				Title("External Host").
				Value(&d.ExternalHost).
				Placeholder(placeholderOrValue(d.ExternalHost)),
		).WithShowHelp(false),
	)
}

func buildProxyForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("HTTP Proxy").
				Value(&d.HTTPProxy).
				Placeholder(placeholderOrValue(d.HTTPProxy)),
			huh.NewInput().
				Title("SOCKS Proxy").
				Value(&d.SOCKSProxy).
				Placeholder(placeholderOrValue(d.SOCKSProxy)),
		).WithShowHelp(false),
	)
}

func buildBotForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("WebSocket Address").
				Value(&d.BotWS).
				Placeholder(placeholderOrValue(d.BotWS)).
				Validate(func(s string) error {
					if s == "" {
						return validationError("WebSocket Address is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Token").
				Value(&d.BotToken).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter Onebot token").
				Validate(func(s string) error {
					if s == "" {
						return validationError("Token is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Admins (comma-separated, optional)").
				Value(&d.BotAdmins).
				Placeholder(placeholderOrValue(d.BotAdmins)),
		).WithShowHelp(false),
	)
}

func buildGroupsForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("Enabled Groups (comma-separated, optional)").
				Value(&d.EnableGroups).
				Placeholder(placeholderOrValue(d.EnableGroups)).
				Validate(validateCommaSeparatedNumbers("group ID")),
		).WithShowHelp(false),
	)
}

func buildCoreAuthForm(d *SetupData) *huh.Form {
	return wrapForm(
		huh.NewGroup(
			huh.NewInput().
				Title("Username").
				Value(&d.ServerUsername).
				Placeholder(defaultServerUsername).
				Validate(func(s string) error {
					if strings.TrimSpace(s) == "" {
						return validationError("Username is required")
					}
					return nil
				}),
			huh.NewInput().
				Title("Password").
				Value(&d.ServerPassword).
				EchoMode(huh.EchoModePassword).
				Placeholder("Enter Core server password").
				Validate(func(s string) error {
					if s == "" {
						return validationError("Password is required")
					}
					return nil
				}),
		).WithShowHelp(false),
	)
}

// splitByCommas splits a string by both English (,) and Chinese (，) commas.
func splitByCommas(s string) []string {
	return strings.Split(strings.ReplaceAll(s, "，", ","), ",")
}

func validateCommaSeparatedNumbers(label string) func(string) error {
	return func(s string) error {
		if s == "" {
			return nil
		}
		parts := splitByCommas(s)
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if p == "" {
				return validationError("empty %s in list", label)
			}
			for _, r := range p {
				if r < '0' || r > '9' {
					return validationError("invalid %s: %q (must be numeric)", label, p)
				}
			}
		}
		return nil
	}
}

func validateFloat(label string) func(string) error {
	return func(s string) error {
		_, err := parseRequiredFloat(label, s)
		return err
	}
}

func parseRequiredFloat(label string, s string) (float64, error) {
	if s == "" {
		return 0, validationError("%s is required", label)
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, validationError("%s must be a number", label)
	}
	return v, nil
}

func validationError(format string, args ...any) error {
	return fmt.Errorf(format, args...)
}

// ---- Helpers ----

func buildToolProviderOptions(providers []ToolProvider) []huh.Option[string] {
	opts := make([]huh.Option[string], len(providers))
	for i, tp := range providers {
		opts[i] = huh.NewOption(fmt.Sprintf("%s  |  %s", tp.Name, tp.URL), tp.Name)
	}
	return opts
}

func defaultToolURL(providers []ToolProvider, selected string) string {
	for _, tp := range providers {
		if tp.Name == selected {
			return tp.URL
		}
	}
	if len(providers) > 0 {
		return providers[0].URL
	}
	return ""
}

func defaultToolModel(providers []ToolProvider, selected string) string {
	for _, tp := range providers {
		if tp.Name == selected {
			return tp.Model
		}
	}
	if len(providers) > 0 {
		return providers[0].Model
	}
	return ""
}

func placeholderOrValue(v string) string {
	if v == "" {
		return "(empty)"
	}
	return v
}

func defaultSettingsForAPI(api string) map[string]string {
	switch strings.ToLower(api) {
	case "anthropic":
		return map[string]string{
			"api-version": "2023-06-01",
			"messages":    "v1/messages",
			"models":      "v1/models",
		}
	default:
		return map[string]string{
			"chat-completions": "v1/chat/completions",
			"responses-api":    "v1/responses",
			"embeddings":       "v1/embeddings",
			"moderations":      "v1/moderations",
			"models":           "v1/models",
		}
	}
}

func settingsKeysForAPI(api string) []string {
	switch strings.ToLower(api) {
	case "anthropic":
		return []string{"api-version", "messages", "models"}
	default:
		return []string{"chat-completions", "responses-api", "embeddings", "moderations", "models"}
	}
}

func copyStringMap(in map[string]string) map[string]string {
	out := make(map[string]string, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}
