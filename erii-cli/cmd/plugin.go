package cmd

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"erii-cli/internal/api"
	uioutput "erii-cli/internal/ui/output"

	"github.com/spf13/cobra"
)

var pluginCmd = &cobra.Command{
	Use:   "plugin",
	Short: "Manage Erii plugins",
}

var pluginMatchFormat string

var pluginRefreshCmd = &cobra.Command{
	Use:   "refresh [plugin-id]",
	Short: "Refresh plugin lifecycle",
	Long:  "Unload and reload all plugins, or refresh a single plugin when a plugin id is provided.",
	Args:  cobra.MaximumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		pluginID := ""
		if len(args) > 0 {
			pluginID = args[0]
		}

		client, err := api.NewClientFromIPC()
		if err != nil {
			return fmt.Errorf("plugin refresh connection: %w", err)
		}

		result, err := client.RefreshPlugins(pluginID)
		if err != nil {
			return fmt.Errorf("plugin refresh backend: %w", err)
		}

		fmt.Fprint(cmd.OutOrStdout(), renderPluginRefreshResult(result))
		return nil
	},
}

var pluginSendCmd = &cobra.Command{
	Use:   "send -- <input...>",
	Short: "Send input to plugin CLI event listeners",
	Args:  cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		input := strings.Join(args, " ")
		client, err := api.NewClientFromIPC()
		if err != nil {
			return fmt.Errorf("plugin send connection: %w", err)
		}

		result, err := client.SendPluginCli(input)
		if err != nil {
			return fmt.Errorf("plugin send backend: %w", err)
		}

		fmt.Fprint(cmd.OutOrStdout(), renderPluginSendResult(result))
		return nil
	},
}

var pluginMatchCmd = &cobra.Command{
	Use:   "match [--] <query...>",
	Short: "Search registered plugin command examples",
	Args:  cobra.ArbitraryArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		query := strings.Join(args, " ")
		format := strings.ToLower(strings.TrimSpace(pluginMatchFormat))
		if format == "" {
			format = "text"
		}
		if format != "text" && format != "json" {
			return commandLineErrorf("invalid format %q, valid: text, json", pluginMatchFormat)
		}

		client, err := api.NewClientFromIPC()
		if err != nil {
			return fmt.Errorf("plugin match connection: %w", err)
		}

		result, err := client.MatchPluginCommands(query, 20)
		if err != nil {
			return fmt.Errorf("plugin match backend: %w", err)
		}

		if format == "json" {
			data, err := json.MarshalIndent(result, "", "  ")
			if err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), string(data))
			return nil
		}

		fmt.Fprint(cmd.OutOrStdout(), renderPluginMatchResult(result))
		return nil
	},
}

func printPluginRefreshResult(result *api.PluginRefreshResponse) {
	fmt.Print(renderPluginRefreshResult(result))
}

func renderPluginRefreshResult(result *api.PluginRefreshResponse) string {
	if result == nil {
		return uioutput.Title("Plugin refresh") + "\n" + uioutput.Muted("No result returned.") + "\n"
	}

	var b strings.Builder
	status := result.Status
	if status == "" {
		status = "unknown"
	}
	badge := uioutput.Status(status)

	b.WriteString(uioutput.Title("Plugin refresh"))
	b.WriteString("  ")
	b.WriteString(badge)
	if result.HTTPStatus >= 400 {
		b.WriteString(" ")
		b.WriteString(uioutput.HTTPStatus(result.HTTPStatus))
	}
	b.WriteString("\n")

	if result.Message != "" {
		b.WriteString(uioutput.Row("Message", result.Message))
	}
	if result.RequestedPluginID != "" {
		b.WriteString(uioutput.Row("Requested", result.RequestedPluginID))
	}
	b.WriteString("\n")
	b.WriteString(uioutput.Section("Summary"))
	b.WriteString("\n")
	b.WriteString(uioutput.Row("Loaded", fmt.Sprintf("%d extensions", result.LoadedExtensions)))
	b.WriteString(uioutput.Row("Refreshed", fmt.Sprintf("%d plugins", len(result.RefreshedPlugins))))
	b.WriteString(uioutput.Row("Failed", fmt.Sprintf("%d plugins", len(result.FailedPlugins))))

	if len(result.RefreshedPlugins) > 0 {
		b.WriteString("\n")
		b.WriteString(uioutput.Section("Refreshed plugins"))
		b.WriteString("\n")
		for _, line := range wrapCommaList(result.RefreshedPlugins, 76) {
			b.WriteString("  ")
			b.WriteString(uioutput.Plugin(line))
			b.WriteString("\n")
		}
	}

	if len(result.FailedPlugins) > 0 {
		b.WriteString("\n")
		b.WriteString(uioutput.Section("Failed plugins"))
		b.WriteString("\n")
		pluginIDs := make([]string, 0, len(result.FailedPlugins))
		for pluginID := range result.FailedPlugins {
			pluginIDs = append(pluginIDs, pluginID)
		}
		sort.Strings(pluginIDs)
		for _, pluginID := range pluginIDs {
			reason := result.FailedPlugins[pluginID]
			b.WriteString("  ")
			b.WriteString(uioutput.Error(pluginID))
			b.WriteString("\n")
			b.WriteString("    ")
			b.WriteString(uioutput.Muted(reason))
			b.WriteString("\n")
		}
	}

	return strings.TrimRight(b.String(), "\n") + "\n"
}

