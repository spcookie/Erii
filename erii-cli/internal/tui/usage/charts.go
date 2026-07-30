package usage

import (
	"erii-cli/internal/api"
	"sort"
	"strings"
	"time"

	style "erii-cli/internal/ui/theme"

	"github.com/NimbleMarkets/ntcharts/barchart"
	"github.com/NimbleMarkets/ntcharts/canvas"
	"github.com/NimbleMarkets/ntcharts/canvas/runes"
	"github.com/NimbleMarkets/ntcharts/linechart/timeserieslinechart"
	"github.com/charmbracelet/lipgloss"
	"github.com/mattn/go-runewidth"
)

const (
	segHit                = "Hit"
	segMiss               = "Miss"
	segOutput             = "Output"
	noDataMsg             = "No daily data available"
	heatmapCellWidth      = 2
	heatmapCellHeight     = 1
	heatmapMaxMonths      = 6
	heatmapLegendMinWidth = 21
)

func truncateModelName(name string, maxLen int) string {
	name = strings.TrimPrefix(name, "claude-")
	if len(name) > maxLen {
		name = name[:maxLen-1] + "…"
	}
	return name
}

// ── Horizontal Stacked Bar Chart ──

func (m *UsageViewModel) buildBarChart(title string, rows []api.TokenUsageChartPoint, cw int) string {
	if len(rows) == 0 {
		return ""
	}

	if cw < 30 {
		cw = 40
	}

	// CJK runes occupy 1 canvas cell but render as 2 terminal cells.
	// Shrink canvas width to compensate so visual output fits within cw.
	adjCW := cw
	if cw > 0 {
		maxCJK := 0
		for _, r := range rows {
			cjk := 0
			for _, ch := range r.Name {
				if runewidth.RuneWidth(ch) == 2 {
					cjk++
				}
			}
			if cjk > maxCJK {
				maxCJK = cjk
			}
		}
		adjCW = cw - maxCJK
		if adjCW < 10 {
			adjCW = 10
		}
	}

	var maxTotal float64
	var barData []barchart.BarData
	var names []string
	maxNameRunes := 0
	for _, r := range rows {
		t := float64(r.CacheHitInput + r.CacheMissInput + r.Output)
		if t > maxTotal {
			maxTotal = t
		}
		names = append(names, r.Name)
		rc := len([]rune(r.Name))
		if rc > maxNameRunes {
			maxNameRunes = rc
		}
	}
	for _, r := range rows {
		totalStr := compactNumber(r.CacheHitInput + r.CacheMissInput + r.Output)
		// Use ASCII-only label so ntcharts origin.X = visual label width.
		// CJK names are drawn manually on the canvas after Draw().
		label := strings.Repeat(" ", maxNameRunes) + " " + totalStr
		barData = append(barData, barchart.BarData{
			Label: label,
			Values: []barchart.BarValue{
				{Name: segHit, Value: float64(r.CacheHitInput), Style: barHitStyle},
				{Name: segMiss, Value: float64(r.CacheMissInput), Style: barMissStyle},
				{Name: segOutput, Value: float64(r.Output), Style: barOutStyle},
			},
		})
	}
	if maxTotal == 0 {
		maxTotal = 1
	}

	barH := len(rows) + 1
	if barH < 3 {
		barH = 3
	}

	bc := barchart.New(adjCW, barH,
		barchart.WithHorizontalBars(),
		barchart.WithStyles(axisStyle, labelStyle),
		barchart.WithDataSet(barData),
		barchart.WithBarGap(0),
	)
	bc.Resize(adjCW, barH)
	bc.Draw()

	lastY := bc.Canvas.Height() - 1
	for x := 0; x < bc.Canvas.Width(); x++ {
		bc.Canvas.SetCell(canvas.Point{X: x, Y: lastY}, canvas.NewCell(' '))
	}

	for i, name := range names {
		if i < barH {
			bc.Canvas.SetString(canvas.Point{X: 0, Y: i}, name)
		}
	}

	block := "█"

	var out strings.Builder
	out.WriteString(sectionTitle(title))
	out.WriteString("\n")
	out.WriteString(" " + barHitStyle.Render(block) + " Input Hit  " +
		barMissStyle.Render(block) + " Input Miss  " +
		barOutStyle.Render(block) + " Output")
	out.WriteString("\n\n")
	out.WriteString(bc.View())

	return out.String()
}

// ── Vertical Stacked Column Chart (pure ntcharts) ──

