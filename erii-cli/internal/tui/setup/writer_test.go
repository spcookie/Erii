package setup

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func makeTestData() *SetupData {
	return &SetupData{
		Providers: []Provider{
			{Name: "OpenAI", Key: "openai", API: "openai", BaseURL: "https://api.openai.com/v1"},
			{Name: "Anthropic", Key: "anthropic", API: "anthropic", BaseURL: "https://api.anthropic.com"},
		},
		SelectedProv:      0,
		APIKey:            "openai-api-key",
		BaseURL:           "https://custom.openai.com",
		ModelMode:         "separate",
		LiteModel:         "gpt-4.1-nano",
		FlashModel:        "gpt-4.1-mini",
		ProModel:          "gpt-4.1",
		EmbeddingEnabled:  true,
		EmbeddingAPIKey:   "test-embedding-key",
		EmbeddingURL:      "https://embed.test.com",
		EmbeddingProvider: "bytedance",
		EmbeddingModel:    "test-model",
		SearchEnabled:     true,
		SearchAPIKey:      "test-search-key",
		SearchURL:         "https://search.test.com",
		SearchProvider:    "exa",
		STTEnabled:        true,
		STTAPIKey:         "test-stt-key",
		STTURL:            "https://stt.test.com/recognize/flash",
		STTProvider:       "volcengine-flash",
		STTModel:          "volc.bigasr.auc_turbo",
		VisionEnabled:     true,
		VisionAPIKey:      "test-vision-key",
		VisionURL:         "https://vision.test.com",
		VisionProvider:    "minimax",
		BrowserEnabled:    true,
		BrowserDownload:   true,
		PlaywrightURL:     "ws://playwright.test.com:3000",
		ExternalHost:      "external.test.com",
		ProxyEnabled:      true,
		HTTPProxy:         "http://proxy.test.com:8080",
		SOCKSProxy:        "socks5://proxy.test.com:1080",
		BotWS:             "ws://bot.test.com:3001",
		BotToken:          "test-bot-token",
		EnableGroups:      "123456,789012",
		ServerUsername:    defaultServerUsername,
		ServerPassword:    defaultServerPassword,
		LLMSettings: map[string]string{
			"chat-completions": "v1/chat/completions",
			"responses-api":    "v1/responses",
			"embeddings":       "v1/embeddings",
			"moderations":      "v1/moderations",
			"models":           "v1/models",
		},
		LLMCapability:   defaultLLMCapability(),
		LLMUsagePricing: defaultLLMUsagePricing(),
	}
}

// ---- buildEnvVars tests ----

func TestBuildEnvVars(t *testing.T) {
	d := makeTestData()
	vars := buildEnvVars(d)

	if vars["OPENAI_API_KEY"] != "openai-api-key" {
		t.Errorf("OPENAI_API_KEY = %q, want %q", vars["OPENAI_API_KEY"], "openai-api-key")
	}
	if vars["EMBEDDING_API_KEY"] != "test-embedding-key" {
		t.Errorf("EMBEDDING_API_KEY = %q, want %q", vars["EMBEDDING_API_KEY"], "test-embedding-key")
	}
	if vars["SEARCH_API_KEY"] != "test-search-key" {
		t.Errorf("SEARCH_API_KEY = %q, want %q", vars["SEARCH_API_KEY"], "test-search-key")
	}
	if vars["STT_API_KEY"] != "test-stt-key" {
		t.Errorf("STT_API_KEY = %q, want %q", vars["STT_API_KEY"], "test-stt-key")
	}
	if vars["VISION_API_KEY"] != "test-vision-key" {
		t.Errorf("VISION_API_KEY = %q, want %q", vars["VISION_API_KEY"], "test-vision-key")
	}
	if vars["HTTP_PROXY"] != "http://proxy.test.com:8080" {
		t.Errorf("HTTP_PROXY = %q, want %q", vars["HTTP_PROXY"], "http://proxy.test.com:8080")
	}
	if vars["SOCKS_PROXY"] != "socks5://proxy.test.com:1080" {
		t.Errorf("SOCKS_PROXY = %q, want %q", vars["SOCKS_PROXY"], "socks5://proxy.test.com:1080")
	}
	if vars["NAPCAT_TOKEN"] != "test-bot-token" {
		t.Errorf("NAPCAT_TOKEN = %q, want %q", vars["NAPCAT_TOKEN"], "test-bot-token")
	}
	if vars["ERII_SERVER_USERNAME"] != defaultServerUsername {
		t.Errorf("ERII_SERVER_USERNAME = %q, want %q", vars["ERII_SERVER_USERNAME"], defaultServerUsername)
	}
	if vars["ERII_SERVER_PASSWORD"] != defaultServerPassword {
		t.Errorf("ERII_SERVER_PASSWORD = %q, want %q", vars["ERII_SERVER_PASSWORD"], defaultServerPassword)
	}
	// Non-sensitive should not be in env vars
	if _, ok := vars["PLAYWRIGHT_HOST"]; ok {
		t.Error("PLAYWRIGHT_HOST should not be in env vars (non-sensitive)")
	}
	if _, ok := vars["EXTERNAL_HOST"]; ok {
		t.Error("EXTERNAL_HOST should not be in env vars (non-sensitive)")
	}
}

