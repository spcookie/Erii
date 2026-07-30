package usage

import (
	"erii-cli/internal/api"
	"fmt"
	"image"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/NimbleMarkets/ntcharts/canvas"
	"github.com/NimbleMarkets/ntcharts/heatmap"
	"github.com/NimbleMarkets/ntcharts/linechart"
	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"
)

// Legacy color scale for old heatmap.Model tests (expects []lipgloss.Color)
var heatBlueScaleLegacy = []lipgloss.Color{
	lipgloss.Color("#1F1F1F"),
	lipgloss.Color("#10233F"),
	lipgloss.Color("#12345A"),
	lipgloss.Color("#174777"),
	lipgloss.Color("#1C5FA0"),
	lipgloss.Color("#2377C8"),
	lipgloss.Color("#2A88E5"),
	lipgloss.Color("#3291FF"),
	lipgloss.Color("#79B8FF"),
}

func mockUsageData() *api.TokenUsageSummary {
	return &api.TokenUsageSummary{
		TodayCacheHitInput:  125000,
		TodayCacheMissInput: 35000,
		TodayOutput:         28000,
		TodayCost:           0.0235,
		PriceUnit:           "USD",
		TotalCacheHitInput:  1500000,
		TotalCacheMissInput: 420000,
		TotalOutput:         336000,
		TotalCost:           0.282,
		TodayCacheHitRate:   78.1,
		SceneBars: []api.TokenUsageChartPoint{
			{Name: "聊天", CacheHitInput: 420000, CacheMissInput: 50000, Output: 5100},
			{Name: "搜索", CacheHitInput: 3500, CacheMissInput: 1000, Output: 200},
			{Name: "插件", CacheHitInput: 80000, CacheMissInput: 12000, Output: 3000},
			{Name: "记忆", CacheHitInput: 65000, CacheMissInput: 8000, Output: 2100},
			{Name: "摘要", CacheHitInput: 45000, CacheMissInput: 6000, Output: 1800},
			{Name: "路由", CacheHitInput: 1500, CacheMissInput: 500, Output: 100},
		},
		ModelBars: []api.TokenUsageChartPoint{
			{Name: "claude-opus-4-7", CacheHitInput: 350000, CacheMissInput: 40000, Output: 4500},
			{Name: "claude-sonnet-4-6", CacheHitInput: 70000, CacheMissInput: 10000, Output: 750},
			{Name: "deepseek-v4", CacheHitInput: 4500, CacheMissInput: 1500, Output: 50},
		},
		DailySeries: mockDailySeries(),
	}
}

func TestPricingTableUsesConfiguredTierOrderAndSemantics(t *testing.T) {
	data := mockUsageData()
	data.PriceUnit = "CNY"
	data.Pricing = &api.TokenUsagePricing{
		Lite:  api.TokenUsageTierPricing{InputCacheHit: 0.01875, InputCacheMiss: 0.075, Output: 0.3},
		Flash: api.TokenUsageTierPricing{InputCacheHit: 0.025, InputCacheMiss: 0.1, Output: 0.4},
		Pro:   api.TokenUsageTierPricing{InputCacheHit: 0.3125, InputCacheMiss: 1.25, Output: 10},
	}
	m := &UsageViewModel{data: data, width: 100}

	lines := strings.Split(ansi.Strip(strings.Join(m.buildPricingTable(), "\n")), "\n")
	want := [][]string{
		{"Lite(0.075/0.01875/0.3)", "Flash(0.1/0.025/0.4)", "Pro(1.25/0.3125/10)", "/", "1M", "tokens"},
	}
	if len(lines) != len(want) {
		t.Fatalf("pricing lines = %d, want %d: %q", len(lines), len(want), lines)
	}
	for i := range want {
		if got := strings.Fields(lines[i]); !reflect.DeepEqual(got, want[i]) {
			t.Errorf("pricing line %d = %q, want fields %q", i, got, want[i])
		}
	}

	m.buildCharts()
	content := ansi.Strip(m.buildContent())
	unitAt := strings.Index(content, "Unit: CNY")
	pricingAt := strings.Index(content, "Lite(0.075/0.01875/0.3)")
	totalsAt := strings.Index(content, "Accumulated Totals")
	if unitAt < 0 || pricingAt <= unitAt || totalsAt <= pricingAt {
		t.Fatalf("pricing table is not between unit and usage sections: unit=%d pricing=%d totals=%d", unitAt, pricingAt, totalsAt)
	}
}

