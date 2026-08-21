package cmd

import (
	"context"
	"fmt"

	"github.com/agnetix/harnax-cli/internal/config"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

var loginCmd = &cobra.Command{
	Use:   "login",
	Short: "Log in to the Harnax admin platform",
	Run: func(cmd *cobra.Command, args []string) {
		username, _ := cmd.Flags().GetString("username")
		password, _ := cmd.Flags().GetString("password")

		if username == "" || password == "" {
			exitError("--username and --password are required")
		}

		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]string{
			"username": username,
			"password": password,
		}

		ctx := context.Background()
		result, err := c.Create(ctx, "/api/admin/auth/cli-login", body)
		if err != nil {
			exitAPIError(err)
		}

		var loginData struct {
			AccessToken   string `json:"accessToken"`
			RouterApiKey  string `json:"routerApiKey"`
			CurrentTenantID int64 `json:"currentTenantId"`
		}
		if err := result.DecodeData(&loginData); err != nil {
			exitError(fmt.Sprintf("failed to parse login response: %v", err))
		}

		creds := &config.Credentials{
			AccessToken: loginData.AccessToken,
			Username:    username,
			TenantID:    loginData.CurrentTenantID,
		}

		if err := config.SaveCredentials(creds); err != nil {
			exitError(fmt.Sprintf("failed to save credentials: %v", err))
		}

		output.PrintSuccess(fmt.Sprintf("Logged in as %s", username))
	},
}

var logoutCmd = &cobra.Command{
	Use:   "logout",
	Short: "Log out from the Harnax admin platform",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		ctx := context.Background()
		_, err = c.Create(ctx, "/api/admin/auth/logout", nil)
		if err != nil {
			exitAPIError(err)
		}

		if err := config.ClearCredentials(); err != nil {
			exitError(fmt.Sprintf("failed to clear credentials: %v", err))
		}

		output.PrintSuccess("Logged out successfully")
	},
}

func init() {
	loginCmd.Flags().String("username", "", "Username (required)")
	loginCmd.Flags().String("password", "", "Password (required)")
	loginCmd.MarkFlagRequired("username")
	loginCmd.MarkFlagRequired("password")

	rootCmd.AddCommand(loginCmd)
	rootCmd.AddCommand(logoutCmd)
}