func TestBuildEnvVars_Anthropic(t *testing.T) {
	d := makeTestData()
	d.SelectedProv = 1 // anthropic
	vars := buildEnvVars(d)

	if vars["ANTHROPIC_API_KEY"] != "openai-api-key" {
		t.Errorf("ANTHROPIC_API_KEY = %q", vars["ANTHROPIC_API_KEY"])
	}
	if _, ok := vars["OPENAI_API_KEY"]; ok {
		t.Error("OPENAI_API_KEY should not be set when anthropic is selected")
	}
}

func TestBuildEnvVars_OpenAICompatiblePreset(t *testing.T) {
	d := makeTestData()
	d.Providers = []Provider{
		{Name: "DeepSeek", Key: "deepseek", API: "openai", BaseURL: "https://api.deepseek.com"},
	}
	d.SelectedProv = 0
	vars := buildEnvVars(d)

	if vars["DEEPSEEK_API_KEY"] != "openai-api-key" {
		t.Errorf("DEEPSEEK_API_KEY = %q", vars["DEEPSEEK_API_KEY"])
	}
	if _, ok := vars["OPENAI_API_KEY"]; ok {
		t.Error("OPENAI_API_KEY should not be set for deepseek preset")
	}
}

func TestBuildEnvVars_Disabled(t *testing.T) {
	d := makeTestData()
	d.EmbeddingEnabled = false
	d.SearchEnabled = false
	d.STTEnabled = false
	d.VisionEnabled = false
	d.BrowserEnabled = false
	d.ProxyEnabled = false

	vars := buildEnvVars(d)

	if _, ok := vars["EMBEDDING_API_KEY"]; ok {
		t.Error("EMBEDDING_API_KEY should not be set when embedding is disabled")
	}
	if _, ok := vars["SEARCH_API_KEY"]; ok {
		t.Error("SEARCH_API_KEY should not be set when search is disabled")
	}
	if _, ok := vars["STT_API_KEY"]; ok {
		t.Error("STT_API_KEY should not be set when STT is disabled")
	}
	if _, ok := vars["VISION_API_KEY"]; ok {
		t.Error("VISION_API_KEY should not be set when vision is disabled")
	}
	if _, ok := vars["HTTP_PROXY"]; ok {
		t.Error("HTTP_PROXY should not be set when proxy is disabled")
	}
}

// ---- writeEnvLocal tests ----

func TestWriteEnvLocal_Create(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()
	envFile := filepath.Join(tmpDir, ".env.local")

	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatalf("writeEnvLocal failed: %v", err)
	}

	content, _ := os.ReadFile(envFile)
	contentStr := string(content)

	for _, want := range []string{
		"OPENAI_API_KEY=openai-api-key",
		"EMBEDDING_API_KEY=test-embedding-key",
		"NAPCAT_TOKEN=test-bot-token",
		"HTTP_PROXY=http://proxy.test.com:8080",
		"ERII_SERVER_USERNAME=eriix",
		"ERII_SERVER_PASSWORD=@Aa123!",
	} {
		if !strings.Contains(contentStr, want) {
			t.Errorf(".env.local should contain %q", want)
		}
	}
}

func TestReadCoreCredentials(t *testing.T) {
	envFile := filepath.Join(t.TempDir(), ".env.local")
	content := `# Core credentials
ERII_SERVER_USERNAME="custom-user"
ERII_SERVER_PASSWORD='custom-pass'
ERII_SERVER_USERNAME=latest-user
`
	if err := os.WriteFile(envFile, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}

	username, password := readCoreCredentials(envFile)
	if username != "latest-user" {
		t.Fatalf("username = %q, want latest-user", username)
	}
	if password != "custom-pass" {
		t.Fatalf("password = %q, want custom-pass", password)
	}
}

