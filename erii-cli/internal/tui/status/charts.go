package status

import (
	"fmt"
	"time"

	"erii-cli/internal/api"
	style "erii-cli/internal/ui/theme"

	"github.com/NimbleMarkets/ntcharts/canvas"
	"github.com/NimbleMarkets/ntcharts/canvas/runes"
	"github.com/NimbleMarkets/ntcharts/linechart/timeserieslinechart"
	"github.com/charmbracelet/lipgloss"
)

const activityGroupDataSet = "group"

var (
	activityAxisStyle  = lipgloss.NewStyle().Foreground(style.TextMuted)
	activityLabelStyle = lipgloss.NewStyle().Foreground(style.TextMuted)
	activityBotStyle   = lipgloss.NewStyle().Foreground(style.ChartBlue)
	activityGroupStyle = lipgloss.NewStyle().Foreground(style.ChartCyan)
)

func buildActivityChart(series []api.HourlyMessageCount, width int) string {
	if len(series) == 0 {
		return style.Muted("No activity data available")
	}
	if width < 40 {
		width = 40
	}

	const chartHeight = 10
	baseTime := time.Date(2000, time.January, 1, 0, 0, 0, 0, time.UTC)
	lastTime := baseTime.Add(time.Duration(len(series)-1) * time.Hour)
	if len(series) == 1 {
		lastTime = baseTime.Add(time.Hour)
	}

	maxValue := 1.0
	for _, point := range series {
		if value := float64(point.BotCount); value > maxValue {
			maxValue = value
		}
		if value := float64(point.GroupCount); value > maxValue {
			maxValue = value
		}
	}

	chart := timeserieslinechart.New(width, chartHeight,
		timeserieslinechart.WithTimeRange(baseTime, lastTime),
		timeserieslinechart.WithYRange(0, maxValue),
		timeserieslinechart.WithAxesStyles(activityAxisStyle, activityLabelStyle),
		timeserieslinechart.WithStyle(activityBotStyle),
		timeserieslinechart.WithDataSetStyle(activityGroupDataSet, activityGroupStyle),
		timeserieslinechart.WithLineStyle(runes.ArcLineStyle),
		timeserieslinechart.WithDataSetLineStyle(activityGroupDataSet, runes.ArcLineStyle),
		timeserieslinechart.WithXYSteps(1, 2),
		timeserieslinechart.WithYLabelFormatter(func(_ int, value float64) string {
			return fmt.Sprintf("%.0f", value)
		}),
	)

	for i, point := range series {
		timestamp := baseTime.Add(time.Duration(i) * time.Hour)
		chart.Push(timeserieslinechart.TimePoint{Time: timestamp, Value: float64(point.BotCount)})
		chart.PushDataSet(activityGroupDataSet, timeserieslinechart.TimePoint{
			Time:  timestamp,
			Value: float64(point.GroupCount),
		})
	}
	chart.DrawBrailleAll()
	drawActivityLabels(&chart, series, baseTime, lastTime)

	return chart.View()
}

func drawActivityLabels(
	chart *timeserieslinechart.Model,
	series []api.HourlyMessageCount,
	firstTime time.Time,
	lastTime time.Time,
) {
	originX := chart.Origin().X
	labelY := chart.Origin().Y + 1
	for x := originX; x < chart.Canvas.Width(); x++ {
		chart.Canvas.SetCell(canvas.Point{X: x, Y: labelY}, canvas.NewCell(' '))
	}

	rangeSeconds := lastTime.Unix() - firstTime.Unix()
	if rangeSeconds <= 0 {
		rangeSeconds = 1
	}
	graphWidth := float64(chart.GraphWidth())
	lastLabelEnd := originX - 2

	for i, point := range series {
		timestamp := firstTime.Add(time.Duration(i) * time.Hour)
		centerX := originX + int(float64(timestamp.Unix()-firstTime.Unix())/float64(rangeSeconds)*graphWidth+0.5)
		label := point.HourLabel
		if label == "" {
			label = timestamp.Format("15:04")
		}
		labelX := centerX - len(label)/2

		if i > 0 && i < len(series)-1 && labelX < lastLabelEnd+1 {
			continue
		}
		if labelX < lastLabelEnd+1 {
			labelX = lastLabelEnd + 1
		}
		if labelX+len(label) > chart.Canvas.Width() {
			labelX = chart.Canvas.Width() - len(label)
		}
		if labelX < originX {
			labelX = originX
		}

		chart.Canvas.SetStringWithStyle(
			canvas.Point{X: labelX, Y: labelY},
			label,
			activityLabelStyle,
		)
		lastLabelEnd = labelX + len(label)
	}
}
