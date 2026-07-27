package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetFactsSendsServerListOptions(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		if got := query.Get("offset"); got != "40" {
			t.Errorf("offset = %q, want 40", got)
		}
		if got := query.Get("limit"); got != "20" {
			t.Errorf("limit = %q, want 20", got)
		}
		if got := query.Get("query"); got != "杭州 user" {
			t.Errorf("query = %q, want search text", got)
		}
		if got := query.Get("sortBy"); got != "keyword" {
			t.Errorf("sortBy = %q, want keyword", got)
		}
		if got := query.Get("order"); got != "desc" {
			t.Errorf("order = %q, want desc", got)
		}
		_ = json.NewEncoder(w).Encode(PaginatedResponse[FactRecord]{
			Items:  []FactRecord{},
			Total:  42,
			Offset: 40,
			Limit:  20,
		})
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	response, err := client.GetFacts("bot", "group", ListOptions{
		Offset: 40,
		Limit:  20,
		Query:  "杭州 user",
		SortBy: "keyword",
		Order:  "desc",
	})
	if err != nil {
		t.Fatalf("GetFacts returned error: %v", err)
	}
	if response.Total != 42 {
		t.Fatalf("total = %d, want 42", response.Total)
	}
}