func TestWriteEnvLocalRemovesDuplicateCoreCredentials(t *testing.T) {
	d := makeTestData()
	envFile := filepath.Join(t.TempDir(), ".env.local")
	initial := `ERII_SERVER_USERNAME=old-one
UNCHANGED=value
ERII_SERVER_USERNAME=old-two
ERII_SERVER_PASSWORD=old-password
`
	if err := os.WriteFile(envFile, []byte(initial), 0600); err != nil {
		t.Fatal(err)
	}
	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatal(err)
	}

	content, err := os.ReadFile(envFile)
	if err != nil {
		t.Fatal(err)
	}
	text := string(content)
	if strings.Count(text, "ERII_SERVER_USERNAME=") != 1 {
		t.Fatalf("username should be de-duplicated:\n%s", text)
	}
	if !strings.Contains(text, "ERII_SERVER_PASSWORD="+defaultServerPassword) {
		t.Fatalf("password was not updated:\n%s", text)
	}
	if !strings.Contains(text, "UNCHANGED=value") {
		t.Fatalf("unrelated value was removed:\n%s", text)
	}
}

func TestWriteEnvLocal_UpdateExisting(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()
	envFile := filepath.Join(tmpDir, ".env.local")

	initial := `# LLM API
OPENAI_API_KEY=old-key
ANTHROPIC_API_KEY=other-key

# Bot
NAPCAT_TOKEN=old-token
`
	if err := os.WriteFile(envFile, []byte(initial), 0644); err != nil {
		t.Fatalf("cannot write initial .env.local: %v", err)
	}

	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatalf("writeEnvLocal failed: %v", err)
	}

	content, _ := os.ReadFile(envFile)
	contentStr := string(content)

	if !strings.Contains(contentStr, "OPENAI_API_KEY=openai-api-key") {
		t.Error("OPENAI_API_KEY should be updated")
	}
	if !strings.Contains(contentStr, "# LLM API") {
		t.Error("comments should be preserved")
	}
	if !strings.Contains(contentStr, "ANTHROPIC_API_KEY=other-key") {
		t.Error("unrelated keys should be preserved")
	}
	if !strings.Contains(contentStr, "EMBEDDING_API_KEY=test-embedding-key") {
		t.Error("new keys should be appended")
	}
}

func TestWriteEnvLocal_EmptyValues(t *testing.T) {
	d := makeTestData()
	d.APIKey = ""
	d.EmbeddingAPIKey = ""
	d.BotToken = ""

	tmpDir := t.TempDir()
	envFile := filepath.Join(tmpDir, ".env.local")

	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatalf("writeEnvLocal failed: %v", err)
	}

	content, _ := os.ReadFile(envFile)
	contentStr := string(content)

	for _, key := range []string{"OPENAI_API_KEY", "EMBEDDING_API_KEY", "NAPCAT_TOKEN"} {
		if strings.Contains(contentStr, key+"=") {
			t.Errorf("%s should not be written when empty", key)
		}
	}
	if !strings.Contains(contentStr, "SEARCH_API_KEY=test-search-key") {
		t.Error("non-empty SEARCH_API_KEY should still be written")
	}
}

func TestWriteEnvLocal_NoDuplicate(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()
	envFile := filepath.Join(tmpDir, ".env.local")

	initial := `SEARCH_API_KEY=existing-key
SEARCH_API_KEY_ID=some-id-should-not-be-touched
`
	if err := os.WriteFile(envFile, []byte(initial), 0644); err != nil {
		t.Fatalf("cannot write initial .env.local: %v", err)
	}

	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatalf("writeEnvLocal failed: %v", err)
	}

	content, _ := os.ReadFile(envFile)
	contentStr := string(content)

	if strings.Count(contentStr, "\nSEARCH_API_KEY=") != 1 && !strings.HasPrefix(contentStr, "SEARCH_API_KEY=") {
		t.Errorf("SEARCH_API_KEY should appear exactly once, got:\n%s", contentStr)
	}
	if !strings.Contains(contentStr, "SEARCH_API_KEY_ID=some-id-should-not-be-touched") {
		t.Error("SEARCH_API_KEY_ID should not be modified")
	}
}

