package usage

import (
	"strings"
	"testing"
	"time"

	"erii-cli/internal/api"

	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
)

func TestUsageDateNavigationStopsAtBoundaries(t *testing.T) {
	model := newNavigationTestModel(navigationTestData())

	assertSelectedDay(t, model, 0)
	if !model.keys.OlderDate.Enabled() || model.keys.NewerDate.Enabled() {
		t.Fatalf("today key state: older=%v newer=%v", model.keys.OlderDate.Enabled(), model.keys.NewerDate.Enabled())
	}

	updated, _ := model.Update(tea.KeyMsg{Type: tea.KeyTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 1)

	updated, _ = model.Update(tea.KeyMsg{Type: tea.KeyTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 2)
	if model.keys.OlderDate.Enabled() || !model.keys.NewerDate.Enabled() {
		t.Fatalf("oldest key state: older=%v newer=%v", model.keys.OlderDate.Enabled(), model.keys.NewerDate.Enabled())
	}

	updated, _ = model.Update(tea.KeyMsg{Type: tea.KeyTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 2)

	updated, _ = model.Update(tea.KeyMsg{Type: tea.KeyShiftTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 1)
	updated, _ = model.Update(tea.KeyMsg{Type: tea.KeyShiftTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 0)
	updated, _ = model.Update(tea.KeyMsg{Type: tea.KeyShiftTab})
	model = updated.(*UsageViewModel)
	assertSelectedDay(t, model, 0)
}

func TestUsageDateNavigatorOmitsUnavailableDays(t *testing.T) {
	today := time.Now()
	data := navigationTestData()
	data.DailyViews = []api.DailyTokenUsageSummary{
		data.DailyViews[0],
		data.DailyViews[2],
	}
	model := newNavigationTestModel(data)

	navigator := model.buildDateNavigator()
	if !strings.Contains(navigator, "Today ("+today.Format("01-02")+")") {
		t.Fatalf("navigator missing today: %s", navigator)
	}
	if strings.Contains(navigator, today.AddDate(0, 0, -1).Format("01-02")) {
		t.Fatalf("navigator contains unavailable day: %s", navigator)
	}
	if !strings.Contains(navigator, today.AddDate(0, 0, -2).Format("01-02")) {
		t.Fatalf("navigator missing available historical day: %s", navigator)
	}
}

func TestUsageDateNavigatorDisplaysOldestToNewest(t *testing.T) {
	today := time.Now()
	model := newNavigationTestModel(navigationTestData())

	navigator := model.buildDateNavigator()
	oldest := today.AddDate(0, 0, -2).Format("01-02")
	middle := today.AddDate(0, 0, -1).Format("01-02")
	current := "Today (" + today.Format("01-02") + ")"
	oldestAt := strings.Index(navigator, oldest)
	middleAt := strings.Index(navigator, middle)
	currentAt := strings.Index(navigator, current)
	if oldestAt < 0 || middleAt < 0 || currentAt < 0 || !(oldestAt < middleAt && middleAt < currentAt) {
		t.Fatalf("navigator is not ordered oldest to newest: %s", navigator)
	}
}

func TestUsageDateSwitchChangesDailyPanelsOnly(t *testing.T) {
	model := newNavigationTestModel(navigationTestData())
	todayKPI := strings.Join(model.buildKPIGrid(100), "\n")
	todayScene := model.sceneChart
	ledger := strings.Join(model.buildLedger(100), "\n")
	lineChart := model.lineChart
	heatmap := model.heatmap

	model.selectDay(1)

	olderKPI := strings.Join(model.buildKPIGrid(100), "\n")
	if todayKPI == olderKPI || !strings.Contains(olderKPI, "222") {
		t.Fatalf("daily KPI did not switch: today=%q older=%q", todayKPI, olderKPI)
	}
	if todayScene == model.sceneChart {
		t.Fatal("scene distribution did not switch with selected day")
	}
	if got := strings.Join(model.buildLedger(100), "\n"); got != ledger {
		t.Fatal("accumulated totals changed with selected day")
	}
	if model.lineChart != lineChart || model.heatmap != heatmap {
		t.Fatal("historical charts changed with selected day")
	}
}

func TestUsageLegacyResponseFallsBackToTodayWithoutNavigationKeys(t *testing.T) {
	data := &api.TokenUsageSummary{
		TodayCacheHitInput:  10,
		TodayCacheMissInput: 20,
		TodayOutput:         30,
		TodayCost:           0.1,
		TodayCacheHitRate:   33.33,
		PriceUnit:           "USD",
		SceneBars: []api.TokenUsageChartPoint{
			{Name: "聊天", CacheHitInput: 10, CacheMissInput: 20, Output: 30},
		},
		ModelBars: []api.TokenUsageChartPoint{
			{Name: "Flash", CacheHitInput: 10, CacheMissInput: 20, Output: 30},
		},
	}
	model := newNavigationTestModel(data)

	if len(model.data.DailyViews) != 1 {
		t.Fatalf("fallback daily views = %d, want 1", len(model.data.DailyViews))
	}
	if model.keys.OlderDate.Enabled() || model.keys.NewerDate.Enabled() {
		t.Fatal("date keys should be disabled for a single day")
	}
	if help := model.help.View(model.keys); strings.Contains(help, "tab") {
		t.Fatalf("single-day help unexpectedly shows date navigation: %s", help)
	}
	if got := strings.Join(model.buildKPIGrid(100), "\n"); !strings.Contains(got, "10 / 20") {
		t.Fatalf("fallback KPI does not use legacy today fields: %s", got)
	}
}

func newNavigationTestModel(data *api.TokenUsageSummary) *UsageViewModel {
	model := &UsageViewModel{
		data:     data,
		keys:     newUsageKeyMap(),
		viewport: viewport.New(100, 40),
		width:    100,
		height:   43,
	}
	model.prepareDailyViews()
	model.buildCharts()
	model.viewport.SetContent(model.buildContent())
	return model
}

func navigationTestData() *api.TokenUsageSummary {
	today := time.Now()
	return &api.TokenUsageSummary{
		PriceUnit:           "USD",
		TotalCacheHitInput:  1000,
		TotalCacheMissInput: 2000,
		TotalOutput:         3000,
		TotalCost:           1.25,
		DailySeries: []api.DailyTokenUsagePoint{
			{Date: today.AddDate(0, 0, -2).Format("2006-01-02"), Tokens: 222, Cost: 0.2},
			{Date: today.AddDate(0, 0, -1).Format("2006-01-02"), Tokens: 333, Cost: 0.3},
			{Date: today.Format("2006-01-02"), Tokens: 111, Cost: 0.1},
		},
		DailyViews: []api.DailyTokenUsageSummary{
			{
				Date:           today.Format("2006-01-02"),
				CacheHitInput:  50,
				CacheMissInput: 40,
				Output:         111,
				Cost:           0.1,
				CacheHitRate:   55.56,
				SceneBars: []api.TokenUsageChartPoint{
					{Name: "聊天", CacheHitInput: 50, CacheMissInput: 40, Output: 111},
				},
				ModelBars: []api.TokenUsageChartPoint{
					{Name: "Flash", CacheHitInput: 50, CacheMissInput: 40, Output: 111},
				},
			},
			{
				Date:           today.AddDate(0, 0, -1).Format("2006-01-02"),
				CacheHitInput:  20,
				CacheMissInput: 30,
				Output:         222,
				Cost:           0.2,
				CacheHitRate:   40,
				SceneBars: []api.TokenUsageChartPoint{
					{Name: "搜索", CacheHitInput: 20, CacheMissInput: 30, Output: 222},
				},
				ModelBars: []api.TokenUsageChartPoint{
					{Name: "Pro", CacheHitInput: 20, CacheMissInput: 30, Output: 222},
				},
			},
			{
				Date:           today.AddDate(0, 0, -2).Format("2006-01-02"),
				CacheHitInput:  10,
				CacheMissInput: 15,
				Output:         333,
				Cost:           0.3,
				CacheHitRate:   40,
				SceneBars: []api.TokenUsageChartPoint{
					{Name: "插件", CacheHitInput: 10, CacheMissInput: 15, Output: 333},
				},
				ModelBars: []api.TokenUsageChartPoint{
					{Name: "Lite", CacheHitInput: 10, CacheMissInput: 15, Output: 333},
				},
			},
		},
	}
}

func assertSelectedDay(t *testing.T, model *UsageViewModel, want int) {
	t.Helper()
	if model.selectedDay != want {
		t.Fatalf("selected day = %d, want %d", model.selectedDay, want)
	}
}