func renderPluginSendResult(result *api.PluginCliSendResponse) string {
	if result == nil {
		return uioutput.Title("Plugin send") + "\n" + uioutput.Muted("No result returned.") + "\n"
	}
	status := result.Status
	if status == "" {
		status = "unknown"
	}
	var b strings.Builder
	b.WriteString(uioutput.Title("Plugin send"))
	b.WriteString("  ")
	b.WriteString(uioutput.Status(status))
	if result.HTTPStatus >= 400 {
		b.WriteString(" ")
		b.WriteString(uioutput.HTTPStatus(result.HTTPStatus))
	}
	b.WriteString("\n")
	if result.Message != "" {
		b.WriteString(uioutput.Row("Message", result.Message))
	}
	b.WriteString("\n")
	b.WriteString(uioutput.Section("Summary"))
	b.WriteString("\n")
	b.WriteString(uioutput.Row("Status", "sent"))
	b.WriteString(uioutput.WrappedRow("Input", result.Input, 72))
	if result.Reply == nil || strings.TrimSpace(*result.Reply) == "" {
		b.WriteString(uioutput.Row("Reply", "empty response"))
	} else {
		b.WriteString(uioutput.WrappedRow("Reply", *result.Reply, 72))
	}
	return b.String()
}

func renderPluginMatchResult(result *api.PluginCommandMatchResponse) string {
	if result == nil {
		return uioutput.Title("Plugin match") + "\n" + uioutput.Muted("No result returned.") + "\n"
	}
	status := result.Status
	if status == "" {
		status = "unknown"
	}
	var b strings.Builder
	b.WriteString(uioutput.Title("Plugin match"))
	b.WriteString("  ")
	b.WriteString(uioutput.Status(status))
	if result.HTTPStatus >= 400 {
		b.WriteString(" ")
		b.WriteString(uioutput.HTTPStatus(result.HTTPStatus))
	}
	b.WriteString("\n")
	b.WriteString(uioutput.Row("Query", result.Query))
	b.WriteString("\n")
	b.WriteString(uioutput.Section("Summary"))
	b.WriteString("\n")
	b.WriteString(uioutput.Row("Matches", fmt.Sprintf("%d", len(result.Matches))))
	if len(result.Matches) == 0 {
		b.WriteString("\n")
		b.WriteString(uioutput.Muted("No registered plugin command examples matched."))
		b.WriteString("\n")
		return b.String()
	}
	b.WriteString("\n")
	b.WriteString(uioutput.Section("Matches"))
	b.WriteString("\n")
	for _, item := range result.Matches {
		b.WriteString("  ")
		b.WriteString(uioutput.Plugin(item.Example))
		b.WriteString("\n")
		b.WriteString(uioutput.IndentedRow("Plugin", item.PluginID))
		if item.Description != "" {
			b.WriteString(uioutput.IndentedRow("Description", uioutput.Muted(item.Description)))
		}
	}
	return b.String()
}

func pluginRefreshStatusBadge(status string) string {
	return uioutput.Status(status)
}

func pluginRefreshRow(label, value string) string {
	return uioutput.Row(label, value)
}

func wrapCommaList(items []string, maxWidth int) []string {
	if len(items) == 0 {
		return nil
	}
	lines := make([]string, 0, 1)
	current := ""
	for _, item := range items {
		if current == "" {
			current = item
			continue
		}
		next := current + ", " + item
		if len(next) > maxWidth {
			lines = append(lines, current)
			current = item
			continue
		}
		current = next
	}
	if current != "" {
		lines = append(lines, current)
	}
	return lines
}

func init() {
	pluginMatchCmd.Flags().StringVarP(&pluginMatchFormat, "format", "f", "text", "Output format: text or json")
	pluginCmd.AddCommand(pluginRefreshCmd, pluginSendCmd, pluginMatchCmd)
	rootCmd.AddCommand(pluginCmd)
}
