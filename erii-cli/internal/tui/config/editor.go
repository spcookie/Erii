package config

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"erii-cli/internal/config/tree"
	"erii-cli/internal/path"
	"erii-cli/internal/tui/components"
	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/huh"
)

// EditorKeyMap defines keybindings for the leaf editor.
type EditorKeyMap struct {
	Back    key.Binding
	PickEnv key.Binding
	Enter   key.Binding
	Quit    key.Binding
	Null    key.Binding
}

func (k EditorKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.Back, k.PickEnv, k.Enter, k.Null, k.Quit}
}

func (k EditorKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.PickEnv, k.Enter},
		{k.Back, k.Null, k.Quit},
	}
}

var DefaultEditorKeys = EditorKeyMap{
	Back: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "cancel"),
	),
	PickEnv: key.NewBinding(
		key.WithKeys("ctrl+e"),
		key.WithHelp("ctrl+e", "pick from env"),
	),
	Enter: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "submit"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	Null: key.NewBinding(
		key.WithKeys("ctrl+n"),
		key.WithHelp("ctrl+n", "set null"),
	),
}

// envItem represents an env variable for the picker.
type envItem struct {
	key   string
	value string
}

func (i envItem) Title() string       { return i.key }
func (i envItem) Description() string { return i.value }
func (i envItem) FilterValue() string { return i.key + " " + i.value }

// LeafEditorModel edits a single leaf node using huh fields.
type LeafEditorModel struct {
	leaf          *tree.LeafNode
	width         int
	height        int
	keys          EditorKeyMap
	help          help.Model
	form          *huh.Form
	formValue     string
	formValueBool bool
	envList       list.Model
	pickingEnv    bool
	onSave        func() tea.Cmd
	quitting      bool
	errMsg        string
	isNullMark    bool   // true when user set value to null via ctrl+n
	originalValue string // tracks the original string value
	originalBool  bool   // tracks the original bool value
}

func NewLeafEditorModel(leaf *tree.LeafNode, onSave func() tea.Cmd) *LeafEditorModel {
	m := &LeafEditorModel{
		leaf:   leaf,
		keys:   DefaultEditorKeys,
		help:   help.New(),
		onSave: onSave,
	}
	m.formValue = leafValueToString(leaf)
	m.originalValue = m.formValue // remember original value

	// If leaf was saved as null, set isNullMark to true
	if leaf.IsNull() {
		m.isNullMark = true
		m.formValue = ""
	}

	// If leaf value is zero/nil/empty and has a default, use the default
	if m.shouldApplyDefault() {
		switch leaf.ValueConfig().Type {
		case "string", "enum":
			if str, ok := leaf.ValueConfig().Default.(string); ok {
				m.formValue = str
			}
		case "number":
			if f, ok := leaf.ValueConfig().Default.(float64); ok {
				m.formValue = formatNumberValue(f)
			}
		case "boolean":
			if b, ok := leaf.ValueConfig().Default.(bool); ok {
				m.formValueBool = b
			}
		case "array":
			if arr, ok := leaf.ValueConfig().Default.([]any); ok {
				var items []string
				for _, a := range arr {
					items = append(items, fmt.Sprintf("%v", a))
				}
				m.formValue = strings.Join(items, "\n")
			}
		}
	}

	if v, ok := leaf.Value().(bool); ok {
		m.formValueBool = v
		m.originalBool = v // remember original bool value
	}
	m.buildForm()
	// Set default dimensions so form renders correctly on first frame
	m.width = 80
	m.height = 24
	if m.form != nil {
		m.form = m.form.WithWidth(60)
	}
	return m
}

// shouldApplyDefault returns true if leaf value is zero and should use default
func (m *LeafEditorModel) shouldApplyDefault() bool {
	// Don't apply default if user explicitly set value to null
	if m.isNullMark {
		return false
	}
	vc := m.leaf.ValueConfig()
	if vc == nil || vc.Default == nil {
		return false
	}
	// Check if current value is "zero"
	switch m.leaf.ValueType() {
	case tree.TypeString, tree.TypeText, tree.TypeEnum:
		return m.formValue == ""
	case tree.TypeNumber:
		return m.formValue == ""
	case tree.TypeArray:
		v := m.leaf.Value()
		if v == nil {
			return true
		}
		switch arr := v.(type) {
		case []string:
			return len(arr) == 0
		case []any:
			return len(arr) == 0
		}
	case tree.TypeBool:
		return false // don't auto-apply default for bool
	}
	return false
}

