package status

import (
	"strings"
	"testing"

	"erii-cli/internal/api"

	"github.com/charmbracelet/x/ansi"
)

func testActivitySeries() []api.HourlyMessageCount {
	return []api.HourlyMessageCount{
		{HourLabel: "00:00", BotCount: 0, GroupCount: 2},
		{HourLabel: "01:00", BotCount: 1, GroupCount: 5},
		{HourLabel: "02:00", BotCount: 2, GroupCount: 8},
		{HourLabel: "03:00", BotCount: 1, GroupCount: 3},
		{HourLabel: "04:00", BotCount: 3, GroupCount: 10},
		{HourLabel: "05:00", BotCount: 2, GroupCount: 6},
		{HourLabel: "06:00", BotCount: 4, GroupCount: 12},
		{HourLabel: "07:00", BotCount: 3, GroupCount: 9},
		{HourLabel: "08:00", BotCount: 5, GroupCount: 14},
		{HourLabel: "09:00", BotCount: 4, GroupCount: 11},
		{HourLabel: "10:00", BotCount: 6, GroupCount: 16},
		{HourLabel: "11:00", BotCount: 5, GroupCount: 13},
	}
}

func TestActivityChartRendersHourlyRange(t *testing.T) {
	chart := ansi.Strip(buildActivityChart(testActivitySeries(), 80))

	for _, label := range []string{"00:00", "11:00"} {
		if !strings.Contains(chart, label) {
			t.Fatalf("activity chart missing %q:\n%s", label, chart)
		}
	}
}

func TestActivityChartAppearsBeforeSummary(t *testing.T) {
	summary := "The group discussed release planning."
	model := &StatusViewModel{
		status: &api.GroupStatus{
			HourlyMsgCounts: testActivitySeries(),
			Summary:         &summary,
		},
		width: 100,
	}

	content := ansi.Strip(model.buildContent())
	activityAt := strings.Index(content, "Chat Activity")
	summaryAt := strings.Index(content, "Summary")
	if activityAt < 0 || summaryAt < 0 || activityAt >= summaryAt {
		t.Fatalf("activity chart is not before summary: activity=%d summary=%d\n%s", activityAt, summaryAt, content)
	}
	for _, legend := range []string{"Bot", "Group"} {
		if !strings.Contains(content, legend) {
			t.Fatalf("activity legend missing %q:\n%s", legend, content)
		}
	}
}