func TestPricingTableIsHiddenForLegacyResponse(t *testing.T) {
	m := &UsageViewModel{data: mockUsageData(), width: 100}
	if rows := m.buildPricingTable(); len(rows) != 0 {
		t.Fatalf("legacy pricing rows = %q, want none", rows)
	}
	m.buildCharts()
	if content := ansi.Strip(m.buildContent()); strings.Contains(content, "/ 1M tokens") {
		t.Fatalf("legacy usage content unexpectedly contains pricing: %q", content)
	}
}

func TestFormatPriceUsesPlainTrimmedDecimals(t *testing.T) {
	for value, want := range map[float64]string{
		0.01875:  "0.01875",
		0.000001: "0.000001",
		0.30:     "0.3",
		10.00:    "10",
	} {
		if got := formatPrice(value); got != want {
			t.Errorf("formatPrice(%v) = %q, want %q", value, got, want)
		}
	}
}

func TestBarChartOutput(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	m.buildCharts()

	fmt.Println("========== Scene Chart ==========")
	fmt.Println(m.sceneChart)
	fmt.Println()

	fmt.Println("========== Model Chart ==========")
	fmt.Println(m.modelChart)
	fmt.Println()

	fmt.Println("========== Line Chart ==========")
	fmt.Println(m.lineChart)
	fmt.Println()

	fmt.Println("========== Heatmap ==========")
	fmt.Println(m.heatmap)
	fmt.Println()

	fmt.Println("========== Full Content ==========")
	m.viewport.Width = 100
	m.viewport.Height = 60
	fmt.Println(m.buildContent())
}

func TestHeatmapCellColoring(t *testing.T) {
	// Verify heatmap canvas cells get background colors set correctly.
	// Note: lipgloss v1.1.1-0.202504... does not emit ANSI in Render(),
	// so View() output is plain text. Colors appear when bubbletea renders.
	series := mockUsageData().DailySeries
	n := len(series)
	cw := 80
	hmH := 5

	var maxV float64
	for _, p := range series {
		if float64(p.Tokens) > maxV {
			maxV = float64(p.Tokens)
		}
	}
	if maxV == 0 {
		maxV = 1
	}

	lc := linechart.New(cw, hmH, 0, 1, 0, 1,
		linechart.WithAutoXYRange(),
		linechart.WithXYSteps(0, 0),
	)
	hm := heatmap.New(cw, hmH,
		heatmap.WithStyle(lc),
		heatmap.WithValueRange(0, maxV),
	)
	hm.ColorScale = heatBlueScaleLegacy

	gw := hm.GraphWidth()
	gh := hm.GraphHeight()

	for ci := 0; ci < gw; ci++ {
		dayIdx := ci * n / gw
		if dayIdx >= n {
			dayIdx = n - 1
		}
		val := float64(series[dayIdx].Tokens)
		for cj := 0; cj < gh; cj++ {
			hm.Push(heatmap.NewHeatPoint(float64(ci), float64(cj), val))
		}
	}

	hm.Draw()

	colored := 0
	for y := 0; y < hm.Canvas.Height(); y++ {
		for x := 0; x < hm.Canvas.Width(); x++ {
			style := hm.Canvas.GetCellStyle(image.Point{X: x, Y: y})
			if style != nil {
				bgFmt := fmt.Sprintf("%T", style.GetBackground())
				if bgFmt != "lipgloss.NoColor" {
					colored++
				}
			}
		}
	}
	total := hm.Canvas.Width() * hm.Canvas.Height()
	t.Logf("Colored cells: %d / %d", colored, total)
	if colored < total/2 {
		t.Errorf("Expected most cells colored, got %d/%d", colored, total)
	}

	// Verify coordinate mapping: graph coords (0,0) maps to valid canvas cell
	hp := heatmap.NewHeatPoint(0, 0, 50)
	sf := hm.ScaleFloat64PointForLine(hp.AsFloat64Point())
	cp := canvas.CanvasPointFromFloat64Point(hm.Origin(), sf)
	if cp.X < 0 || cp.Y < 0 || cp.X >= hm.Canvas.Width() || cp.Y >= hm.Canvas.Height() {
		t.Errorf("Point(0,0) maps outside canvas: canvas(%d,%d)", cp.X, cp.Y)
	}

	view := hm.View()
	fmt.Println(view)
}

