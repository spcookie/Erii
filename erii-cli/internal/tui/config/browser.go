package config

import (
	"fmt"
	"strings"

	"erii-cli/internal/config/tree"
	"erii-cli/internal/tui/components"
	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/bubbles/help"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/huh"
)

// BrowserKeyMap defines keybindings for the config browser.
type BrowserKeyMap struct {
	Up       key.Binding
	Down     key.Binding
	Enter    key.Binding
	Back     key.Binding
	New      key.Binding
	Rename   key.Binding
	Delete   key.Binding
	EditDesc     key.Binding
	Help         key.Binding
	Quit         key.Binding
	FormCancel   key.Binding
	FormNext     key.Binding
	FormPrev     key.Binding
	FormSubmit   key.Binding
}

func (k BrowserKeyMap) ShortHelp() []key.Binding {
	return []key.Binding{k.Up, k.Down, k.Enter, k.Back, k.Help, k.Quit}
}

func (k BrowserKeyMap) FullHelp() [][]key.Binding {
	return [][]key.Binding{
		{k.Up, k.Down, k.Enter},
		{k.New, k.Rename, k.Delete},
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
		key.WithHelp("enter", "open/edit"),
	),
	Back: key.NewBinding(
		key.WithKeys("esc", "left"),
		key.WithHelp("esc/←", "back"),
	),
	New: key.NewBinding(
		key.WithKeys("ctrl+n"),
		key.WithHelp("ctrl+n", "new item"),
	),
	Rename: key.NewBinding(
		key.WithKeys("ctrl+r"),
		key.WithHelp("ctrl+r", "rename"),
	),
	Delete: key.NewBinding(
		key.WithKeys("ctrl+d"),
		key.WithHelp("ctrl+d", "delete"),
	),
	EditDesc: key.NewBinding(
		key.WithKeys("ctrl+e"),
		key.WithHelp("ctrl+e", "edit desc"),
	),
	Help: key.NewBinding(
		key.WithKeys("?"),
		key.WithHelp("?", "toggle help"),
	),
	Quit: key.NewBinding(
		key.WithKeys("ctrl+c"),
		key.WithHelp("ctrl+c", "quit"),
	),
	FormCancel: key.NewBinding(
		key.WithKeys("esc"),
		key.WithHelp("esc", "cancel"),
	),
	FormNext: key.NewBinding(
		key.WithKeys("tab"),
		key.WithHelp("tab", "next field"),
	),
	FormPrev: key.NewBinding(
		key.WithKeys("shift+tab"),
		key.WithHelp("shift+tab", "prev field"),
	),
	FormSubmit: key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "submit"),
	),
}

// NodeItem wraps a ConfigNode for the list delegate.
type NodeItem struct {
	Node tree.ConfigNode
}

func (i NodeItem) Title() string {
	return i.Node.Title()
}

func (i NodeItem) Description() string {
	if !i.Node.IsLeaf() {
		description := strings.TrimSpace(i.Node.Description())
		if description != "" {
			return description
		}
		branch := i.Node.(*tree.BranchNode)
		if branch.IsArray() {
			return fmt.Sprintf("Array (%d items)", len(branch.Children()))
		}
		return "Object"
	}
	leaf := i.Node.(*tree.LeafNode)
	if leaf.IsNull() {
		return "(null)"
	}
	return formatLeafValue(leaf)
}

func formatLeafValue(leaf *tree.LeafNode) string {
	switch leaf.ValueType() {
	case tree.TypeArray:
		return formatArrayValue(leaf.Value())
	case tree.TypeObject:
		return "object"
	case tree.TypeNumber:
		if leaf.Value() == nil {
			return "(empty)"
		}
		return formatNumberValue(leaf.Value())
	case tree.TypeBool:
		if leaf.Value() == nil {
			return "(empty)"
		}
		return fmt.Sprintf("%v", leaf.Value())
	case tree.TypeText, tree.TypeString:
		return formatStringValue(leaf.Value())
	default:
		return formatStringValue(leaf.Value())
	}
}

