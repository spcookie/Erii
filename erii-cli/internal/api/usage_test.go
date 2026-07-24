package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetUsageDecodesDailyViews(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{
			"todayCacheHitInput": 10,
			"todayCacheMissInput": 20,
			"todayOutput": 30,
			"todayCost": 0.1,
			"priceUnit": "USD",
			"pricing": {
				"lite": {"inputCacheHit":0.01875,"inputCacheMiss":0.075,"output":0.3},
				"flash": {"inputCacheHit":0.025,"inputCacheMiss":0.1,"output":0.4},
				"pro": {"inputCacheHit":0.3125,"inputCacheMiss":1.25,"output":10}
			},
			"totalCacheHitInput": 10,
			"totalCacheMissInput": 20,
			"totalOutput": 30,
			"totalCost": 0.1,
			"todayCacheHitRate": 33.33,
			"sceneBars": [],
			"modelBars": [],
			"dailySeries": [],
			"dailyViews": [{
				"date": "2026-07-21",
				"cacheHitInput": 10,
				"cacheMissInput": 20,
				"output": 30,
				"cost": 0.1,
				"cacheHitRate": 33.33,
				"sceneBars": [{"name":"聊天","cacheHitInput":10,"cacheMissInput":20,"output":30}],
				"modelBars": [{"name":"Flash","cacheHitInput":10,"cacheMissInput":20,"output":30}]
			}]
		}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.GetUsage("bot-a", "group-1")
	if err != nil {
		t.Fatalf("GetUsage returned error: %v", err)
	}
	if gotURI != "/api/usage?botId=bot-a&groupId=group-1" {
		t.Fatalf("uri = %q, want scoped usage URI", gotURI)
	}
	if len(result.DailyViews) != 1 {
		t.Fatalf("daily views = %d, want 1", len(result.DailyViews))
	}
	if result.Pricing == nil {
		t.Fatal("pricing was not decoded")
	}
	if result.Pricing.Lite.InputCacheHit != 0.01875 || result.Pricing.Flash.InputCacheMiss != 0.1 || result.Pricing.Pro.Output != 10 {
		t.Fatalf("decoded pricing = %+v", result.Pricing)
	}
	day := result.DailyViews[0]
	if day.Date != "2026-07-21" || day.CacheHitInput != 10 || day.CacheMissInput != 20 || day.Output != 30 {
		t.Fatalf("decoded daily view = %+v", day)
	}
	if len(day.SceneBars) != 1 || day.SceneBars[0].Name != "聊天" {
		t.Fatalf("decoded scene bars = %+v", day.SceneBars)
	}
}

func TestGetUsageAcceptsLegacyResponseWithoutDailyViews(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{
			"todayCacheHitInput": 10,
			"todayCacheMissInput": 20,
			"todayOutput": 30,
			"todayCost": 0.1,
			"priceUnit": "USD",
			"totalCacheHitInput": 10,
			"totalCacheMissInput": 20,
			"totalOutput": 30,
			"totalCost": 0.1,
			"todayCacheHitRate": 33.33,
			"sceneBars": [],
			"modelBars": [],
			"dailySeries": []
		}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.GetUsage("", "")
	if err != nil {
		t.Fatalf("GetUsage returned error: %v", err)
	}
	if len(result.DailyViews) != 0 {
		t.Fatalf("legacy response daily views = %d, want 0 for TUI fallback", len(result.DailyViews))
	}
	if result.Pricing != nil {
		t.Fatalf("legacy response pricing = %+v, want nil", result.Pricing)
	}
}