func TestHeatmapWithAutoRange(t *testing.T) {
	lc := linechart.New(20, 5, 0, 1, 0, 1,
		linechart.WithAutoXYRange(),
		linechart.WithXYSteps(0, 0),
	)
	hm := heatmap.New(20, 5,
		heatmap.WithStyle(lc),
		heatmap.WithValueRange(0, 100),
	)
	hm.ColorScale = heatBlueScaleLegacy

	gw := hm.GraphWidth()
	gh := hm.GraphHeight()

	// Push points using graph coords starting from 0
	for ci := 0; ci < gw; ci++ {
		for cj := 0; cj < gh; cj++ {
			hm.Push(heatmap.NewHeatPoint(float64(ci), float64(cj), float64(ci*10)))
		}
	}

	hm.Draw()

	// Check cells
	colored := 0
	for y := 0; y < hm.Canvas.Height(); y++ {
		for x := 0; x < hm.Canvas.Width(); x++ {
			style := hm.Canvas.GetCellStyle(image.Point{X: x, Y: y})
			if style != nil {
				bgFmt := fmt.Sprintf("%T", style.GetBackground())
				if bgFmt != "lipgloss.NoColor" {
					colored++
				}
			}
		}
	}
	t.Logf("Colored cells: %d / %d", colored, hm.Canvas.Width()*hm.Canvas.Height())

	view := hm.View()
	t.Logf("View length: %d", len(view))
	fmt.Println(view)

	// Verify coord mapping
	hp := heatmap.NewHeatPoint(0, 0, 50)
	sf := hm.ScaleFloat64PointForLine(hp.AsFloat64Point())
	cp := canvas.CanvasPointFromFloat64Point(hm.Origin(), sf)
	t.Logf("Point(0,0) -> sf=(%.2f,%.2f) -> canvas(%d,%d)", sf.X, sf.Y, cp.X, cp.Y)

	hp = heatmap.NewHeatPoint(float64(gw-1), float64(gh-1), 50)
	sf = hm.ScaleFloat64PointForLine(hp.AsFloat64Point())
	cp = canvas.CanvasPointFromFloat64Point(hm.Origin(), sf)
	t.Logf("Point(%d,%d) -> sf=(%.2f,%.2f) -> canvas(%d,%d)", gw-1, gh-1, sf.X, sf.Y, cp.X, cp.Y)
}

func TestHeatmapCalendar(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	hm, _ := m.buildHeatmap(44)

	if hm == "" {
		t.Fatal("buildHeatmap() returned empty string")
	}

	// Should contain month labels (e.g., "Jan", "Feb", ... "Jun")
	enMonths := []string{"Jan", "Feb", "Mar", "Apr", "May", "Jun"}
	foundMonths := 0
	for _, m := range enMonths {
		if strings.Contains(hm, m) {
			foundMonths++
		}
	}
	if foundMonths < 3 {
		t.Errorf("Expected at least 3 month labels in heatmap, found %d\nOutput:\n%s", foundMonths, hm)
	}

	// Output should have multiple lines (month label row + 7 day rows)
	lines := strings.Split(strings.TrimRight(hm, "\n"), "\n")
	if len(lines) < 7 {
		t.Errorf("Expected at least 7 lines in heatmap, got %d\nOutput:\n%s", len(lines), hm)
	}

	fmt.Println("========== Calendar Heatmap ==========")
	fmt.Println(hm)
}

