package cmd

import (
	"context"
	"net/http"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const modelProviderBasePath = "/api/admin/model-providers"

type ModelProvider struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Type       string `json:"type"`
	URL        string `json:"url"`
	ApiKey     string `json:"apiKey"`
	Status     int    `json:"status"`
	Public     bool   `json:"public"`
	CreateTime string `json:"createTime"`
}

var modelProviderCmd = &cobra.Command{
	Use:   "model-provider",
	Short: "Manage model providers",
}

var modelProviderListCmd = &cobra.Command{
	Use:   "list",
	Short: "List model providers",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			params["name"] = v
		}
		if v, _ := cmd.Flags().GetString("type"); v != "" {
			params["type"] = v
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		page, _ := cmd.Flags().GetInt("page")
		size, _ := cmd.Flags().GetInt("size")
		params["pageNum"] = strconv.Itoa(page)
		params["pageSize"] = strconv.Itoa(size)

		result, err := c.List(context.Background(), modelProviderBasePath+"/page", params)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			var raw any
			result.DecodeData(&raw)
			output.PrintJSON(raw)
			return
		}

		var pageData client.Page
		if err := result.DecodeData(&pageData); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		var providers []ModelProvider
		if err := pageData.DecodeList(&providers); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Type", "Status", "Public"}
		rows := make([][]string, len(providers))
		for i, p := range providers {
			rows[i] = []string{
				strconv.FormatInt(p.ID, 10),
				p.Name,
				p.Type,
				output.StatusText(p.Status),
				output.BoolText(p.Public),
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var modelProviderGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get model provider details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), modelProviderBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			var raw any
			result.DecodeData(&raw)
			output.PrintJSON(raw)
			return
		}

		var provider ModelProvider
		if err := result.DecodeData(&provider); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(provider.ID, 10)},
			{"Name", provider.Name},
			{"Type", provider.Type},
			{"URL", provider.URL},
			{"Status", output.StatusText(provider.Status)},
			{"Public", output.BoolText(provider.Public)},
			{"Created", provider.CreateTime},
		})
	},
}

var modelProviderCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a model provider",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); cmd.Flags().Changed("name") {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetString("type"); cmd.Flags().Changed("type") {
			body["type"] = v
		}
		if v, _ := cmd.Flags().GetString("url"); cmd.Flags().Changed("url") {
			body["url"] = v
		}
		if v, _ := cmd.Flags().GetString("api-key"); cmd.Flags().Changed("api-key") {
			body["apiKey"] = v
		}

		result, err := c.Create(context.Background(), modelProviderBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			var raw any
			result.DecodeData(&raw)
			output.PrintJSON(raw)
			return
		}

		output.PrintSuccess("Model provider created successfully")
	},
}

var modelProviderUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a model provider",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); cmd.Flags().Changed("name") {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetString("type"); cmd.Flags().Changed("type") {
			body["type"] = v
		}
		if v, _ := cmd.Flags().GetString("url"); cmd.Flags().Changed("url") {
			body["url"] = v
		}
		if v, _ := cmd.Flags().GetString("api-key"); cmd.Flags().Changed("api-key") {
			body["apiKey"] = v
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), modelProviderBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model provider updated successfully")
	},
}

var modelProviderDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a model provider",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), modelProviderBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model provider deleted successfully")
	},
}

var modelProviderToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle model provider status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), modelProviderBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model provider status toggled successfully")
	},
}

var modelProviderTestCmd = &cobra.Command{
	Use:   "test <id>",
	Short: "Test a model provider connection",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := modelProviderBasePath + "/" + args[0] + "/test"
		result, err := c.Request(context.Background(), http.MethodPost, path, nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			var raw any
			result.DecodeData(&raw)
			output.PrintJSON(raw)
			return
		}

		output.PrintSuccess("Model provider test successful")
	},
}

var modelProviderStatsCmd = &cobra.Command{
	Use:   "stats <id>",
	Short: "Get model provider statistics",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := modelProviderBasePath + "/" + args[0] + "/stats"
		result, err := c.Request(context.Background(), http.MethodGet, path, nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			var raw any
			result.DecodeData(&raw)
			output.PrintJSON(raw)
			return
		}

		var stats map[string]any
		if err := result.DecodeData(&stats); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		pairs := make([][]string, 0, len(stats))
		for k, v := range stats {
			valStr := "-"
			switch val := v.(type) {
			case float64:
				valStr = strconv.FormatFloat(val, 'f', -1, 64)
			case string:
				valStr = val
			case bool:
				valStr = output.BoolText(val)
			}
			pairs = append(pairs, []string{k, valStr})
		}
		output.PrintKeyValue(pairs)
	},
}

func init() {
	modelProviderListCmd.Flags().String("name", "", "Filter by name")
	modelProviderListCmd.Flags().String("type", "", "Filter by type")
	modelProviderListCmd.Flags().Int("status", -1, "Filter by status")
	modelProviderListCmd.Flags().Int("page", 1, "Page number")
	modelProviderListCmd.Flags().Int("size", 10, "Page size")

	modelProviderCreateCmd.Flags().String("name", "", "Provider name")
	modelProviderCreateCmd.Flags().String("type", "", "Provider type")
	modelProviderCreateCmd.Flags().String("url", "", "Provider URL")
	modelProviderCreateCmd.Flags().String("api-key", "", "API key")
	modelProviderCreateCmd.MarkFlagRequired("name")
	modelProviderCreateCmd.MarkFlagRequired("type")

	modelProviderUpdateCmd.Flags().String("name", "", "Provider name")
	modelProviderUpdateCmd.Flags().String("type", "", "Provider type")
	modelProviderUpdateCmd.Flags().String("url", "", "Provider URL")
	modelProviderUpdateCmd.Flags().String("api-key", "", "API key")

	modelProviderCmd.AddCommand(modelProviderListCmd)
	modelProviderCmd.AddCommand(modelProviderGetCmd)
	modelProviderCmd.AddCommand(modelProviderCreateCmd)
	modelProviderCmd.AddCommand(modelProviderUpdateCmd)
	modelProviderCmd.AddCommand(modelProviderDeleteCmd)
	modelProviderCmd.AddCommand(modelProviderToggleCmd)
	modelProviderCmd.AddCommand(modelProviderTestCmd)
	modelProviderCmd.AddCommand(modelProviderStatsCmd)

	rootCmd.AddCommand(modelProviderCmd)
}