func (m *UsageViewModel) buildColumnChart(title string, rows []api.TokenUsageChartPoint, cw int) string {
	if len(rows) == 0 {
		return ""
	}

	if cw < 30 {
		cw = 40
	}

	chartH := 10

	var maxTotal float64
	var barData []barchart.BarData
	var tokenLabels []string
	var modelLabels []string
	for _, r := range rows {
		t := float64(r.CacheHitInput + r.CacheMissInput + r.Output)
		if t > maxTotal {
			maxTotal = t
		}
		totalStr := compactNumber(r.CacheHitInput + r.CacheMissInput + r.Output)
		tokenLabels = append(tokenLabels, totalStr)
		modelLabels = append(modelLabels, truncateModelName(r.Name, 5))
		barData = append(barData, barchart.BarData{
			Label: truncateModelName(r.Name, 8),
			Values: []barchart.BarValue{
				{Name: segHit, Value: float64(r.CacheHitInput), Style: barHitStyle},
				{Name: segMiss, Value: float64(r.CacheMissInput), Style: barMissStyle},
				{Name: segOutput, Value: float64(r.Output), Style: barOutStyle},
			},
		})
	}
	if maxTotal == 0 {
		maxTotal = 1
	}

	bc := barchart.New(cw, chartH,
		barchart.WithStyles(axisStyle, labelStyle),
		barchart.WithDataSet(barData),
		barchart.WithBarGap(0),
	)
	bc.Draw()

	barW := bc.BarWidth()
	barGap := bc.BarGap()
	// Use ntcharts' own axis/label rows so bars are not overwritten
	axisY := bc.Canvas.Height() - 2
	labelY := bc.Canvas.Height() - 1

	for x := 0; x < bc.Canvas.Width(); x++ {
		bc.Canvas.SetCell(canvas.Point{X: x, Y: axisY}, canvas.NewCell(' '))
		bc.Canvas.SetCell(canvas.Point{X: x, Y: labelY}, canvas.NewCell(' '))
	}

	graphEndX := len(barData) * barW
	if graphEndX > bc.Canvas.Width() {
		graphEndX = bc.Canvas.Width()
	}
	for x := 0; x < graphEndX; x++ {
		bc.Canvas.SetCell(canvas.Point{X: x, Y: axisY}, canvas.NewCellWithStyle('─', axisStyle))
	}

	for i := range barData {
		centerX := i*(barW+barGap) + barW/2

		combined := tokenLabels[i] + " " + modelLabels[i]
		cx := centerX - len(combined)/2
		if cx < 0 {
			cx = 0
		}
		if cx+len(combined) > bc.Canvas.Width() {
			cx = bc.Canvas.Width() - len(combined)
		}
		bc.Canvas.SetStringWithStyle(canvas.Point{X: cx, Y: labelY}, combined, labelStyle)
	}

	block := "█"

	var out strings.Builder
	out.WriteString(sectionTitle(title))
	out.WriteString("\n")
	out.WriteString(" " + barHitStyle.Render(block) + " Input Hit  " +
		barMissStyle.Render(block) + " Input Miss  " +
		barOutStyle.Render(block) + " Output")
	out.WriteString("\n\n")
	out.WriteString(bc.View())

	return out.String()
}

// ── Daily Heatmap ──

type dailyHeatmapLayout struct {
	startDate   time.Time
	endDate     time.Time
	weekStart   time.Time
	numWeeks    int
	monthCount  int
	canvasWidth int
}

func calculateDailyHeatmapLayout(lastDate time.Time, maxWidth int) dailyHeatmapLayout {
	endDate := time.Date(lastDate.Year(), lastDate.Month()+1, 0, 0, 0, 0, 0, time.UTC)
	selected := dailyHeatmapLayout{}

	for monthCount := heatmapMaxMonths; monthCount >= 1; monthCount-- {
		startDate := time.Date(
			lastDate.Year(),
			lastDate.Month()-time.Month(monthCount-1),
			1,
			0,
			0,
			0,
			0,
			time.UTC,
		)
		weekStart := startDate
		for weekStart.Weekday() != time.Sunday {
			weekStart = weekStart.AddDate(0, 0, -1)
		}
		numWeeks := int(endDate.Sub(weekStart).Hours()/24/7) + 1
		gridWidth := numWeeks * heatmapCellWidth

		selected = dailyHeatmapLayout{
			startDate:   startDate,
			endDate:     endDate,
			weekStart:   weekStart,
			numWeeks:    numWeeks,
			monthCount:  monthCount,
			canvasWidth: max(gridWidth, heatmapLegendMinWidth),
		}
		if gridWidth <= maxWidth {
			break
		}
	}

	return selected
}

