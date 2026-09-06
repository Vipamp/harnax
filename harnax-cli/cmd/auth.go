package cmd

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/agnetix/harnax-cli/internal/client"
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

		// Login must work without existing credentials, so resolve the
		// server URL directly instead of using newAdminClient().
		url := serverURL
		if url == "" {
			var err error
			url, err = config.GetServerURL(profile)
			if err != nil {
				exitError(err.Error())
			}
		}
		c := client.NewAdminClient(url, "")
		c.Verbose = verbose

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
			AccessToken     string `json:"accessToken"`
			RouterApiKey    string `json:"routerApiKey"`
			CurrentTenantID int64  `json:"currentTenantId"`
			ExpiresAt       int64  `json:"expiresAt"`
		}
		if err := result.DecodeData(&loginData); err != nil {
			exitError(fmt.Sprintf("failed to parse login response: %v", err))
		}

		creds := &config.Credentials{
			AccessToken: loginData.AccessToken,
			Username:    username,
			TenantID:    loginData.CurrentTenantID,
			ExpiresAt:   loginData.ExpiresAt,
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

var whoamiCmd = &cobra.Command{
	Use:   "whoami",
	Short: "Show current logged-in user info and login status",
	Run: func(cmd *cobra.Command, args []string) {
		creds, err := config.LoadCredentials()
		if err != nil {
			output.PrintError("Login status: not logged in. Please run 'harnax login' first")
			os.Exit(1)
		}

		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		ctx := context.Background()
		result, err := c.List(ctx, "/api/admin/auth/me", nil)
		if err != nil {
			if apiErr, ok := err.(*client.APIError); ok && apiErr.Code == 401 {
				output.PrintError("Login status: token expired or invalid. Please run 'harnax login' to re-authenticate.")
				os.Exit(2)
			}
			exitAPIError(err)
		}

		var me struct {
			ID        int64  `json:"id"`
			Username  string `json:"username"`
			Nickname  string `json:"nickname"`
			Email     string `json:"email"`
			Phone     string `json:"phone"`
			IsAdmin   int    `json:"isAdmin"`
			TenantID  int64  `json:"tenantId"`
			AuthMode  string `json:"authMode"`
			ExpiresAt int64  `json:"expiresAt"`
		}
		if err := result.DecodeData(&me); err != nil {
			exitError(fmt.Sprintf("failed to parse user info: %v", err))
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		mode := "jwt"
		if creds.Mode == "internal" {
			mode = "internal secret"
		}

		rows := [][]string{
			{"Login Status", "logged in (token valid)"},
			{"Auth Mode", mode},
			{"Username", me.Username},
		}
		if me.AuthMode != "internal" {
			role := "user"
			if me.IsAdmin == 1 {
				role = "admin"
			}
			tenantID := "-"
			if me.TenantID > 0 {
				tenantID = fmt.Sprintf("%d", me.TenantID)
			}
			rows = append(rows,
				[]string{"User ID", fmt.Sprintf("%d", me.ID)},
				[]string{"Nickname", me.Nickname},
				[]string{"Email", me.Email},
				[]string{"Phone", me.Phone},
				[]string{"Role", role},
				[]string{"Tenant ID", tenantID},
			)
			if me.ExpiresAt > 0 {
				expires := time.UnixMilli(me.ExpiresAt)
				rows = append(rows, []string{"Token Expires",
					fmt.Sprintf("%s (in %s)", expires.Format("2006-01-02 15:04:05"), time.Until(expires).Round(time.Second))})
			}
		}
		output.PrintKeyValue(rows)
	},
}

func init() {
	loginCmd.Flags().String("username", "", "Username (required)")
	loginCmd.Flags().String("password", "", "Password (required)")
	loginCmd.MarkFlagRequired("username")
	loginCmd.MarkFlagRequired("password")

	rootCmd.AddCommand(loginCmd)
	rootCmd.AddCommand(logoutCmd)
	rootCmd.AddCommand(whoamiCmd)
}