func TestWriteEnvLocal_CommentedKeyNotMatched(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()
	envFile := filepath.Join(tmpDir, ".env.local")

	initial := `# SEARCH_API_KEY=commented-out-key
`
	if err := os.WriteFile(envFile, []byte(initial), 0644); err != nil {
		t.Fatalf("cannot write initial .env.local: %v", err)
	}

	if err := writeEnvLocal(d, envFile); err != nil {
		t.Fatalf("writeEnvLocal failed: %v", err)
	}

	content, _ := os.ReadFile(envFile)
	contentStr := string(content)

	if !strings.Contains(contentStr, "# SEARCH_API_KEY=commented-out-key") {
		t.Error("commented-out key should be preserved")
	}
	// An active key should be appended (not modifying the comment)
	found := false
	for _, line := range strings.Split(contentStr, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "SEARCH_API_KEY=test-search-key" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("SEARCH_API_KEY should be appended as active line, got:\n%s", contentStr)
	}
}

// ---- modifyConfig tests ----

func TestModifyConfig_NoSecretLeak(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()

	confContent := `llm {
  choice-provider = "NONE"
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://api.openai.com/v1"
      models {
        lite = "gpt-4.1-nano"
        flash = "gpt-4.1-mini"
        pro = "gpt-4.1"
      }
    }
  }
}

embedding {
  api-key = ${?EMBEDDING_API_KEY}
  provider = "bytedance"
  model = "doubao-embedding-vision-251215"
}

search {
  api-key = ${?SEARCH_API_KEY}
  provider = "exa"
}

vision {
  api-key = ${?VISION_API_KEY}
  provider = "minimax"
}

stt {
  api-key = ${?STT_API_KEY}
  url = "https://old-stt.example.com"
  provider = "none"
  model = ""
}

browser {
  download = false
  playwright-url = ${?PLAYWRIGHT_HOST}
  external-host = ${?EXTERNAL_HOST}
}

proxy {
  http = ${?HTTP_PROXY}
  socks = ${?SOCKS_PROXY}
}

onebot {
  bots {
    erii {
      ws = "ws://127.0.0.1:3001"
      token = ${NAPCAT_TOKEN}
    }
  }
}
`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	// Sensitive plaintext MUST NOT appear
	for _, secret := range []string{
		"openai-api-key", "test-embedding-key", "test-search-key",
		"test-stt-key", "test-vision-key", "test-bot-token", "http://proxy.test.com:8080",
	} {
		if strings.Contains(resultStr, secret) {
			t.Errorf("secret %q leaked into application.conf!", secret)
		}
	}

	// Browser URLs are non-sensitive — SHOULD be in conf
	if !strings.Contains(resultStr, "playwright-url = \"ws://playwright.test.com:3000\"") {
		t.Error("playwright-url should be written to application.conf")
	}
	if !strings.Contains(resultStr, "external-host = \"external.test.com\"") {
		t.Error("external-host should be written to application.conf")
	}

	// Secret ${?VAR} references MUST remain
	for _, ref := range []string{
		"${?OPENAI_API_KEY}", "${?EMBEDDING_API_KEY}",
		"${?STT_API_KEY}", "${NAPCAT_TOKEN}", "${?HTTP_PROXY}",
	} {
		if !strings.Contains(resultStr, ref) {
			t.Errorf("%s reference should remain in application.conf", ref)
		}
	}
}

func TestModifyConfig_NonSensitiveWritten(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()

	confContent := `llm {
  choice-provider = "NONE"
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://default.example.com"
      models {
        lite = "old-lite"
        flash = "old-flash"
        pro = "old-pro"
      }
    }
  }
}

browser {
  download = false
  playwright-url = ${?PLAYWRIGHT_HOST}
  external-host = ${?EXTERNAL_HOST}
}

embedding {
  api-key = ${?EMBEDDING_API_KEY}
  url = "https://old-embed.example.com"
  provider = "old"
  model = "old-model"
}

stt {
  api-key = ${?STT_API_KEY}
  url = "https://old-stt.example.com"
  provider = "none"
  model = ""
}

onebot {
  bots {
    erii {
      ws = "ws://old.example.com:3001"
      token = ${NAPCAT_TOKEN}
    }
  }
}
`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	checks := []string{
		"choice-provider = \"OPENAI\"",
		"base-url = \"https://custom.openai.com\"",
		"lite = \"gpt-4.1-nano\"",
		"download = true",
		"playwright-url = \"ws://playwright.test.com:3000\"",
		"external-host = \"external.test.com\"",
		"ws = \"ws://bot.test.com:3001\"",
		"provider = \"bytedance\"",
		"url = \"https://stt.test.com/recognize/flash\"",
		"provider = \"volcengine-flash\"",
		"model = \"volc.bigasr.auc_turbo\"",
	}
	for _, c := range checks {
		if !strings.Contains(resultStr, c) {
			t.Errorf("conf should contain %q", c)
		}
	}
}

