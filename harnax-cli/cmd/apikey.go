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
	Enabled    bool   `json:"enabled"`
	CreateTime string `json:"createTime"`
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

		headers := []string{"ID", "Name", "Enabled", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.Name,
				output.BoolText(item.Enabled),
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
			{"Enabled", output.BoolText(item.Enabled)},
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

		result, err := c.Create(ctx, apikeyPath, body)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		output.PrintSuccess("API key created successfully.")
		if result.Data != nil {
			var keyData any
			if err := result.DecodeData(&keyData); err == nil {
				fmt.Printf("Key: %v\n", keyData)
			}
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
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
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

		_, err = c.Toggle(ctx, apikeyPath, args[0], nil)
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
		var key string
		if err := result.DecodeData(&key); err == nil {
			fmt.Println(key)
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

		var key string
		if err := result.DecodeData(&key); err == nil {
			output.PrintKeyValue([][]string{
				{"Permanent Key", key},
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

	apikeyUpdateCmd.Flags().String("name", "", "API key name")

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