// hasChanges returns true if the form value differs from the original
func (m *LeafEditorModel) placeholder() string {
	if m.isNullMark {
		return "(null)"
	}
	if m.formValue == "" {
		if vc := m.leaf.ValueConfig(); vc != nil && vc.Default != nil {
			if m.leaf.ValueType() == tree.TypeNumber {
				return fmt.Sprintf("(default: %s)", formatNumberValue(vc.Default))
			}
			return fmt.Sprintf("(default: %v)", vc.Default)
		}
		return "(empty)"
	}
	return ""
}

func (m *LeafEditorModel) hasChanges() bool {
	switch m.leaf.ValueType() {
	case tree.TypeString, tree.TypeText, tree.TypeNumber, tree.TypeEnum:
		return m.formValue != m.originalValue
	case tree.TypeBool:
		return m.formValueBool != m.originalBool
	case tree.TypeArray:
		origItems := strings.Split(m.originalValue, "\n")
		currItems := strings.Split(m.formValue, "\n")
		if len(origItems) != len(currItems) {
			return true
		}
		for i := range origItems {
			if strings.TrimSpace(origItems[i]) != strings.TrimSpace(currItems[i]) {
				return true
			}
		}
		return false
	}
	return false
}

// shouldApplyDefaultOnSave returns true if current value is empty and should apply default on save
func (m *LeafEditorModel) shouldApplyDefaultOnSave() bool {
	// Don't apply default if user explicitly set value to null
	if m.isNullMark {
		return false
	}
	vc := m.leaf.ValueConfig()
	if vc == nil || vc.Default == nil {
		return false
	}
	switch m.leaf.ValueType() {
	case tree.TypeString, tree.TypeText, tree.TypeEnum:
		return m.formValue == ""
	case tree.TypeNumber:
		return m.formValue == ""
	case tree.TypeArray:
		lines := strings.Split(m.formValue, "\n")
		// Check if all lines are empty
		for _, line := range lines {
			if strings.TrimSpace(line) != "" {
				return false
			}
		}
		return true
	}
	return false
}

func leafValueToString(leaf *tree.LeafNode) string {
	switch leaf.ValueType() {
	case tree.TypeNumber:
		if v := leaf.Value(); v != nil {
			return formatNumberValue(v)
		}
	case tree.TypeString, tree.TypeText:
		if v := leaf.Value(); v != nil {
			return fmt.Sprintf("%v", v)
		}
	case tree.TypeEnum:
		if v, ok := leaf.Value().(string); ok {
			return v
		}
	case tree.TypeArray:
		switch v := leaf.Value().(type) {
		case []string:
			return strings.Join(v, "\n")
		case []any:
			var items []string
			for _, a := range v {
				items = append(items, fmt.Sprintf("%v", a))
			}
			return strings.Join(items, "\n")
		}
	}
	return ""
}