func TestModifyConfig_OpenAICompatiblePresetWritesOpenAIBlock(t *testing.T) {
	d := makeTestData()
	d.Providers = []Provider{
		{Name: "DeepSeek", Key: "deepseek", API: "openai", BaseURL: "https://api.deepseek.com"},
	}
	d.SelectedProv = 0
	d.BaseURL = "https://api.deepseek.com"
	d.LiteModel = "deepseek-chat"
	d.FlashModel = "deepseek-chat"
	d.ProModel = "deepseek-reasoner"
	d.LLMSettings["chat-completions"] = "v1/chat/completions"
	d.LLMSettings["responses-api"] = "v1/responses"
	d.LLMSettings["models"] = "v1/models"

	tmpDir := t.TempDir()
	confContent := `llm {
  choice-provider = "NONE"
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://api.openai.com/v1"
      models {
        lite = "old-lite"
        flash = "old-flash"
        pro = "old-pro"
      }
      settings {
        chat-completions = "old/chat"
        responses-api = "old/responses"
        embeddings = "old/embeddings"
        moderations = "old/moderations"
        models = "old/models"
      }
    }
    anthropic {
      api-key = ${?ANTHROPIC_API_KEY}
      base-url = "https://api.anthropic.com"
    }
  }
}
`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	for _, want := range []string{
		`choice-provider = "OPENAI"`,
		`api-key = ${?DEEPSEEK_API_KEY}`,
		`base-url = "https://api.deepseek.com"`,
		`lite = "deepseek-chat"`,
		`flash = "deepseek-chat"`,
		`pro = "deepseek-reasoner"`,
		`chat-completions = "v1/chat/completions"`,
		`responses-api = "v1/responses"`,
	} {
		if !strings.Contains(resultStr, want) {
			t.Errorf("conf should contain %q, got:\n%s", want, resultStr)
		}
	}
	if strings.Contains(resultStr, "DEEPSEEK_API_KEY") && strings.Contains(resultStr, "providers {\n    anthropic") {
		// The anthropic block may still contain its own env ref, but DeepSeek should only affect openai block.
		if strings.Contains(resultStr, "anthropic {\n      api-key = ${?DEEPSEEK_API_KEY}") {
			t.Error("deepseek env ref should not be written to anthropic block")
		}
	}
}

func TestModifyConfig_AdvancedLLMFields(t *testing.T) {
	d := makeTestData()
	d.LLMCapability.Lite.Thinking = true
	d.LLMCapability.Lite.VisionImage = true
	d.LLMUsagePricing.PriceUnit = "CNY"
	d.LLMUsagePricing.Lite.InputCacheHit = 0.01
	d.LLMUsagePricing.Lite.InputCacheMiss = 0.02
	d.LLMUsagePricing.Lite.Output = 0.03

	tmpDir := t.TempDir()
	confContent := `llm {
  capability {
    completion = true
    prompt-caching = true
    temperature = true
    tools = true
    tool-choice = true
    multiple-choices = true
    thinking = true
    vision-image = true
    lite {
      completion = true
      prompt-caching = true
      temperature = true
      tools = true
      tool-choice = true
      multiple-choices = true
      thinking = false
      vision-image = false
    }
  }
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://api.openai.com/v1"
      models {
        lite = "old-lite"
        flash = "old-flash"
        pro = "old-pro"
      }
      settings {
        chat-completions = "old/chat"
        responses-api = "old/responses"
        embeddings = "old/embeddings"
        moderations = "old/moderations"
        models = "old/models"
      }
    }
  }
  usage-pricing {
    price-unit = "USD"
    lite {
      input-cache-hit = 0.1
      input-cache-miss = 0.2
      output = 0.3
    }
  }
}
`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	for _, want := range []string{
		`thinking = true`,
		`vision-image = true`,
		`price-unit = "CNY"`,
		`input-cache-hit = 0.01`,
		`input-cache-miss = 0.02`,
		`output = 0.03`,
	} {
		if !strings.Contains(resultStr, want) {
			t.Errorf("conf should contain %q, got:\n%s", want, resultStr)
		}
	}
}

