package cmd

import (
	"erii-cli/internal/tui/status"

	"github.com/spf13/cobra"
)

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "View bot and group status",
	RunE: func(cmd *cobra.Command, args []string) error {
		return status.Start()
	},
}

func init() {
	rootCmd.AddCommand(statusCmd)
}