func (m *LeafEditorModel) buildForm() {
	var fields []huh.Field

	switch m.leaf.ValueType() {
	case tree.TypeString:
		fields = append(fields, huh.NewInput().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Placeholder(m.placeholder()).
			Value(&m.formValue).
			Key("value"))

	case tree.TypeText:
		fields = append(fields, huh.NewText().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Placeholder(m.placeholder()).
			Value(&m.formValue).
			Key("value"))

	case tree.TypeNumber:
		fields = append(fields, huh.NewInput().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Placeholder(m.placeholder()).
			Value(&m.formValue).
			Key("value"))

	case tree.TypeBool:
		fields = append(fields, huh.NewConfirm().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Value(&m.formValueBool).
			Key("value"))

	case tree.TypeEnum:
		var opts []huh.Option[string]
		for _, o := range m.leaf.Options() {
			opts = append(opts, huh.NewOption(o, o))
		}
		fields = append(fields, huh.NewSelect[string]().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Options(opts...).
			Value(&m.formValue).
			Key("value"))

	case tree.TypeArray:
		placeholder := ""
		if m.isNullMark {
			placeholder = "(null)"
		} else if m.formValue == "" {
			if vc := m.leaf.ValueConfig(); vc != nil && vc.Default != nil {
				placeholder = fmt.Sprintf("(default: %v)", vc.Default)
			} else {
				placeholder = "(empty)"
			}
		}
		fields = append(fields, huh.NewText().
			Title(m.leaf.Title()).
			Description("Enter values, one per line").
			Placeholder(placeholder).
			Value(&m.formValue).
			Key("value"))

	case tree.TypeObject:
		fields = append(fields, huh.NewText().
			Title(m.leaf.Title()).
			Description(m.leaf.Description()).
			Value(&m.formValue).
			Key("value"))
	}

	if len(fields) > 0 {
		m.form = huh.NewForm(huh.NewGroup(fields...)).
			WithWidth(60).
			WithShowHelp(false).
			WithTheme(style.HuhTheme())
	}
}

func (m *LeafEditorModel) loadEnvValues() []envItem {
	data, err := os.ReadFile(path.EnvFile)
	if err != nil {
		return nil
	}
	var result []envItem
	scanner := bufio.NewScanner(strings.NewReader(string(data)))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		idx := strings.IndexByte(line, '=')
		if idx == -1 {
			continue
		}
		space := strings.TrimSpace(line[:idx])
		val := strings.TrimSpace(line[idx+1:])
		if space != "" {
			result = append(result, envItem{key: space, value: val})
		}
	}
	return result
}

func (m *LeafEditorModel) initEnvList() {
	if m.envList.Items() != nil {
		return
	}
	envItems := m.loadEnvValues()
	items := make([]list.Item, 0, len(envItems))
	for _, it := range envItems {
		items = append(items, it)
	}

	delegate := style.StyleDelegate(list.NewDefaultDelegate())

	l := list.New(items, delegate, 0, 0)
	l.Title = style.Title("Pick env value")
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(true)
	l.SetShowHelp(false)
	l.Styles.Title = style.ListTitle
	if m.width > 0 && m.height > 0 {
		l.SetSize(m.width, m.height-4)
	}
	m.envList = l
}

func (m *LeafEditorModel) pickEnvValue(key string) tea.Cmd {
	m.pickingEnv = false
	m.formValue = "${" + key + "}"
	m.leaf.SetEnvRef(true)
	m.buildForm()
	if m.form != nil {
		if m.width > 0 {
			m.form = m.form.WithWidth(min(60, m.width-8))
		}
		return m.form.Init()
	}
	return nil
}

func (m *LeafEditorModel) Init() tea.Cmd {
	if m.form != nil {
		return m.form.Init()
	}
	return nil
}