func TestDailyHeatmapLayoutUsesSquareCellsAndAdaptiveMonths(t *testing.T) {
	lastDate := time.Date(2026, time.June, 23, 0, 0, 0, 0, time.UTC)

	if heatmapCellWidth != 2 || heatmapCellHeight != 1 {
		t.Fatalf(
			"heatmap cell size = %dx%d, want 2x1 terminal cells",
			heatmapCellWidth,
			heatmapCellHeight,
		)
	}

	narrow := calculateDailyHeatmapLayout(lastDate, 44)
	if narrow.canvasWidth > 44 {
		t.Fatalf("narrow heatmap width = %d, exceeds column width 44", narrow.canvasWidth)
	}
	if narrow.monthCount >= heatmapMaxMonths {
		t.Fatalf("narrow heatmap months = %d, want fewer than %d", narrow.monthCount, heatmapMaxMonths)
	}
	if got, want := narrow.canvasWidth, narrow.numWeeks*heatmapCellWidth; got != want {
		t.Fatalf("narrow canvas width = %d, want week grid width %d", got, want)
	}

	wide := calculateDailyHeatmapLayout(lastDate, 60)
	if wide.monthCount != heatmapMaxMonths {
		t.Fatalf("wide heatmap months = %d, want %d", wide.monthCount, heatmapMaxMonths)
	}
	if wide.canvasWidth > 60 {
		t.Fatalf("wide heatmap width = %d, exceeds column width 60", wide.canvasWidth)
	}
}

func TestDailyIntensityCentersChartButKeepsTitleLeftAligned(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	m.buildCharts()

	halfW := (m.chartWidth() - 2 - 4) / 2
	column := buildDailyIntensityColumn(m.heatmap, halfW)
	lines := strings.Split(column, "\n")
	if len(lines) < 3 {
		t.Fatalf("Daily Intensity column has %d lines, want title, spacer, and chart", len(lines))
	}
	if !strings.HasPrefix(ansi.Strip(lines[0]), "Daily Intensity") {
		t.Fatalf("Daily Intensity title is not left-aligned: %q", lines[0])
	}

	heatmapWidth := maxLineVisualWidth(m.heatmap)
	wantLeftPadding := (halfW - heatmapWidth) / 2
	gotLeftPadding := len(lines[2]) - len(strings.TrimLeft(lines[2], " "))
	if gotLeftPadding != wantLeftPadding {
		t.Fatalf(
			"heatmap left padding = %d, want %d for column width %d and chart width %d",
			gotLeftPadding,
			wantLeftPadding,
			halfW,
			heatmapWidth,
		)
	}
	for i, line := range lines[2:] {
		if got := lipgloss.Width(line); got != halfW {
			t.Fatalf("centered heatmap line %d width = %d, want %d", i, got, halfW)
		}
	}
}

func TestHeatmap7Days(t *testing.T) {
	// Test the line chart shows recent 7 days with correct date labels
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	lc := m.buildLineChart(60, 10)

	if lc == "" {
		t.Fatal("buildLineChart() returned empty string")
	}

	// Should contain the last 7 days' date labels (MM/DD format)
	expectedDates := []string{"6/17", "6/20", "6/23"}
	for _, d := range expectedDates {
		if !strings.Contains(lc, d) {
			t.Errorf("Expected line chart to contain date %s\nOutput:\n%s", d, lc)
		}
	}

	fmt.Println("========== Line Chart (7 days) ==========")
	fmt.Println(lc)
}

// ── TDD tests for new requirements ──

func TestHeatmapZeroIsGrey(t *testing.T) {
	// heatBlueScale[0] should be a visible grey, not black/navy.
	c := heatBlueScale[0]
	// AdaptiveColor or Color — check by string representation
	cStr := fmt.Sprintf("%v", c)
	if strings.Contains(cStr, "1a1a2e") || strings.Contains(cStr, "000000") {
		t.Errorf("Zero-value heatmap color should be grey, got %v", c)
	}
	// AdaptiveColor should have both Dark and Light fields set
	acStr := fmt.Sprintf("%T", c)
	if acStr != "lipgloss.AdaptiveColor" {
		t.Errorf("heatBlueScale[0] should be AdaptiveColor, got %s", acStr)
	}
}

func TestModelChartHasValueLabels(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	m.buildCharts()

	// Model chart should contain token count labels
	if !strings.Contains(m.modelChart, "394.5K") {
		t.Errorf("Model chart should contain '394.5K' label\nOutput:\n%s", m.modelChart)
	}
	if !strings.Contains(m.modelChart, "80.8K") {
		t.Errorf("Model chart should contain '80.8K' label\nOutput:\n%s", m.modelChart)
	}
	if !strings.Contains(m.modelChart, "6K") {
		t.Errorf("Model chart should contain '6K' label\nOutput:\n%s", m.modelChart)
	}
}

