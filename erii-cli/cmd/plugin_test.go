package cmd

import (
	"errors"
	"strings"
	"testing"

	"erii-cli/internal/api"
	internalpath "erii-cli/internal/path"

	"github.com/spf13/cobra"
)

func TestPluginRefreshCommandShape(t *testing.T) {
	if pluginCmd.Use != "plugin" {
		t.Fatalf("plugin command use = %q", pluginCmd.Use)
	}
	if pluginRefreshCmd.Use != "refresh [plugin-id]" {
		t.Fatalf("plugin refresh command use = %q", pluginRefreshCmd.Use)
	}
	if err := pluginRefreshCmd.Args(pluginRefreshCmd, []string{"demo"}); err != nil {
		t.Fatalf("one plugin id should be accepted: %v", err)
	}
	if err := pluginRefreshCmd.Args(pluginRefreshCmd, []string{"demo", "extra"}); err == nil {
		t.Fatal("more than one plugin id should be rejected")
	}
	if pluginSendCmd.Use != "send -- <input...>" {
		t.Fatalf("plugin send command use = %q", pluginSendCmd.Use)
	}
	if pluginMatchCmd.Use != "match [--] <query...>" {
		t.Fatalf("plugin match command use = %q", pluginMatchCmd.Use)
	}
	if pluginMatchCmd.Flags().Lookup("fromat") != nil {
		t.Fatal("plugin match must not expose a --fromat compatibility flag")
	}
}

func TestPluginCommandsReturnConnectionErrors(t *testing.T) {
	previousEriiDir := internalpath.EriiDir
	internalpath.EriiDir = t.TempDir()
	defer func() { internalpath.EriiDir = previousEriiDir }()

	tests := []struct {
		name string
		cmd  *cobra.Command
		args []string
	}{
		{name: "refresh", cmd: pluginRefreshCmd},
		{name: "send", cmd: pluginSendCmd, args: []string{"hello"}},
		{name: "match", cmd: pluginMatchCmd, args: []string{"hello"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := test.cmd.RunE(test.cmd, test.args); err == nil {
				t.Fatal("connection failure must be returned to Cobra")
			}
		})
	}
}

func TestRootThemeFlagDefaultsToAuto(t *testing.T) {
	flag := rootCmd.PersistentFlags().Lookup("theme")
	if flag == nil {
		t.Fatal("root command is missing --theme")
	}
	if flag.DefValue != "auto" {
		t.Fatalf("--theme default = %q, want auto", flag.DefValue)
	}
}

func TestRootChatImageFlagsExist(t *testing.T) {
	for _, name := range []string{
		"chat-image-max-cols",
		"chat-image-max-rows",
		"chat-image-fit",
		"chat-image-background",
		"chat-image-mode",
	} {
		if rootCmd.PersistentFlags().Lookup(name) == nil {
			t.Fatalf("root command is missing --%s", name)
		}
	}
}

func TestRenderReloadErrorShowsStyledLayout(t *testing.T) {
	output := renderReloadError(errors.New("metadata reload failed: broken schema"))

	for _, want := range []string{
		"Reload result",
		"ERROR",
		"Error",
		"Message",
		"metadata reload failed: broken schema",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}

func TestRenderRefreshSuccessShowsStyledLayout(t *testing.T) {
	output := renderRefreshSuccess(&api.ConfigRefreshResponse{
		Status:  "ok",
		Message: "config refreshed",
		Reloaded: api.ConfigReloadedSummary{
			Config: true,
			Roles:  3,
			Rules:  7,
			MCP:    2,
		},
		Bots: api.BotRefreshSummary{
			Added:       api.BotRefreshItems{"bot-a"},
			Removed:     api.BotRefreshItems{"bot-b", "bot-c"},
			Reconnected: api.BotRefreshItems{"bot-d", "bot-e", "bot-f"},
			RoleUpdated: api.BotRefreshItems{"bot-g", "bot-h", "bot-i", "bot-j"},
			Failed:      api.BotRefreshItems{"bot-k", "bot-l", "bot-m", "bot-n", "bot-o"},
		},
	})

	for _, want := range []string{
		"Config refresh",
		"OK",
		"config refreshed",
		"Summary",
		"Reloaded",
		"Roles",
		"3",
		"Bots",
		"Reconnected",
		"bot-d",
		"Role updated",
		"5",
		"backend config cache",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}

func TestRenderRefreshErrorShowsStyledLayout(t *testing.T) {
	output := renderRefreshError("Connection", errors.New("cannot connect to Erii service"))

	for _, want := range []string{
		"Config refresh",
		"ERROR",
		"Error",
		"Scope",
		"Connection",
		"cannot connect to Erii service",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}

func TestRenderPluginRefreshResultShowsFailureLayout(t *testing.T) {
	output := renderPluginRefreshResult(&api.PluginRefreshResponse{
		Status:           "error",
		Message:          "plugin refresh completed with failures",
		RefreshedPlugins: []string{"builtin", "chat-heatmap", "seeddream"},
		LoadedExtensions: 16,
		FailedPlugins: map[string]string{
			"official-qq-adapter_GeneratedPassive_default": "Address already in use",
		},
		HTTPStatus: 500,
	})

	for _, want := range []string{
		"Plugin refresh",
		"ERROR",
		"HTTP 500",
		"Summary",
		"16 extensions",
		"3 plugins",
		"Failed plugins",
		"official-qq-adapter_GeneratedPassive_default",
		"Address already in use",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}

func TestRenderPluginSendResultShowsInput(t *testing.T) {
	reply := "plugin reply"
	output := renderPluginSendResult(&api.PluginCliSendResponse{
		Status:  "ok",
		Message: "plugin event sent",
		Input:   "hello plugin",
		Reply:   &reply,
	})

	for _, want := range []string{
		"Plugin send",
		"OK",
		"plugin event sent",
		"Summary",
		"hello plugin",
		"plugin reply",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}

func TestRenderPluginSendResultShowsEmptyReply(t *testing.T) {
	output := renderPluginSendResult(&api.PluginCliSendResponse{
		Status:  "ok",
		Message: "plugin event sent",
		Input:   "hello plugin",
	})

	if !strings.Contains(output, "empty response") {
		t.Fatalf("rendered output should mention empty response:\n%s", output)
	}
}

func TestRenderPluginMatchResultShowsMatches(t *testing.T) {
	output := renderPluginMatchResult(&api.PluginCommandMatchResponse{
		Status: "ok",
		Query:  "hello",
		Matches: []api.PluginCommandExample{
			{
				PluginID:      "demo",
				ExtensionName: "Demo",
				Example:       "hello plugin",
				Description:   "demo command",
			},
		},
	})

	for _, want := range []string{
		"Plugin match",
		"OK",
		"Query",
		"hello",
		"Matches",
		"hello plugin",
		"demo command",
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("rendered output missing %q:\n%s", want, output)
		}
	}
}