func formatArrayValue(v any) string {
	switch val := v.(type) {
	case []string:
		return fmt.Sprintf("array[%d]", len(val))
	case []any:
		return fmt.Sprintf("array[%d]", len(val))
	default:
		return "array"
	}
}

func formatStringValue(v any) string {
	s := fmt.Sprintf("%v", v)
	if s == "" || s == "<nil>" {
		return "(empty)"
	}
	if len(s) > 40 {
		s = s[:40] + "..."
	}
	return s
}

func (i NodeItem) FilterValue() string { return i.Node.Title() }

// BrowserModel is a generic config file browser using list + node tree.
type BrowserModel struct {
	Root            tree.ConfigNode
	current         *tree.BranchNode
	stack           []*tree.BranchNode
	List            list.Model
	width           int
	height          int
	Keys            BrowserKeyMap
	help            help.Model
	onEdit          func(leaf *tree.LeafNode, onSave func() tea.Cmd)
	OnSaveFile      func(root tree.ConfigNode) error
	title           string
	pluginName      string
	errMsg          string
	adding          bool
	addTitle        string
	addDesc         string
	addType         string
	addForm         *huh.Form
	renaming        bool
	renameValue     string
	renameDesc      string
	renameForm      *huh.Form
	deleting        bool
	deleteConfirm   bool
	deleteForm      *huh.Form
	editingDesc     bool
	editDescValue   string
	editDescForm    *huh.Form
	editable        bool
	newItemFactory  func(title, desc string) tree.ConfigNode
}

func NewBrowserModel(root tree.ConfigNode, title string, onEdit func(leaf *tree.LeafNode, onSave func() tea.Cmd), OnSaveFile func(root tree.ConfigNode) error) *BrowserModel {
	branch, ok := root.(*tree.BranchNode)
	if !ok {
		// Wrap single leaf in a branch
		branch = tree.NewBranch("root", "Configuration")
		branch.AddChild(root)
	}

	m := &BrowserModel{
		Root:       root,
		current:    branch,
		stack:      []*tree.BranchNode{},
		Keys:       DefaultBrowserKeys,
		help:       help.New(),
		onEdit:     onEdit,
		OnSaveFile: OnSaveFile,
		title:      title,
		width:      80, // Default dimensions to avoid blank first render
		height:     24,
	}
	m.refreshList()
	return m
}

// WithPlugin sets the plugin context for metadata lookups.
func (m *BrowserModel) WithPlugin(name string) *BrowserModel {
	m.pluginName = name
	return m
}

func (m *BrowserModel) canModify() bool {
	return m.editable || m.current.IsArray() || tree.CanModify(m.pluginName, m.currentPath())
}

// canEditDesc reports whether description editing is available at the current path.
// Requires structured config context (copy.json patterns or object-typed parent),
// not just the editable flag (env/mcp files have no desc.json metadata).
func (m *BrowserModel) canEditDesc() bool {
	return m.current.IsArray() || tree.CanModify(m.pluginName, m.currentPath())
}

// isObjectContext checks if the current path is inside an object-typed config node.
func (m *BrowserModel) isObjectContext() bool {
	return tree.IsObjectContext(m.pluginName, m.currentPath())
}

func (m *BrowserModel) autoSave() {
	if m.OnSaveFile != nil {
		if err := m.OnSaveFile(m.Root); err != nil {
			m.errMsg = err.Error()
		}
	}
}

// WithEditable enables add/delete/rename without copy.json permission.
func (m *BrowserModel) WithEditable(v bool) *BrowserModel {
	m.editable = v
	return m
}

// WithNewItemFactory sets a custom factory for creating new items.
func (m *BrowserModel) WithNewItemFactory(f func(title, desc string) tree.ConfigNode) *BrowserModel {
	m.newItemFactory = f
	return m
}

