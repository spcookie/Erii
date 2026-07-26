package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRefreshPluginsUsesAllPluginsEndpoint(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{"status":"ok","message":"plugins refreshed","refreshedPlugins":["demo"],"loadedExtensions":1,"failedPlugins":{}}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	if _, err := client.RefreshPlugins(""); err != nil {
		t.Fatalf("RefreshPlugins returned error: %v", err)
	}
	if gotURI != "/api/plugins/refresh" {
		t.Fatalf("uri = %q, want /api/plugins/refresh", gotURI)
	}
}

func TestRefreshPluginsUsesSpecificPluginEndpoint(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{"status":"ok","message":"plugin refreshed","requestedPluginId":"demo/plugin","refreshedPlugins":["demo/plugin"],"loadedExtensions":1,"failedPlugins":{}}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	if _, err := client.RefreshPlugins("demo/plugin"); err != nil {
		t.Fatalf("RefreshPlugins returned error: %v", err)
	}
	if gotURI != "/api/plugins/demo%2Fplugin/refresh" {
		t.Fatalf("uri = %q, want /api/plugins/demo%%2Fplugin/refresh", gotURI)
	}
}

func TestRefreshPluginsParsesErrorResponseBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"status":"error","message":"plugin refresh completed with failures","refreshedPlugins":["demo"],"loadedExtensions":1,"failedPlugins":{"demo":"boom"}}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.RefreshPlugins("")
	if err == nil {
		t.Fatal("RefreshPlugins should return an error for HTTP 500")
	}
	if result.HTTPStatus != http.StatusInternalServerError {
		t.Fatalf("HTTPStatus = %d, want %d", result.HTTPStatus, http.StatusInternalServerError)
	}
	if result.Status != "error" {
		t.Fatalf("Status = %q, want error", result.Status)
	}
	if result.FailedPlugins["demo"] != "boom" {
		t.Fatalf("failed plugin reason = %q, want boom", result.FailedPlugins["demo"])
	}
}

func TestRefreshConfigParsesResponseBody(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{"status":"ok","message":"config refreshed","reloaded":{"config":true,"roles":3,"rules":7,"mcp":2},"bots":{"added":["bot-a"],"removed":["bot-b","bot-c"],"reconnected":["bot-d","bot-e","bot-f"],"roleUpdated":["bot-g","bot-h","bot-i","bot-j"],"failed":["bot-k","bot-l","bot-m","bot-n","bot-o"]}}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.RefreshConfig()
	if err != nil {
		t.Fatalf("RefreshConfig returned error: %v", err)
	}
	if gotURI != "/api/config/refresh" {
		t.Fatalf("uri = %q, want /api/config/refresh", gotURI)
	}
	if result.Reloaded.Roles != 3 || result.Reloaded.Rules != 7 || result.Reloaded.MCP != 2 {
		t.Fatalf("reloaded = %+v, want roles/rules/mcp 3/7/2", result.Reloaded)
	}
	if result.Bots.Added.Count() != 1 || result.Bots.Removed.Count() != 2 || result.Bots.Reconnected.Count() != 3 || result.Bots.RoleUpdated.Count() != 4 || result.Bots.Failed.Count() != 5 {
		t.Fatalf("bots = %+v, want counts 1/2/3/4/5", result.Bots)
	}
	if result.Bots.Reconnected[0] != "bot-d" {
		t.Fatalf("first reconnected bot = %q, want bot-d", result.Bots.Reconnected[0])
	}
}

func TestRefreshConfigAcceptsLegacyBotCounts(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"ok","message":"config refreshed","reloaded":{"config":true,"roles":3,"rules":7,"mcp":2},"bots":{"added":1,"removed":2,"reconnected":3,"roleUpdated":4,"failed":5}}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.RefreshConfig()
	if err != nil {
		t.Fatalf("RefreshConfig returned error: %v", err)
	}
	if result.Bots.Added.Count() != 1 || result.Bots.Failed.Count() != 5 {
		t.Fatalf("bots = %+v, want legacy counts accepted", result.Bots)
	}
}

func TestSendPluginCliUsesSendEndpoint(t *testing.T) {
	var gotURI string
	var gotBody apiBody
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		if err := json.NewDecoder(r.Body).Decode(&gotBody); err != nil {
			t.Fatalf("decode body: %v", err)
		}
		_, _ = w.Write([]byte(`{"status":"ok","message":"plugin event sent","input":"hello plugin","echo":"echo-1","reply":"pong"}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.SendPluginCli("hello plugin")
	if err != nil {
		t.Fatalf("SendPluginCli returned error: %v", err)
	}
	if gotURI != "/api/plugins/cli/send" {
		t.Fatalf("uri = %q, want /api/plugins/cli/send", gotURI)
	}
	if gotBody.Input != "hello plugin" || result.Input != "hello plugin" {
		t.Fatalf("input body/result = %q/%q, want hello plugin", gotBody.Input, result.Input)
	}
	if result.Echo != "echo-1" || result.Reply == nil || *result.Reply != "pong" {
		t.Fatalf("echo/reply = %q/%v, want echo-1/pong", result.Echo, result.Reply)
	}
}

func TestSendPluginCliReturnsStructuredBackendError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusGatewayTimeout)
		_, _ = w.Write([]byte(`{"status":"error","message":"plugin event timed out","input":"slow","echo":"echo-1","reply":null}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.SendPluginCli("slow")
	if err == nil {
		t.Fatal("SendPluginCli should return an error for HTTP 504")
	}
	if result == nil || result.HTTPStatus != http.StatusGatewayTimeout || result.Status != "error" {
		t.Fatalf("result = %+v, want structured HTTP 504 error", result)
	}
}

func TestMatchPluginCommandsEscapesQueryAndLimit(t *testing.T) {
	var gotURI string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI = r.RequestURI
		_, _ = w.Write([]byte(`{"status":"ok","query":"hello plugin","matches":[{"pluginId":"demo","extensionName":"Demo","example":"hello plugin","description":"desc"}]}`))
	}))
	defer server.Close()

	client := &Client{baseURL: server.URL, http: server.Client()}
	result, err := client.MatchPluginCommands("hello plugin", 200)
	if err != nil {
		t.Fatalf("MatchPluginCommands returned error: %v", err)
	}
	if gotURI != "/api/plugins/cli/match?query=hello+plugin&limit=100" {
		t.Fatalf("uri = %q, want escaped query and capped limit", gotURI)
	}
	if len(result.Matches) != 1 || result.Matches[0].Example != "hello plugin" {
		t.Fatalf("matches = %+v, want hello plugin", result.Matches)
	}
}

type apiBody struct {
	Input string `json:"input"`
}
