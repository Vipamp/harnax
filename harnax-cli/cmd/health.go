package cmd

import (
	"context"

	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

var healthCmd = &cobra.Command{
	Use:   "health",
	Short: "Check server health status",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		ctx := context.Background()
		result, err := c.List(ctx, "/api/admin/health", nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintJSON(result)
	},
}

var infoCmd = &cobra.Command{
	Use:   "info",
	Short: "Get server version information",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		ctx := context.Background()
		result, err := c.List(ctx, "/api/admin/info", nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintJSON(result)
	},
}

func init() {
	healthCmd.AddCommand(infoCmd)
	rootCmd.AddCommand(healthCmd)
}