func (m *LeafEditorModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.help.Width = msg.Width
		if m.form != nil {
			m.form = m.form.WithWidth(min(60, msg.Width-8))
			_, cmd := m.form.Update(msg)
			if m.envList.Items() != nil {
				m.envList.SetSize(msg.Width, msg.Height-4)
			}
			return m, cmd
		}
		if m.envList.Items() != nil {
			m.envList.SetSize(msg.Width, msg.Height-4)
		}
		return m, nil

	case tea.KeyMsg:
		if m.pickingEnv {
			return m.handleEnvKey(msg)
		}
		// Check for ctrl+n first - before form processes it
		if msg.Type == tea.KeyCtrlN {
			// Toggle null state if nullable
			vc := m.leaf.ValueConfig()
			if vc != nil && vc.Nullable {
				m.isNullMark = !m.isNullMark
				if m.isNullMark {
					m.formValue = ""
				}
				m.buildForm()
				if m.form != nil {
					return m, m.form.Init()
				}
			}
			return m, nil
		}
		if key.Matches(msg, m.keys.Quit) {
			m.quitting = true
			return m, tea.Quit
		}
		if key.Matches(msg, m.keys.Back) {
			return m, func() tea.Msg { return components.PopScreenMsg{} }
		}
		if key.Matches(msg, m.keys.PickEnv) {
			// Only for string/text/number types
			vt := m.leaf.ValueType()
			if vt == tree.TypeString || vt == tree.TypeText || vt == tree.TypeNumber {
				m.pickingEnv = true
				m.initEnvList()
			}
			return m, nil
		}
		if key.Matches(msg, m.keys.Null) {
			// Toggle null state if nullable
			if vc := m.leaf.ValueConfig(); vc != nil && vc.Nullable {
				m.isNullMark = !m.isNullMark
				if m.isNullMark {
					m.formValue = ""
				}
				m.buildForm()
				if m.form != nil {
					return m, m.form.Init()
				}
			}
			return m, nil
		}
		// Enter key saves and exits (except for Text/Array types where Enter is newline)
		if key.Matches(msg, m.keys.Enter) {
			if m.leaf.ValueType() != tree.TypeText && m.leaf.ValueType() != tree.TypeArray {
				return m, m.saveAndPopIfChanged()
			}
		}
	}

	if m.form != nil {
		newForm, cmd := m.form.Update(msg)
		if f, ok := newForm.(*huh.Form); ok {
			m.form = f
		}
		if m.form.State == huh.StateCompleted {
			return m, m.saveAndPopIfChanged()
		}
		return m, cmd
	}

	return m, nil
}

// saveAndPopIfChanged saves value if changed and returns pop command.
func (m *LeafEditorModel) saveAndPopIfChanged() tea.Cmd {
	if m.hasChanges() || m.shouldApplyDefaultOnSave() || m.isNullMark {
		m.saveValue()
	}
	var cmds []tea.Cmd
	if m.onSave != nil && (m.hasChanges() || m.shouldApplyDefaultOnSave() || m.isNullMark) {
		cmds = append(cmds, m.onSave())
	}
	cmds = append(cmds, func() tea.Msg { return components.PopScreenMsg{} })
	return tea.Batch(cmds...)
}

func (m *LeafEditorModel) handleEnvKey(msg tea.KeyMsg) (tea.Model, tea.Cmd) {
	switch msg.String() {
	case "esc", "q", "ctrl+c":
		m.pickingEnv = false
		return m, nil
	case "enter":
		if item, ok := m.envList.SelectedItem().(envItem); ok {
			return m, m.pickEnvValue(item.key)
		}
		return m, nil
	}
	var cmd tea.Cmd
	m.envList, cmd = m.envList.Update(msg)
	return m, cmd
}

func (m *LeafEditorModel) saveValue() {
	switch m.leaf.ValueType() {
	case tree.TypeObject:
		if m.formValue == "" && m.shouldApplyDefault() {
			if obj, ok := m.leaf.ValueConfig().Default.(map[string]any); ok {
				m.leaf.SetValue(obj)
				m.leaf.SetNull(false)
			}
		} else {
			m.leaf.SetValue(m.formValue)
		}
	case tree.TypeString, tree.TypeText:
		// If was null and user entered non-empty value, clear null state
		if m.isNullMark && m.formValue != "" {
			m.isNullMark = false
		}
		if !m.isNullMark {
			// If value is empty and has a default, use the default
			if m.formValue == "" && m.shouldApplyDefault() {
				if str, ok := m.leaf.ValueConfig().Default.(string); ok {
					m.leaf.SetValue(str)
					m.leaf.SetNull(false)
				} else {
					m.leaf.SetValue("")
					m.leaf.SetNull(false)
				}
			} else {
				m.leaf.SetValue(m.formValue)
				m.leaf.SetNull(false)
			}
		} else {
			// isNullMark is true and formValue is empty - set to null
			m.leaf.SetValue(nil)
			m.leaf.SetNull(true)
			m.leaf.SetEnvRef(false)
		}

	case tree.TypeNumber:
		// If value is empty and has a default, use the default
		if m.formValue == "" && m.shouldApplyDefault() {
			if f, ok := m.leaf.ValueConfig().Default.(float64); ok {
				m.leaf.SetValue(f)
			} else {
				m.leaf.SetValue(0)
			}
		} else if f, err := strconv.ParseFloat(m.formValue, 64); err == nil {
			m.leaf.SetValue(f)
		} else {
			m.leaf.SetValue(m.formValue)
		}

	case tree.TypeBool:
		m.leaf.SetValue(m.formValueBool)

	case tree.TypeEnum:
		m.leaf.SetValue(m.formValue)

	case tree.TypeArray:
		if m.isNullMark && m.formValue != "" {
			m.isNullMark = false
		}
		if !m.isNullMark {
			// If value is empty and has a default, use the default
			if m.formValue == "" && m.shouldApplyDefault() {
				if arr, ok := m.leaf.ValueConfig().Default.([]any); ok {
					var items []string
					for _, a := range arr {
						items = append(items, fmt.Sprintf("%v", a))
					}
					m.leaf.SetValue(items)
				} else {
					m.leaf.SetValue([]string{})
				}
				m.leaf.SetNull(false)
			} else {
				lines := strings.Split(m.formValue, "\n")
				var items []string
				for _, line := range lines {
					line = strings.TrimSpace(line)
					if line != "" {
						items = append(items, line)
					}
				}
				m.leaf.SetValue(items)
				m.leaf.SetNull(false)
			}
		} else {
			m.leaf.SetValue(nil)
			m.leaf.SetNull(true)
			m.leaf.SetEnvRef(false)
		}
	}
}