func (m *BrowserModel) refreshList() {
	items := make([]list.Item, 0, len(m.current.Children()))
	for _, child := range m.current.Children() {
		items = append(items, NodeItem{Node: child})
	}

	delegate := style.StyleDelegate(list.NewDefaultDelegate())

	l := list.New(items, delegate, 0, 0)
	title := m.current.Title()
	if len(m.stack) == 0 && title == "root" && m.title != "" {
		title = m.title
	}
	l.Title = style.Title(title)
	l.SetShowStatusBar(false)
	l.SetFilteringEnabled(false)
	l.SetShowHelp(false)
	l.SetShowPagination(false)
	l.Styles.Title = style.ListTitle

	if m.List.Items() != nil {
		// Preserve index if possible
		idx := m.List.Index()
		if idx >= len(items) {
			idx = len(items) - 1
		}
		if idx < 0 {
			idx = 0
		}
		l.Select(idx)
	}

	m.List = l
	m.updateSize()
}

func (m *BrowserModel) updateSize() {
	if m.width > 0 && m.height > 0 {
		// Reserve 2 lines for the external help bar rendered below the list.
		m.List.SetSize(m.width, m.height-2)
		m.help.Width = m.width
	}
}

// handleFormSizeAndCancel handles WindowSizeMsg and ESC cancellation for forms.
// Returns (formUpdated, cmd) where formUpdated indicates if form was resized.
func (m *BrowserModel) handleFormSizeAndCancel(msg tea.Msg, active *bool, form *huh.Form, setForm func(*huh.Form)) (bool, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		w := m.formWidth()
		newForm := form.WithWidth(w)
		_, cmd := newForm.Update(msg)
		setForm(newForm)
		return true, cmd
	case tea.KeyMsg:
		if msg.String() == "esc" {
			*active = false
			setForm(nil)
			return true, nil
		}
	}
	return false, nil
}

func (m *BrowserModel) formWidth() int {
	w := 60
	if m.width > 16 {
		w = m.width - 8
		if w > 60 {
			w = 60
		}
	}
	return w
}

// currentPath builds the dot-separated path from root to current branch (without "root." prefix).
func (m *BrowserModel) currentPath() string {
	var parts []string
	for _, b := range m.stack {
		if b.Title() != "root" {
			parts = append(parts, b.Title())
		}
	}
	if m.current.Title() != "root" {
		parts = append(parts, m.current.Title())
	}
	return strings.Join(parts, ".")
}

func (m *BrowserModel) childPath(title string) string {
	if parent := m.currentPath(); parent != "" {
		return parent + "." + title
	}
	return title
}

var objectValueTypes = []string{"number", "string", "boolean", "array", "object"}

func (m *BrowserModel) buildAddForm() tea.Cmd {
	w := m.formWidth()
	fields := make([]huh.Field, 0, 3)
	if !m.current.IsArray() {
		fields = append(fields, huh.NewInput().
			Title("Title").
			Placeholder("(empty)").
			Value(&m.addTitle).
			Key("title"))
	}
	fields = append(fields, huh.NewInput().
		Title("Description").
		Placeholder("(empty)").
		Value(&m.addDesc).
		Key("desc"))
	if m.isObjectContext() {
		fields = append(fields,
			huh.NewSelect[string]().
				Title("Type").
				Options(huh.NewOptions(objectValueTypes...)...).
				Value(&m.addType).
				Key("type"),
		)
	}
	m.addForm = huh.NewForm(
		huh.NewGroup(fields...),
	).WithWidth(w).WithShowHelp(false).WithTheme(style.HuhTheme())
	return m.addForm.Init()
}

func (m *BrowserModel) buildRenameForm() tea.Cmd {
	w := m.formWidth()
	fields := make([]huh.Field, 0, 2)
	if !m.current.IsArray() {
		fields = append(fields, huh.NewInput().
			Title("Name").
			Placeholder("new name").
			Value(&m.renameValue).
			Key("name"))
	}
	fields = append(fields, huh.NewInput().
		Title("Description").
		Placeholder("(empty)").
		Value(&m.renameDesc).
		Key("desc"))
	m.renameForm = huh.NewForm(
		huh.NewGroup(fields...),
	).WithWidth(w).WithShowHelp(false).WithTheme(style.HuhTheme())
	return m.renameForm.Init()
}

