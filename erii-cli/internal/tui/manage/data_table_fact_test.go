package manage

import (
	"testing"

	"erii-cli/internal/api"
	tea "github.com/charmbracelet/bubbletea"
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
