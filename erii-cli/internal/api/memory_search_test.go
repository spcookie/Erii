package api

import (
	"encoding/json"
	"testing"
)

func TestMemoryGraphSearchResponseJSON(t *testing.T) {
	raw := []byte(`{
		"query": "杭州",
		"seedResults": [
			{"fact": {"id": 1, "botId": "bot-a", "groupId": "group-a", "keyword": "seed", "description": "seed fact", "entities": ["杭州"], "subjects": "user-a", "scopeType": "USER", "createdAt": "2026-07-03T00:00:00", "validFrom": "2026-07-03T00:00:00", "validTo": null, "vectorId": "v1"}, "score": 0.91, "vectorId": "v1", "source": "seed"}
		],
		"expandedResults": [
			{"fact": {"id": 2, "botId": "bot-a", "groupId": "group-a", "keyword": "expanded", "description": "expanded fact", "entities": ["杭州", "西湖"], "subjects": "user-a", "scopeType": "USER", "createdAt": "2026-07-03T00:00:00", "validFrom": "2026-07-03T00:00:00", "validTo": null, "vectorId": null}, "score": null, "vectorId": null, "source": "expanded"}
		],
		"nodes": [
			{"id": "fact:1", "type": "fact", "label": "#1 seed", "source": "seed"},
			{"id": "entity:杭州", "type": "entity", "label": "杭州", "source": ""}
		],
		"edges": [
			{"from": "fact:1", "to": "entity:杭州", "label": "involves"}
		]
	}`)

	var response MemoryGraphSearchResponse
	if err := json.Unmarshal(raw, &response); err != nil {
		t.Fatalf("unmarshal graph response: %v", err)
	}

	if response.Query != "杭州" {
		t.Fatalf("query = %q", response.Query)
	}
	if got := response.SeedResults[0].Fact.Entities[0]; got != "杭州" {
		t.Fatalf("seed entity = %q", got)
	}
	if response.ExpandedResults[0].Score != nil {
		t.Fatalf("expanded score should be nil")
	}
	if got := response.Edges[0].Label; got != "involves" {
		t.Fatalf("edge label = %q", got)
	}
}
