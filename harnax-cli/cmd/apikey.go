package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const apikeyPath = "/api/admin/api-keys"

type APIKey struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	KeyPrefix  string `json:"keyPrefix"`
	Scopes     string `json:"scopes"`
	Enabled    int    `json:"enabled"`
	ExpiresAt  string `json:"expiresAt"`
	CreateTime string `json:"createTime"`
}

type APIKeyCreated struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	RawKey    string `json:"rawKey"`
	KeyPrefix string `json:"keyPrefix"`
}

var apikeyCmd = &cobra.Command{
	Use:   "api-key",
	Short: "Manage API keys",
}

var apikeyListCmd = &cobra.Command{
	Use:   "list",
	Short: "List API keys",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		params := map[string]string{}
		if cmd.Flags().Changed("keyword") {
			params["keyword"], _ = cmd.Flags().GetString("keyword")
		}
		if cmd.Flags().Changed("page") {
			v, _ := cmd.Flags().GetInt("page")
			params["pageNum"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("size") {
			v, _ := cmd.Flags().GetInt("size")
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(ctx, apikeyPath+"/page", params)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var page client.Page
		if err := result.DecodeData(&page); err != nil {
			exitAPIError(err)
		}
		var items []APIKey
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"ID", "Name", "Scopes", "Enabled", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.Name,
				item.Scopes,
				output.BoolText(item.Enabled == 1),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var apikeyGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get an API key",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Get(ctx, apikeyPath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item APIKey
		if err := result.DecodeData(&item); err != nil {
			exitAPIError(err)
		}
		output.PrintKeyValue([][]string{
			{"ID", fmt.Sprintf("%d", item.ID)},
			{"Name", item.Name},
			{"Key Prefix", item.KeyPrefix},
			{"Scopes", item.Scopes},
			{"Enabled", output.BoolText(item.Enabled == 1)},
			{"Expires At", item.ExpiresAt},
			{"Created", item.CreateTime},
		})
	},
}

var apikeyCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create an API key",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("scopes") {
			body["scopes"], _ = cmd.Flags().GetString("scopes")
		}
		if cmd.Flags().Changed("rate-limit") {
			body["rateLimit"], _ = cmd.Flags().GetInt("rate-limit")
		}
		if cmd.Flags().Changed("expires-at") {
			body["expiresAt"], _ = cmd.Flags().GetString("expires-at")
		}

		result, err := c.Create(ctx, apikeyPath, body)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		output.PrintSuccess("API key created successfully. Save the key now -- it will not be shown again:")
		var created APIKeyCreated
		if err := result.DecodeData(&created); err == nil {
			output.PrintKeyValue([][]string{
				{"ID", fmt.Sprintf("%d", created.ID)},
				{"Name", created.Name},
				{"Key", created.RawKey},
			})
		}
	},
}

var apikeyUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update an API key",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("scopes") {
			body["scopes"], _ = cmd.Flags().GetString("scopes")
		}
		if cmd.Flags().Changed("rate-limit") {
			body["rateLimit"], _ = cmd.Flags().GetInt("rate-limit")
		}
		if cmd.Flags().Changed("enabled") {
			body["enabled"], _ = cmd.Flags().GetInt("enabled")
		}
		if cmd.Flags().Changed("expires-at") {
			body["expiresAt"], _ = cmd.Flags().GetString("expires-at")
		}

		_, err = c.Update(ctx, apikeyPath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("API key updated successfully.")
	},
}

var apikeyDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete an API key",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Delete(ctx, apikeyPath, args[0])
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("API key deleted successfully.")
	},
}

var apikeyToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle an API key",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		enabled := 1
		if cmd.Flags().Changed("enabled") {
			enabled, _ = cmd.Flags().GetInt("enabled")
		} else {
			result, err := c.Get(ctx, apikeyPath, args[0])
			if err != nil {
				exitAPIError(err)
			}
			var current APIKey
			if err := result.DecodeData(&current); err != nil {
				exitAPIError(err)
			}
			if current.Enabled == 1 {
				enabled = 0
			}
		}

		_, err = c.Request(ctx, "PUT", fmt.Sprintf("%s/toggle/%s", apikeyPath, args[0]), map[string]string{"enabled": strconv.Itoa(enabled)}, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("API key toggled successfully.")
	},
}

var apikeyRegenerateCmd = &cobra.Command{
	Use:   "regenerate <id>",
	Short: "Regenerate an API key (new key shown only once)",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Request(ctx, "POST", fmt.Sprintf("%s/%s/regenerate", apikeyPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		output.PrintSuccess("API key regenerated successfully. Save the key now -- it will not be shown again:")
		var created APIKeyCreated
		if err := result.DecodeData(&created); err == nil {
			output.PrintKeyValue([][]string{
				{"ID", fmt.Sprintf("%d", created.ID)},
				{"Name", created.Name},
				{"Key", created.RawKey},
			})
		} else {
			output.PrintJSON(result.Data)
		}
	},
}

var apikeyPermanentCmd = &cobra.Command{
	Use:   "permanent",
	Short: "Get your permanent API key",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.List(ctx, apikeyPath+"/my-permanent-key", nil)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item APIKey
		if err := result.DecodeData(&item); err == nil {
			output.PrintKeyValue([][]string{
				{"ID", fmt.Sprintf("%d", item.ID)},
				{"Name", item.Name},
				{"Key Prefix", item.KeyPrefix},
				{"Scopes", item.Scopes},
				{"Enabled", output.BoolText(item.Enabled == 1)},
			})
		} else {
			output.PrintJSON(result.Data)
		}
	},
}

func init() {
	apikeyListCmd.Flags().String("keyword", "", "Filter by keyword")
	apikeyListCmd.Flags().Int("page", 1, "Page number")
	apikeyListCmd.Flags().Int("size", 20, "Page size")

	apikeyCreateCmd.Flags().String("name", "", "API key name")
	apikeyCreateCmd.MarkFlagRequired("name")
	apikeyCreateCmd.Flags().String("scopes", "", "Comma-separated scopes (e.g. api:chat,api:session)")
	apikeyCreateCmd.MarkFlagRequired("scopes")
	apikeyCreateCmd.Flags().Int("rate-limit", 0, "Rate limit per minute")
	apikeyCreateCmd.Flags().String("expires-at", "", "Expiration time in ISO format (e.g. 2026-12-31T23:59:59)")

	apikeyUpdateCmd.Flags().String("scopes", "", "Comma-separated scopes")
	apikeyUpdateCmd.Flags().Int("rate-limit", 0, "Rate limit per minute")
	apikeyUpdateCmd.Flags().Int("enabled", 0, "Enabled status (0/1)")
	apikeyUpdateCmd.Flags().String("expires-at", "", "Expiration time in ISO format")

	apikeyToggleCmd.Flags().Int("enabled", 0, "Target enabled status (0/1); defaults to flipping current status")

	apikeyCmd.AddCommand(apikeyListCmd)
	apikeyCmd.AddCommand(apikeyGetCmd)
	apikeyCmd.AddCommand(apikeyCreateCmd)
	apikeyCmd.AddCommand(apikeyUpdateCmd)
	apikeyCmd.AddCommand(apikeyDeleteCmd)
	apikeyCmd.AddCommand(apikeyToggleCmd)
	apikeyCmd.AddCommand(apikeyRegenerateCmd)
	apikeyCmd.AddCommand(apikeyPermanentCmd)

	rootCmd.AddCommand(apikeyCmd)
}