func (m *UsageViewModel) buildHeatmap(cw int) (string, int) {
	series := m.data.DailySeries
	if len(series) == 0 {
		return "", 0
	}

	tokenByDate := make(map[string]int64)
	var maxV int64
	for _, p := range series {
		tokenByDate[p.Date] = p.Tokens
		if p.Tokens > maxV {
			maxV = p.Tokens
		}
	}
	if maxV == 0 {
		maxV = 1
	}

	lastDate, err := time.Parse("2006-01-02", series[len(series)-1].Date)
	if err != nil {
		return "", 0
	}
	layout := calculateDailyHeatmapLayout(lastDate, cw)
	heatW := layout.canvasWidth
	offsetX := (heatW - layout.numWeeks*heatmapCellWidth) / 2

	dayRows := 7 * heatmapCellHeight
	heatH := 1 + dayRows + 1 + 1 // month labels + day cells + spacer + legend
	cellBaseY := 1               // day cells start at row 1 (row 0 = month labels)
	legendY := 1 + dayRows + 1   // after cells + spacer

	cv := canvas.New(heatW, heatH)
	for y := 0; y < heatH; y++ {
		for x := 0; x < heatW; x++ {
			cv.SetCell(canvas.Point{X: x, Y: y}, canvas.NewCell(' '))
		}
	}

	type monthLabel struct {
		col  int
		name string
	}
	var monthLabels []monthLabel
	lastMonth := -1

	dayCursor := layout.weekStart
	for !dayCursor.After(layout.endDate) {
		col := int(dayCursor.Sub(layout.weekStart).Hours() / 24 / 7)
		row := int(dayCursor.Weekday())

		startX := offsetX + col*heatmapCellWidth
		endX := startX + heatmapCellWidth
		canvasY := cellBaseY + row*heatmapCellHeight

		dateKey := dayCursor.Format("2006-01-02")
		tokens := tokenByDate[dateKey]

		inRange := !dayCursor.Before(layout.startDate) && !dayCursor.After(layout.endDate)
		colorIdx := 0
		if inRange && tokens > 0 {
			frac := float64(tokens) / float64(maxV)
			switch {
			case frac >= 0.72:
				colorIdx = 8
			case frac >= 0.52:
				colorIdx = 7
			case frac >= 0.35:
				colorIdx = 6
			case frac >= 0.22:
				colorIdx = 5
			case frac >= 0.13:
				colorIdx = 4
			case frac >= 0.07:
				colorIdx = 3
			case frac >= 0.03:
				colorIdx = 2
			default:
				colorIdx = 1
			}
		}
		borderIdx := colorIdx
		if borderIdx > 0 {
			borderIdx--
		}

		bg := heatBlueScale[colorIdx]
		borderBg := heatBlueScale[borderIdx]
		cellStyle := lipgloss.NewStyle().Background(bg).Foreground(bg)
		borderStyle := lipgloss.NewStyle().Background(borderBg).Foreground(borderBg)

		for dy := 0; dy < heatmapCellHeight; dy++ {
			cy := canvasY + dy
			if cy >= heatH {
				break
			}
			for x := startX; x < endX && x < heatW; x++ {
				if x == endX-1 && borderIdx != colorIdx {
					cv.SetCell(canvas.Point{X: x, Y: cy}, canvas.NewCellWithStyle('█', borderStyle))
				} else {
					cv.SetCell(canvas.Point{X: x, Y: cy}, canvas.NewCellWithStyle('█', cellStyle))
				}
			}
		}

		month := int(dayCursor.Month())
		if month != lastMonth &&
			!dayCursor.Before(layout.startDate) &&
			!dayCursor.After(layout.endDate) {
			lastMonth = month
			monthLabels = append(monthLabels, monthLabel{
				col:  col,
				name: dayCursor.Format("Jan"),
			})
		}

		dayCursor = dayCursor.AddDate(0, 0, 1)
	}

	sort.Slice(monthLabels, func(i, j int) bool {
		return monthLabels[i].col < monthLabels[j].col
	})

	// Draw month labels on canvas row 0
	labelRunes := []rune(strings.Repeat(" ", heatW))
	for i, ml := range monthLabels {
		pos := offsetX + ml.col*heatmapCellWidth
		nameRunes := []rune(ml.name)
		limit := heatW
		if i+1 < len(monthLabels) {
			limit = offsetX + monthLabels[i+1].col*heatmapCellWidth
		}
		if pos+len(nameRunes) > limit {
			continue
		}
		for j, r := range nameRunes {
			idx := pos + j
			if idx < len(labelRunes) {
				labelRunes[idx] = r
			}
		}
	}
	cv.SetStringWithStyle(canvas.Point{X: 0, Y: 0}, string(labelRunes), style.MutedStyle)

	// Draw color legend below cells (with spacer row)
	legendWidth := 5 + len(heatBlueScale) + 5
	legendX := (heatW - legendWidth) / 2
	if legendX < 0 {
		legendX = 0
	}
	cv.SetStringWithStyle(canvas.Point{X: legendX, Y: legendY}, "Less ", style.MutedStyle)
	legendX += 5
	for _, c := range heatBlueScale {
		cv.SetCell(canvas.Point{X: legendX, Y: legendY}, canvas.NewCellWithStyle(' ', lipgloss.NewStyle().Background(c)))
		legendX++
	}
	cv.SetStringWithStyle(canvas.Point{X: legendX, Y: legendY}, " More", style.MutedStyle)

	var out strings.Builder
	out.WriteString(cv.View())

	return out.String(), heatH
}