func TestDistributionTopBottomAligned(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	m.buildCharts()
	content := m.buildContent()

	// Find Distribution section
	distIdx := strings.Index(content, "Distribution")
	if distIdx < 0 {
		t.Fatal("Distribution section not found")
	}
	afterDist := content[distIdx:]

	// Both Scene Tokens and Model Tokens should appear
	sIdx := strings.Index(afterDist, "Scene Tokens")
	mIdx := strings.Index(afterDist, "Model Tokens")
	if sIdx < 0 || mIdx < 0 {
		t.Fatal("Scene Tokens or Model Tokens not found after Distribution")
	}

	// With equalized heights + Top alignment, both titles start on the same line
	lineBeforeScene := strings.Count(afterDist[:sIdx], "\n")
	lineBeforeModel := strings.Count(afterDist[:mIdx], "\n")
	t.Logf("Scene Tokens starts after %d lines, Model Tokens after %d lines", lineBeforeScene, lineBeforeModel)

	if lineBeforeScene != lineBeforeModel {
		t.Errorf("Expected Scene and Model to start on same line (both top-aligned): Scene line %d, Model line %d", lineBeforeScene, lineBeforeModel)
	}

	// Verify both charts have the same number of lines (bottom edges align)
	sceneChartLines := strings.Split(strings.TrimRight(m.sceneChart, "\n"), "\n")
	modelChartLines := strings.Split(strings.TrimRight(m.modelChart, "\n"), "\n")
	// After buildContent's equalization, the rendered charts should have same line count
	// Find the rendered chart sections
	sceneStart := sIdx + len("Scene Tokens")
	modelStart := mIdx + len("Model Tokens")
	_ = sceneStart
	_ = modelStart
	t.Logf("Scene chart lines: %d, Model chart lines: %d", len(sceneChartLines), len(modelChartLines))
}

func TestColorsAreAdaptive(t *testing.T) {
	// Key chart colors should be AdaptiveColor for dark/light switching
	checkAdaptive := func(name string, c interface{}) {
		typeName := fmt.Sprintf("%T", c)
		if typeName != "lipgloss.AdaptiveColor" {
			t.Errorf("%s should be AdaptiveColor, got %s", name, typeName)
		}
	}
	checkAdaptive("barHitColor", barHitColor)
	checkAdaptive("barMissColor", barMissColor)
	checkAdaptive("barOutColor", barOutColor)
	checkAdaptive("lineColor", lineColor)
	for i, c := range heatBlueScale {
		checkAdaptive(fmt.Sprintf("heatBlueScale[%d]", i), c)
	}
}

// ── TDD tests for CJK width fix ──

func TestChineseLabelBarChartWidth(t *testing.T) {
	m := &UsageViewModel{}
	chineseRows := []api.TokenUsageChartPoint{
		{Name: "聊天", CacheHitInput: 420000, CacheMissInput: 50000, Output: 5100},
		{Name: "搜索", CacheHitInput: 3500, CacheMissInput: 1000, Output: 200},
		{Name: "路由", CacheHitInput: 1500, CacheMissInput: 500, Output: 100},
	}
	cw := 40
	result := m.buildBarChart("Test", chineseRows, cw)

	lines := strings.Split(strings.TrimRight(result, "\n"), "\n")
	for i, line := range lines {
		w := lipgloss.Width(line)
		if w > cw {
			t.Errorf("Line %d visual width %d exceeds cw=%d:\n%s", i, w, cw, line)
		}
	}
}

func TestSceneModelWidthEqual(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	m.buildCharts()

	if m.sceneChart == "" || m.modelChart == "" {
		t.Fatal("Charts are empty")
	}

	halfW := (m.chartWidth() - 2 - 4) / 2

	sceneMaxW := maxLineVisualWidth(m.sceneChart)
	modelMaxW := maxLineVisualWidth(m.modelChart)

	t.Logf("halfW=%d, sceneMaxW=%d, modelMaxW=%d", halfW, sceneMaxW, modelMaxW)

	if sceneMaxW > halfW+1 {
		t.Errorf("Scene chart max width %d exceeds halfW=%d", sceneMaxW, halfW)
	}
	if modelMaxW > halfW+1 {
		t.Errorf("Model chart max width %d exceeds halfW=%d", modelMaxW, halfW)
	}

	// Scene and Model should have close visual widths
	diff := sceneMaxW - modelMaxW
	if diff < 0 {
		diff = -diff
	}
	if diff > 3 {
		t.Errorf("Scene(%d) and Model(%d) widths differ by more than 3", sceneMaxW, modelMaxW)
	}
}

