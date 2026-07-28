package manage

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"

	"erii-cli/internal/api"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

func TestFactsFormatterShowsValidityStatus(t *testing.T) {
	formatter := getFormatter(ResourceFacts)
	widths := formatter.calcWidths(160)
	columns := formatter.columns(widths)

	if len(columns) != 8 {
		t.Fatalf("expected 8 fact columns, got %d", len(columns))
	}
	if columns[2].Title != "Status" {
		t.Fatalf("expected status column, got %q", columns[2].Title)
	}

	validRow := formatter.getRow(api.FactRecord{ID: 1, Valid: true}, false)
	invalidRow := formatter.getRow(api.FactRecord{ID: 2, Valid: false}, false)
	if validRow[2] != "Valid" {
		t.Fatalf("expected valid status, got %q", validRow[2])
	}
	if invalidRow[2] != "Invalid" {
		t.Fatalf("expected invalid status, got %q", invalidRow[2])
	}
}

func TestDataTableUsesServerPageCountAndSortReload(t *testing.T) {
	model := NewDataTableModel(nil, ResourceFacts, api.BotInfo{BotID: "bot"}, api.GroupInfo{GroupID: "group"})
	model.pageSize = 20
	model.totalCount = 45

	if got := model.pageCount(); got != 3 {
		t.Fatalf("pageCount = %d, want 3", got)
	}

	updated, cmd := model.applySortBy(2)
	result := updated.(*DataTableModel)
	if cmd == nil {
		t.Fatal("sorting should request a fresh server page")
	}
	if result.sortCol != 2 || !result.sortAsc {
		t.Fatalf("unexpected sort state: col=%d asc=%v", result.sortCol, result.sortAsc)
	}
	if field := serverSortField(ResourceFacts, result.sortCol); field != "keyword" {
		t.Fatalf("server sort field = %q, want keyword", field)
	}
}

func TestDataTablePageSizeUsesVisibleRows(t *testing.T) {
	model := NewDataTableModel(nil, ResourceFacts, api.BotInfo{BotID: "bot"}, api.GroupInfo{GroupID: "group"})
	if cmd := model.Init(); cmd != nil {
		t.Fatal("table should wait for terminal dimensions before loading its first page")
	}

	updated, cmd := model.Update(tea.WindowSizeMsg{Width: 120, Height: 30})
	result := updated.(*DataTableModel)
	if cmd == nil {
		t.Fatal("receiving terminal dimensions should load the first server page")
	}
	if result.pageSize != result.table.Height() {
		t.Fatalf("pageSize = %d, visible table rows = %d", result.pageSize, result.table.Height())
	}
	if result.pageSize == 20 {
		t.Fatalf("pageSize should be calculated from height, still got fixed value %d", result.pageSize)
	}
	result.filteredItems = make([]any, result.pageSize)
	for i := range result.filteredItems {
		result.filteredItems[i] = api.FactRecord{ID: i + 1, Valid: true}
	}
	result.totalCount = result.pageSize
	result.updateTableRows()
	result.loading = false
	if got := lipgloss.Height(result.View()); got != result.height {
		t.Fatalf(
			"rendered table height = %d, terminal height = %d (title=%d table=%d status=%d help=%d rows=%d)",
			got,
			result.height,
			lipgloss.Height(result.renderTitleBar()),
			lipgloss.Height(result.table.View()),
			lipgloss.Height(result.renderStatusBar()),
			lipgloss.Height(result.help.View(result.keys)),
			result.table.Height(),
		)
	}

	oldPageSize := result.pageSize
	updated, cmd = result.Update(tea.WindowSizeMsg{Width: 120, Height: 22})
	result = updated.(*DataTableModel)
	if result.pageSize >= oldPageSize {
		t.Fatalf("pageSize did not shrink with terminal: before=%d after=%d", oldPageSize, result.pageSize)
	}
	if cmd == nil {
		t.Fatal("changing visible row count should reload the server page")
	}
}

func TestDataTableSendsVisibleRowCountAsServerLimit(t *testing.T) {
	limit := make(chan string, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		limit <- r.URL.Query().Get("limit")
		_ = json.NewEncoder(w).Encode(api.PaginatedResponse[api.FactRecord]{})
	}))
	defer server.Close()

	port := server.Listener.Addr().(*net.TCPAddr).Port
	model := NewDataTableModel(
		api.NewClient(port, "", ""),
		ResourceFacts,
		api.BotInfo{BotID: "bot"},
		api.GroupInfo{GroupID: "group"},
	)
	updated, cmd := model.Update(tea.WindowSizeMsg{Width: 120, Height: 30})
	if cmd == nil {
		t.Fatal("terminal dimensions should create a server load command")
	}
	msg, ok := cmd().(dataLoadedMsg)
	if !ok {
		t.Fatalf("load command returned %T, want dataLoadedMsg", msg)
	}
	if msg.Error != nil {
		t.Fatalf("load command failed: %v", msg.Error)
	}
	want := strconv.Itoa(updated.(*DataTableModel).pageSize)
	if got := <-limit; got != want {
		t.Fatalf("server limit = %q, visible row count = %q", got, want)
	}
}

func TestDataTableSearchSubmitsServerQuery(t *testing.T) {
	model := NewDataTableModel(nil, ResourceFacts, api.BotInfo{BotID: "bot"}, api.GroupInfo{GroupID: "group"})
	model.searching = true
	model.searchInput.SetValue("  expired memory  ")
	model.currentPage = 2

	updated, cmd := model.Update(tea.KeyMsg{Type: tea.KeyEnter})
	result := updated.(*DataTableModel)
	if cmd == nil {
		t.Fatal("submitting search should request a fresh server page")
	}
	if result.searchQuery != "expired memory" {
		t.Fatalf("search query = %q", result.searchQuery)
	}
	if result.currentPage != 0 {
		t.Fatalf("current page = %d, want 0", result.currentPage)
	}
}