func TestModifyConfig_MigratePlaintextToEnvRef(t *testing.T) {
	// conf has plaintext api-key → should be replaced with ${?VAR}
	// and the value captured into SetupData for .env.local
	d := makeTestData()
	d.APIKey = ""
	d.EmbeddingAPIKey = ""
	d.SearchAPIKey = ""
	d.BotToken = ""

	tmpDir := t.TempDir()

	confContent := `llm {
  providers {
    openai {
      api-key = "sk-plaintext-in-conf"
      base-url = "https://api.example.com"
      models {
        lite = "old-lite"
      }
    }
  }
}

embedding {
  api-key = "emb-plaintext-in-conf"
}

search {
  api-key = "search-plaintext-in-conf"
}

onebot {
  bots {
    erii {
      ws = "ws://127.0.0.1:3001"
      token = "token-plaintext-in-conf"
    }
  }
}
`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	// Plaintext values replaced with ${?VAR}
	if strings.Contains(resultStr, "sk-plaintext-in-conf") {
		t.Error("plaintext LLM key should be replaced with ${?OPENAI_API_KEY}")
	}
	if !strings.Contains(resultStr, "${?OPENAI_API_KEY}") {
		t.Error("conf should have ${?OPENAI_API_KEY}")
	}
	if !strings.Contains(resultStr, "${?EMBEDDING_API_KEY}") {
		t.Error("conf should have ${?EMBEDDING_API_KEY}")
	}
	if !strings.Contains(resultStr, "${?SEARCH_API_KEY}") {
		t.Error("conf should have ${?SEARCH_API_KEY}")
	}
	if !strings.Contains(resultStr, "${NAPCAT_TOKEN}") {
		t.Error("conf should have ${NAPCAT_TOKEN}")
	}

	// Values captured into SetupData
	if d.APIKey != "sk-plaintext-in-conf" {
		t.Errorf("APIKey should be captured, got %q", d.APIKey)
	}
	if d.EmbeddingAPIKey != "emb-plaintext-in-conf" {
		t.Errorf("EmbeddingAPIKey should be captured, got %q", d.EmbeddingAPIKey)
	}
	if d.SearchAPIKey != "search-plaintext-in-conf" {
		t.Errorf("SearchAPIKey should be captured, got %q", d.SearchAPIKey)
	}
	if d.BotToken != "token-plaintext-in-conf" {
		t.Errorf("BotToken should be captured, got %q", d.BotToken)
	}
}

func TestModifyConfig_EnvRefUntouched(t *testing.T) {
	// conf already has ${?VAR} → left as-is, user value kept in SetupData
	d := makeTestData()
	d.APIKey = "user-entered-key"

	tmpDir := t.TempDir()

	confContent := `llm {
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
    }
  }
}`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	if !strings.Contains(resultStr, "${?OPENAI_API_KEY}") {
		t.Error("existing ${?OPENAI_API_KEY} should remain")
	}
	if strings.Contains(resultStr, "user-entered-key") {
		t.Error("user-entered key should not be written to conf")
	}
	if d.APIKey != "user-entered-key" {
		t.Errorf("APIKey should be %q, got %q", "user-entered-key", d.APIKey)
	}
}

func TestModifyConfig_PreservesEnvRefFormat(t *testing.T) {
	d := makeTestData()
	tmpDir := t.TempDir()

	confContent := `llm {
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
    }
    anthropic {
      api-key = ${?ANTHROPIC_API_KEY}
    }
    openai {
      api-key = ${?OPENAI_API_KEY}
    }
  }
}`
	confFile := filepath.Join(tmpDir, "application.conf")
	if err := os.WriteFile(confFile, []byte(confContent), 0644); err != nil {
		t.Fatalf("cannot write test conf: %v", err)
	}

	if err := modifyConfig(d, confFile); err != nil {
		t.Fatalf("modifyConfig failed: %v", err)
	}

	result, _ := os.ReadFile(confFile)
	resultStr := string(result)

	scanner := bufio.NewScanner(strings.NewReader(resultStr))
	count := 0
	for scanner.Scan() {
		if strings.Contains(scanner.Text(), "${?") {
			count++
		}
	}
	if count != 3 {
		t.Errorf("expected 3 ${? references, got %d", count)
	}
}