// ── Time Series Line Chart ──

func (m *UsageViewModel) buildLineChart(cw int, chartH int) string {
	series := m.data.DailySeries
	if cw < 30 {
		cw = 40
	}

	if len(series) == 0 {
		return style.Muted(noDataMsg)
	}

	if len(series) > 7 {
		series = series[len(series)-7:]
	}

	var maxV float64
	timePoints := make([]timeserieslinechart.TimePoint, 0, len(series))
	for _, p := range series {
		t, err := time.Parse("2006-01-02", p.Date)
		if err != nil {
			continue
		}
		v := float64(p.Tokens)
		if v > maxV {
			maxV = v
		}
		timePoints = append(timePoints, timeserieslinechart.TimePoint{Time: t, Value: v})
	}
	if maxV == 0 {
		maxV = 1
	}
	if len(timePoints) == 0 {
		return style.Muted(noDataMsg)
	}

	if chartH < 8 {
		chartH = 8
	}

	firstDate := timePoints[0].Time
	lastDate := timePoints[len(timePoints)-1].Time

	yLabelFmt := func(i int, v float64) string {
		return compactNumber(int64(v))
	}

	tslc := timeserieslinechart.New(cw, chartH,
		timeserieslinechart.WithTimeRange(firstDate, lastDate),
		timeserieslinechart.WithYRange(0, maxV),
		timeserieslinechart.WithAxesStyles(axisStyle, labelStyle),
		timeserieslinechart.WithStyle(lineStyle),
		timeserieslinechart.WithLineStyle(runes.ArcLineStyle),
		timeserieslinechart.WithXYSteps(1, 2), // XStep>0 keeps axis, we replace labels
		timeserieslinechart.WithYLabelFormatter(yLabelFmt),
	)

	for _, tp := range timePoints {
		tslc.Push(tp)
	}

	tslc.DrawBraille()

	ox := tslc.Origin().X
	labelY := tslc.Origin().Y + 1
	for x := ox; x < tslc.Canvas.Width(); x++ {
		tslc.Canvas.SetCell(canvas.Point{X: x, Y: labelY}, canvas.NewCell(' '))
	}

	viewMinX := tslc.ViewMinX()
	viewMaxX := tslc.ViewMaxX()
	gw := float64(tslc.GraphWidth())

	dateStrs := make([]string, len(timePoints))
	for i, tp := range timePoints {
		parts := strings.Split(tp.Time.Format("2006-01-02"), "-")
		if len(parts) == 3 {
			dateStrs[i] = strings.TrimLeft(parts[1], "0") + "/" + parts[2]
		}
	}

	lastEnd := ox - 2
	for i, tp := range timePoints {
		// Always show first and last; skip intermediate if too close to previous
		centerX := ox + int((float64(tp.Time.Unix())-viewMinX)/(viewMaxX-viewMinX)*gw+0.5)
		dateStr := dateStrs[i]
		labelX := centerX - len(dateStr)/2

		if i > 0 && i < len(timePoints)-1 && labelX < lastEnd+1 {
			continue
		}
		if labelX < lastEnd+1 {
			labelX = lastEnd + 1
		}
		if labelX+len(dateStr) > tslc.Canvas.Width() {
			labelX = tslc.Canvas.Width() - len(dateStr)
		}
		tslc.Canvas.SetStringWithStyle(
			canvas.Point{X: labelX, Y: labelY},
			dateStr,
			labelStyle,
		)
		lastEnd = labelX + len(dateStr)
	}

	var out strings.Builder
	out.WriteString(tslc.View())

	return out.String()
}