func (m *BrowserModel) buildDeleteConfirmForm() tea.Cmd {
	w := m.formWidth()
	m.deleteConfirm = false

	m.deleteForm = huh.NewForm(
		huh.NewGroup(
			huh.NewConfirm().
				Title("Delete this item?").
				Affirmative("Yes").
				Negative("No").
				Value(&m.deleteConfirm).
				Key("confirm"),
		),
	).WithWidth(w).WithShowHelp(false).WithTheme(style.DestructiveHuhTheme())
	return m.deleteForm.Init()
}

func (m *BrowserModel) buildEditDescForm() tea.Cmd {
	w := m.formWidth()
	m.editDescForm = huh.NewForm(
		huh.NewGroup(
			huh.NewInput().
				Title("Description").
				Placeholder("(empty)").
				Value(&m.editDescValue).
				Key("desc"),
		),
	).WithWidth(w).WithShowHelp(false).WithTheme(style.HuhTheme())
	return m.editDescForm.Init()
}

func (m *BrowserModel) Init() tea.Cmd {
	return nil
}

func (m *BrowserModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	if m.editingDesc && m.editDescForm != nil {
		if handled, cmd := m.handleFormSizeAndCancel(msg, &m.editingDesc, m.editDescForm, func(f *huh.Form) { m.editDescForm = f }); handled {
			return m, cmd
		}
		newForm, cmd := m.editDescForm.Update(msg)
		if f, ok := newForm.(*huh.Form); ok {
			m.editDescForm = f
		}
		if m.editDescForm.State == huh.StateCompleted {
			newDesc := strings.TrimSpace(m.editDescValue)
			m.editingDesc = false
			m.editDescForm = nil
			idx := m.List.Index()
			if idx >= 0 && idx < len(m.current.Children()) {
				child := m.current.Children()[idx]
				if b, ok := child.(*tree.BranchNode); ok {
					b.SetDescription(newDesc)
				} else if l, ok := child.(*tree.LeafNode); ok {
					l.SetDescription(newDesc)
				}
				m.refreshList()
				m.List.Select(idx)
				_ = tree.SaveDesc(m.childPath(child.Title()), newDesc)
			}
		}
		return m, cmd
	}

	if m.adding && m.addForm != nil {
		if handled, cmd := m.handleFormSizeAndCancel(msg, &m.adding, m.addForm, func(f *huh.Form) { m.addForm = f }); handled {
			return m, cmd
		}
		newForm, cmd := m.addForm.Update(msg)
		if f, ok := newForm.(*huh.Form); ok {
			m.addForm = f
		}
		if m.addForm.State == huh.StateCompleted {
			title := strings.TrimSpace(m.addTitle)
			if m.current.IsArray() {
				title = fmt.Sprintf("[%d]", len(m.current.Children()))
			}
			desc := strings.TrimSpace(m.addDesc)
			m.adding = false
			m.addForm = nil
			if title != "" {
				if m.isObjectContext() {
					m.current.AddChild(m.createObjectValueNode(title, desc, m.addType))
				} else {
					m.current.AddChild(m.createNodeFromTemplate(title, desc))
				}
				m.refreshList()
				m.List.Select(len(m.current.Children()) - 1)
				_ = tree.SaveDesc(m.childPath(title), desc)
				m.autoSave()
			}
		}
		return m, cmd
	}

	if m.renaming && m.renameForm != nil {
		if handled, cmd := m.handleFormSizeAndCancel(msg, &m.renaming, m.renameForm, func(f *huh.Form) { m.renameForm = f }); handled {
			return m, cmd
		}
		newForm, cmd := m.renameForm.Update(msg)
		if f, ok := newForm.(*huh.Form); ok {
			m.renameForm = f
		}
		if m.renameForm.State == huh.StateCompleted {
			newName := strings.TrimSpace(m.renameValue)
			newDesc := strings.TrimSpace(m.renameDesc)
			m.renaming = false
			m.renameForm = nil
			if newName != "" {
				idx := m.List.Index()
				if idx >= 0 && idx < len(m.current.Children()) {
					child := m.current.Children()[idx]
					oldPath := m.childPath(child.Title())
					if m.current.IsArray() {
						newName = child.Title()
					}
					if b, ok := child.(*tree.BranchNode); ok {
						b.SetTitle(newName)
					} else if l, ok := child.(*tree.LeafNode); ok {
						l.SetTitle(newName)
					}
					if b, ok := child.(*tree.BranchNode); ok {
						b.SetDescription(newDesc)
					} else if l, ok := child.(*tree.LeafNode); ok {
						l.SetDescription(newDesc)
					}
					m.refreshList()
					m.List.Select(idx)
					newPath := m.childPath(newName)
					if oldPath != newPath {
						_ = tree.SaveDesc(oldPath, "")
					}
					_ = tree.SaveDesc(newPath, newDesc)
				}
				m.autoSave()
			}
			return m, cmd
		}
		return m, cmd
	}

	if m.deleting && m.deleteForm != nil {
		if handled, cmd := m.handleFormSizeAndCancel(msg, &m.deleting, m.deleteForm, func(f *huh.Form) { m.deleteForm = f }); handled {
			return m, cmd
		}
		newForm, cmd := m.deleteForm.Update(msg)
		if f, ok := newForm.(*huh.Form); ok {
			m.deleteForm = f
		}
		if m.deleteForm.State == huh.StateCompleted {
			confirmed := m.deleteConfirm
			m.deleting = false
			m.deleteForm = nil
			if confirmed {
				idx := m.List.Index()
				if m.current.RemoveChildAt(idx) {
					m.refreshList()
					m.autoSave()
				}
			}
			return m, cmd
		}
		return m, cmd
	}

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
			if len(m.stack) > 0 {
				m.current = m.stack[len(m.stack)-1]
				m.stack = m.stack[:len(m.stack)-1]
				m.refreshList()
			} else {
				return m, func() tea.Msg { return components.PopScreenMsg{} }
			}
			return m, nil
		}
		if key.Matches(msg, m.Keys.Enter) {
			if item, ok := m.List.SelectedItem().(NodeItem); ok {
				if item.Node.IsLeaf() {
					leaf := item.Node.(*tree.LeafNode)
					if m.onEdit != nil {
						m.onEdit(leaf, func() tea.Cmd {
							// Save to file when editor triggers save
							if m.OnSaveFile != nil {
								if err := m.OnSaveFile(m.Root); err != nil {
									m.errMsg = err.Error()
								} else {
									m.errMsg = ""
								}
							}
							m.refreshList()
							return nil
						})
					}
				} else {
					branch := item.Node.(*tree.BranchNode)
					m.stack = append(m.stack, m.current)
					m.current = branch
					m.refreshList()
				}
			}
			return m, nil
		}
		if key.Matches(msg, m.Keys.EditDesc) {
			if !m.canEditDesc() {
				return m, nil
			}

			if item, ok := m.List.SelectedItem().(NodeItem); ok {
				m.editingDesc = true
				m.editDescValue = item.Node.Description()
				return m, m.buildEditDescForm()
			}
			return m, nil
		}

		if key.Matches(msg, m.Keys.New) {
			if !m.canModify() {
				return m, nil
			}
			m.adding = true
			m.addTitle = ""
			m.addDesc = ""
			m.addType = "number"
			if m.current.IsArray() {
				m.addTitle = fmt.Sprintf("[%d]", len(m.current.Children()))
			}
			return m, m.buildAddForm()
		}
		if key.Matches(msg, m.Keys.Rename) {
			if !m.canModify() {
				return m, nil
			}
			if item, ok := m.List.SelectedItem().(NodeItem); ok {
				if !item.Node.IsLeaf() {
					m.renaming = true
					m.renameValue = item.Node.Title()
					m.renameDesc = item.Node.Description()
					return m, m.buildRenameForm()
				}
			}
			return m, nil
		}
		if key.Matches(msg, m.Keys.Delete) {
			if !m.canModify() {
				return m, nil
			}
			m.deleting = true
			return m, m.buildDeleteConfirmForm()
		}
	}

	var cmd tea.Cmd
	m.List, cmd = m.List.Update(msg)
	return m, cmd
}

