package cmd

import (
	"bytes"
	"fmt"
	"os"

	"erii-cli/internal/path"
	uioutput "erii-cli/internal/ui/output"
	"erii-cli/internal/ui/theme"
	"erii-cli/internal/version"

	"github.com/spf13/cobra"
)

var (
	confDirFlag             string
	confMetaDirFlag         string
	eriiDirFlag             string
	pluginDirFlag           string
	optsPathFlag            string
	logsPathFlag            string
	themeFlag               string
	chatImageMaxColsFlag    int
	chatImageMaxRowsFlag    int
	chatImageFitFlag        string
	chatImageBackgroundFlag string
	chatImageModeFlag       string
)

var rootCmd = &cobra.Command{
	Use:     "erii",
	Short:   "Erii CLI - Configuration and management tool for Erii",
	Long:    `A command-line tool with TUI for managing Erii bot configuration.`,
	Version: version.Version,
	PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
		if err := configureTheme(cmd); err != nil {
			if cmd.Root().PersistentFlags().Changed("theme") {
				return asCommandLineError(err)
			}
			return err
		}
		path.InitPaths(confDirFlag, confMetaDirFlag, eriiDirFlag, pluginDirFlag, optsPathFlag)
		return nil
	},
	SilenceErrors: true,
	SilenceUsage:  true,
}

func Execute() {
	markCommandLineErrors(rootCmd)
	executedCmd, err := rootCmd.ExecuteC()
	if err != nil {
		printExecutionError(executedCmd, err)
		os.Exit(1)
	}
}

func configureTheme(cmd *cobra.Command) error {
	_, err := theme.Configure(themeFlag, cmd.Root().PersistentFlags().Changed("theme"), os.Getenv("ERII_THEME"))
	theme.ConfigureColorOutput(cmd.OutOrStdout())
	return err
}

func init() {
	rootCmd.PersistentFlags().StringVar(&confDirFlag, "conf-dir", "", "Path to conf directory (default: auto-detected)")
	rootCmd.PersistentFlags().StringVar(&confMetaDirFlag, "meta-conf-dir", "", "Path to .conf meta directory (default: auto-detected)")
	rootCmd.PersistentFlags().StringVar(&eriiDirFlag, "erii-dir", "", "Path to erii directory (default: auto-detected)")
	rootCmd.PersistentFlags().StringVar(&pluginDirFlag, "plugin-dir", "", "Path to plugins directory (default: auto-detected)")
	rootCmd.PersistentFlags().StringVar(&logsPathFlag, "logs-path", "", "Path to logs directory (default: ./logs)")
	rootCmd.PersistentFlags().StringVar(&optsPathFlag, "opts-path", "", "Path to JVM opts directory (default: auto-detected)")
	rootCmd.PersistentFlags().StringVar(&themeFlag, "theme", "auto", "Color theme: auto, dark, or light")
	rootCmd.PersistentFlags().IntVar(&chatImageMaxColsFlag, "chat-image-max-cols", 0, "erii chat image max width in terminal columns (env: ERII_CHAT_IMAGE_MAX_COLS)")
	rootCmd.PersistentFlags().IntVar(&chatImageMaxRowsFlag, "chat-image-max-rows", 0, "erii chat image max height in terminal rows (env: ERII_CHAT_IMAGE_MAX_ROWS)")
	rootCmd.PersistentFlags().StringVar(&chatImageFitFlag, "chat-image-fit", "", "erii chat image fit: contain, fill, or cover (env: ERII_CHAT_IMAGE_FIT)")
	rootCmd.PersistentFlags().StringVar(&chatImageBackgroundFlag, "chat-image-background", "", "erii chat image background: transparent, black, white, or #RRGGBB (env: ERII_CHAT_IMAGE_BACKGROUND)")
	rootCmd.PersistentFlags().StringVar(&chatImageModeFlag, "chat-image-mode", "", "erii chat image mode: glyph, kitty, iterm, or auto (env: ERII_CHAT_IMAGE_MODE)")

	defaultHelp := rootCmd.HelpFunc()
	rootCmd.SetHelpFunc(func(cmd *cobra.Command, args []string) {
		_ = configureTheme(cmd)
		target := cmd.OutOrStdout()
		var help bytes.Buffer
		cmd.SetOut(&help)
		defaultHelp(cmd, args)
		cmd.SetOut(target)
		if cmd == cmd.Root() {
			fmt.Fprint(target, uioutput.HelpHeader())
		}
		fmt.Fprint(target, uioutput.Help(help.String()))
	})
}