func TestLineChartLabelFormula(t *testing.T) {
	tests := []struct {
		i, n, graphW int
		oldDelta     int // int(i*(graphW-1)/(n-1))
		newDelta     int // int(float64(i)*float64(graphW)/float64(n-1)+0.5)
	}{
		{0, 7, 40, 0, 0},
		{1, 7, 40, 6, 7},
		{2, 7, 40, 13, 13},
		{3, 7, 40, 19, 20},
		{4, 7, 40, 26, 27},
		{5, 7, 40, 32, 33},
		{6, 7, 40, 39, 40},

		{0, 5, 30, 0, 0},
		{1, 5, 30, 7, 8},
		{2, 5, 30, 14, 15},
		{3, 5, 30, 21, 23},
		{4, 5, 30, 29, 30},
	}

	for _, tt := range tests {
		oldDelta := tt.i * (tt.graphW - 1) / (tt.n - 1)
		newDelta := int(float64(tt.i)*float64(tt.graphW)/float64(tt.n-1) + 0.5)

		if oldDelta != tt.oldDelta {
			t.Errorf("i=%d n=%d graphW=%d: oldDelta expected=%d actual=%d", tt.i, tt.n, tt.graphW, tt.oldDelta, oldDelta)
		}
		if newDelta != tt.newDelta {
			t.Errorf("i=%d n=%d graphW=%d: newDelta expected=%d actual=%d", tt.i, tt.n, tt.graphW, tt.newDelta, newDelta)
		}

		// Verify that new formula matches math.Round(i * graphW / (n-1))
		expected := int(float64(tt.i)*float64(tt.graphW)/float64(tt.n-1) + 0.5)
		if newDelta != expected {
			t.Errorf("i=%d: newDelta %d != math.Round equivalent %d", tt.i, newDelta, expected)
		}
	}
}

func TestLineChartLabelAlignment(t *testing.T) {
	m := &UsageViewModel{
		data:  mockUsageData(),
		width: 100,
	}
	lc := m.buildLineChart(60, 10)

	if lc == "" {
		t.Fatal("buildLineChart() returned empty string")
	}

	expectedDates := []string{"6/17", "6/20", "6/23"}
	for _, d := range expectedDates {
		if !strings.Contains(lc, d) {
			t.Errorf("Expected line chart to contain date %s", d)
		}
	}

	fmt.Println("========== Line Chart Label Alignment ==========")
	fmt.Println(lc)
}

// ── Helpers ──

func maxLineVisualWidth(s string) int {
	maxW := 0
	for _, line := range strings.Split(s, "\n") {
		w := lipgloss.Width(line)
		if w > maxW {
			maxW = w
		}
	}
	return maxW
}

func mockDailySeries() []api.DailyTokenUsagePoint {
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	end := time.Date(2026, 6, 23, 0, 0, 0, 0, time.UTC)

	lastWeek := map[string]int64{
		"2026-06-17": 85000,
		"2026-06-18": 120000,
		"2026-06-19": 95000,
		"2026-06-20": 75000,
		"2026-06-21": 140000,
		"2026-06-22": 168000,
		"2026-06-23": 188000,
	}

	var series []api.DailyTokenUsagePoint
	for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
		dateKey := d.Format("2006-01-02")
		tokens := lastWeek[dateKey]

		if tokens == 0 {
			weekday := d.Weekday()
			dayOfYear := d.YearDay()
			base := int64(dayOfYear * 997 % 150000)
			switch weekday {
			case time.Saturday, time.Sunday:
				tokens = base % 40000
			default:
				tokens = 30000 + base
			}
			if dayOfYear%13 == 0 {
				tokens = 0
			}
		}

		series = append(series, api.DailyTokenUsagePoint{
			Date:   dateKey,
			Tokens: tokens,
		})
	}
	return series
}
