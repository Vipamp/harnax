package cmd

import (
	"fmt"

	"github.com/agnetix/harnax-cli/internal/config"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

var configCmd = &cobra.Command{
	Use:   "config",
	Short: "Manage CLI configuration",
}

var configSetCmd = &cobra.Command{
	Use:   "set <key> <value>",
	Short: "Set a configuration value",
	Args:  cobra.ExactArgs(2),
	Run: func(cmd *cobra.Command, args []string) {
		key, value := args[0], args[1]

		cfg, err := config.LoadConfig()
		if err != nil {
			exitError(err.Error())
		}

		profileName := profile
		if profileName == "" {
			profileName = cfg.CurrentProfile
		}
		if profileName == "" {
			profileName = "default"
		}

		p, ok := cfg.Profiles[profileName]
		if !ok {
			p = config.Profile{}
		}

		switch key {
		case "serverUrl":
			p.ServerURL = value
		case "defaultOutput":
			cfg.DefaultOutput = value
		default:
			exitError(fmt.Sprintf("unsupported config key: %s (supported: serverUrl, defaultOutput)", key))
		}

		cfg.Profiles[profileName] = p

		if err := config.SaveConfig(cfg); err != nil {
			exitError(fmt.Sprintf("failed to save config: %v", err))
		}

		output.PrintSuccess(fmt.Sprintf("Set %s = %s", key, value))
	},
}

var configGetCmd = &cobra.Command{
	Use:   "get <key>",
	Short: "Get a configuration value",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		key := args[0]

		cfg, err := config.LoadConfig()
		if err != nil {
			exitError(err.Error())
		}

		profileName := profile
		if profileName == "" {
			profileName = cfg.CurrentProfile
		}
		if profileName == "" {
			profileName = "default"
		}

		p, ok := cfg.Profiles[profileName]
		if !ok {
			exitError(fmt.Sprintf("profile '%s' not found", profileName))
		}

		switch key {
		case "serverUrl":
			fmt.Println(p.ServerURL)
		case "defaultOutput":
			fmt.Println(cfg.DefaultOutput)
		default:
			exitError(fmt.Sprintf("unsupported config key: %s (supported: serverUrl, defaultOutput)", key))
		}
	},
}

var configListCmd = &cobra.Command{
	Use:   "list",
	Short: "List all configuration",
	Run: func(cmd *cobra.Command, args []string) {
		cfg, err := config.LoadConfig()
		if err != nil {
			exitError(err.Error())
		}

		output.PrintJSON(cfg)
	},
}

var configUseProfileCmd = &cobra.Command{
	Use:   "use-profile <name>",
	Short: "Switch to a different profile",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		name := args[0]

		cfg, err := config.LoadConfig()
		if err != nil {
			exitError(err.Error())
		}

		cfg.CurrentProfile = name

		if err := config.SaveConfig(cfg); err != nil {
			exitError(fmt.Sprintf("failed to save config: %v", err))
		}

		output.PrintSuccess(fmt.Sprintf("Switched to profile: %s", name))
	},
}

func init() {
	configCmd.AddCommand(configSetCmd)
	configCmd.AddCommand(configGetCmd)
	configCmd.AddCommand(configListCmd)
	configCmd.AddCommand(configUseProfileCmd)
	rootCmd.AddCommand(configCmd)
}
