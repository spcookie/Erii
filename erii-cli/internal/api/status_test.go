package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetGroupStatusDecodesHourlyMessageCounts(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{
			"botId": "bot-a",
			"groupId": "group-1",
			"hourlyMsgCounts": [
				{"hourLabel":"10:00","botCount":2,"groupCount":8},
				{"hourLabel":"11:00","botCount":3,"groupCount":13}
			]
		}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.GetGroupStatus("bot-a", "group-1")
	if err != nil {
		t.Fatalf("GetGroupStatus returned error: %v", err)
	}
	if gotURI != "/api/bot/bot-a/group/group-1/status" {
		t.Fatalf("uri = %q, want group status URI", gotURI)
	}
	if len(result.HourlyMsgCounts) != 2 {
		t.Fatalf("hourly message counts = %d, want 2", len(result.HourlyMsgCounts))
	}
	if got := result.HourlyMsgCounts[1]; got.HourLabel != "11:00" || got.BotCount != 3 || got.GroupCount != 13 {
		t.Fatalf("decoded hourly message count = %+v", got)
	}
}