func (m *BrowserModel) createNodeFromTemplate(title, desc string) tree.ConfigNode {
	if m.newItemFactory != nil {
		return m.newItemFactory(title, desc)
	}
	if len(m.current.Children()) > 0 {
		template := m.current.Children()[0]
		cloned := tree.CloneNode(template)
		if branch, ok := cloned.(*tree.BranchNode); ok {
			newBranch := tree.NewBranch(title, desc)
			newBranch.SetIsArray(branch.IsArray())
			for _, child := range branch.Children() {
				newBranch.AddChild(child)
			}
			return newBranch
		}
		if leaf, ok := cloned.(*tree.LeafNode); ok {
			return tree.NewLeaf(title, desc, leaf.ValueType(), leaf.Value())
		}
	}
	return tree.NewBranch(title, desc)
}

func (m *BrowserModel) createObjectValueNode(title, desc, valueType string) tree.ConfigNode {
	switch valueType {
	case "object":
		return tree.NewBranch(title, desc)
	case "array":
		return tree.NewLeaf(title, desc, tree.TypeArray, []string{})
	case "boolean":
		return tree.NewLeaf(title, desc, tree.TypeBool, false)
	case "number":
		return tree.NewLeaf(title, desc, tree.TypeNumber, float64(0))
	default:
		return tree.NewLeaf(title, desc, tree.TypeString, "")
	}
}

