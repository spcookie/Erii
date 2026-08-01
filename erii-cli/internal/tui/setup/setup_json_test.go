package setup

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSetupJSONSupportsProviderPresetsAndAdvancedDefaults(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", ".conf", "setup.json"))
	if err != nil {
		t.Fatalf("cannot read setup.json: %v", err)
	}

	var sf SetupFile
	if err := json.Unmarshal(data, &sf); err != nil {
		t.Fatalf("cannot parse setup.json: %v", err)
	}

	if len(sf.Providers) < 4 {
		t.Fatalf("expected at least 4 provider presets, got %d", len(sf.Providers))
	}

	anthropicPresets := map[string]bool{
		"deepseek":           false,
		"kimi":               false,
		"kimi-code":          false,
		"mimo":               false,
		"mimo-token-plan":    false,
		"minimax":            false,
		"minimax-token-plan": false,
		"glm":                false,
		"qwen":               false,
		"bytedance-seed":     false,
	}
	for _, p := range sf.Providers {
		if _, ok := anthropicPresets[p.Key]; !ok {
			continue
		}
		anthropicPresets[p.Key] = true
		if p.API != "anthropic" {
			t.Errorf("%s api = %q, want anthropic", p.Key, p.API)
		}
		defaults := sf.Defaults.LLM[p.Key]
		if defaults.Settings["messages"] != "v1/messages" {
			t.Errorf("%s messages path = %q, want v1/messages", p.Key, defaults.Settings["messages"])
		}
	}
	for key, found := range anthropicPresets {
		if !found {
			t.Errorf("%s provider preset missing", key)
		}
	}
	if !sf.Defaults.LLMCapability.Default.Tools {
		t.Fatal("llm capability defaults missing")
	}
	if sf.Defaults.LLMUsagePricing.PriceUnit == "" {
		t.Fatal("llm usage pricing defaults missing")
	}
	if len(sf.ToolProviders.STT) != 1 {
		t.Fatalf("expected one STT provider preset, got %d", len(sf.ToolProviders.STT))
	}
	stt := sf.ToolProviders.STT[0]
	if stt.Name != "volcengine-flash" || stt.Model != "volc.bigasr.auc_turbo" || stt.URL == "" {
		t.Fatalf("invalid STT provider preset: %+v", stt)
	}
}

func TestOldSetupJSONDefaultsRemainUsable(t *testing.T) {
	sf := SetupFile{
		Providers: []Provider{{Name: "OpenAI", Key: "openai", BaseURL: "https://api.openai.com/v1"}},
		Defaults: DefaultsConfig{
			LLM: map[string]LLMDefaults{
				"openai": {ModelMode: "all", AllModel: "gpt-test"},
			},
		},
	}

	for i := range sf.Providers {
		if sf.Providers[i].API == "" {
			sf.Providers[i].API = providerAPI(sf.Providers[i])
		}
	}
	m := newModel(sf.Providers, sf.Defaults, ToolProvidersConfig{})

	if providerAPI(m.data.Providers[0]) != "openai" {
		t.Fatalf("provider api fallback failed: %q", providerAPI(m.data.Providers[0]))
	}
	if m.data.LLMUsagePricing.PriceUnit != "USD" {
		t.Fatalf("usage pricing default fallback failed: %q", m.data.LLMUsagePricing.PriceUnit)
	}
}