// envPickKeyMap is shown in the env picker help bar.
type envPickKeyMap struct {
	Select key.Binding
	Back   key.Binding
}

func (k envPickKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.Select, k.Back}
}

func (k envPickKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{{k.Select}, {k.Back}}
}

var envPickKeys = envPickKeyMap{
	Select: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "select"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "cancel"),
	),
}

func (m *LeafEditorModel) ShortHelp() []key.Binding {
	bindings := []key.Binding{m.keys.Back, m.keys.Enter, m.keys.Quit}
	if vt := m.leaf.ValueType(); vt == tree.TypeString || vt == tree.TypeText || vt == tree.TypeNumber {
		bindings = append(bindings, m.keys.PickEnv)
	}
	m.appendTypeBindings(&bindings)
	return bindings
}

func (m *LeafEditorModel) FullHelp() [][]key.Binding {
	var first []key.Binding
	first = append(first, m.keys.Enter)
	if vt := m.leaf.ValueType(); vt != tree.TypeEnum && vt != tree.TypeBool {
		first = append(first, m.keys.PickEnv)
	}
	m.appendTypeBindings(&first)
	return [][]key.Binding{
		first,
		{m.keys.Back, m.keys.Quit},
	}
}

// appendTypeBindings appends type-specific key bindings to the given slice.
func (m *LeafEditorModel) appendTypeBindings(bindings *[]key.Binding) {
	if vc := m.leaf.ValueConfig(); vc != nil && vc.Nullable {
		*bindings = append(*bindings, m.keys.Null)
	}
	if vt := m.leaf.ValueType(); vt == tree.TypeText || vt == tree.TypeArray {
		*bindings = append(*bindings, key.NewBinding(
			key.WithKeys("ctrl+j"),
			key.WithHelp("ctrl+j", "newline"),
		))
	}
	if vt := m.leaf.ValueType(); vt == tree.TypeEnum || vt == tree.TypeBool {
		*bindings = append(*bindings, key.NewBinding(
			key.WithKeys("j", "k", "up", "down"),
			key.WithHelp("j/k/↑/↓", "select"),
		))
	}
}

func (m *LeafEditorModel) View() string {
	if m.quitting {
		return ""
	}

	if m.pickingEnv {
		return m.envList.View() + "\n\n" + m.help.View(envPickKeys)
	}

	var b strings.Builder
	b.WriteString(style.Title("Edit: "+m.leaf.Title()) + "\n\n")

	if m.form != nil {
		b.WriteString(m.form.View())
	} else {
		b.WriteString(style.ErrorText("Unsupported value type: " + m.leaf.ValueType().String()))
	}

	if m.errMsg != "" {
		b.WriteString("\n" + style.ErrorText(m.errMsg))
	}

	b.WriteString("\n\n" + m.help.View(m))

	return b.String()
}