func (m *BrowserModel) ShortHelp() []key.Binding {
	if m.editingDesc || m.adding || m.renaming || m.deleting {
		return []key.Binding{m.Keys.FormCancel, m.Keys.FormNext, m.Keys.FormPrev, m.Keys.FormSubmit}
	}
	if m.canEditDesc() {
		bindings := []key.Binding{m.Keys.Up, m.Keys.Down, m.Keys.Enter, m.Keys.Back, m.Keys.EditDesc}
		if m.canModify() {
			if item, ok := m.List.SelectedItem().(NodeItem); ok && !item.Node.IsLeaf() {
				bindings = append(bindings, m.Keys.Rename)
			}
		}
		return append(bindings, m.Keys.Help, m.Keys.Quit)
	}
	return m.Keys.ShortHelp()
}

func (m *BrowserModel) FullHelp() [][]key.Binding {
	var middle []key.Binding
	if m.canEditDesc() {
		middle = append(middle, m.Keys.EditDesc)
	}
	if m.canModify() {
		middle = append(middle, m.Keys.New)
		if item, ok := m.List.SelectedItem().(NodeItem); ok {
			if !item.Node.IsLeaf() {
				middle = append(middle, m.Keys.Rename)
			}
			middle = append(middle, m.Keys.Delete)
		}
	}
	return [][]key.Binding{
		{m.Keys.Up, m.Keys.Down, m.Keys.Enter},
		middle,
		{m.Keys.Back, m.Keys.Help, m.Keys.Quit},
	}
}

func (m *BrowserModel) View() string {
	if m.editingDesc && m.editDescForm != nil {
		var b strings.Builder
		b.WriteString(style.Title("Edit Description") + "\n\n")
		b.WriteString(m.editDescForm.View())
		b.WriteString("\n" + m.help.View(m))
		return b.String()
	}

	if m.adding && m.addForm != nil {
		var b strings.Builder
		b.WriteString(style.Title("Add new item") + "\n\n")
		b.WriteString(m.addForm.View())
		b.WriteString("\n" + m.help.View(m))
		return b.String()
	}

	if m.renaming && m.renameForm != nil {
		var b strings.Builder
		b.WriteString(style.Title("Rename") + "\n\n")
		b.WriteString(m.renameForm.View())
		b.WriteString("\n" + m.help.View(m))
		return b.String()
	}

	if m.deleting && m.deleteForm != nil {
		var b strings.Builder
		b.WriteString(style.ErrorText("Delete") + "\n\n")
		b.WriteString(m.deleteForm.View())
		b.WriteString("\n" + m.help.View(m))
		return b.String()
	}

	var b string
	b = m.List.View()

	if m.errMsg != "" {
		b += "\n" + style.ErrorText("Error: "+m.errMsg)
	}

	b += "\n" + m.help.View(m)
	return b
}
