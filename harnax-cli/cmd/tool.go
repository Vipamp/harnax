package cmd

import (
	"context"
	"fmt"
	"net/http"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const toolBasePath = "/api/admin/tools"

type Tool struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Status     int    `json:"status"`
	ReadOnly   int    `json:"readOnly"`
	CreateTime string `json:"createTime"`
}

var toolCmd = &cobra.Command{
	Use:   "tool",
	Short: "Query tools (builtin only, read-only)",
}

var toolListCmd = &cobra.Command{
	Use:   "list",
	Short: "List tools",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("keyword"); v != "" {
			params["keyword"] = v
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		page, _ := cmd.Flags().GetInt("page")
		size, _ := cmd.Flags().GetInt("size")
		params["pageNum"] = strconv.Itoa(page)
		params["pageSize"] = strconv.Itoa(size)

		result, err := c.List(context.Background(), toolBasePath+"/page", params)
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

		var tools []Tool
		if err := pageData.DecodeList(&tools); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly == 1),
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var toolGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get tool details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), toolBasePath, args[0])
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

		var tool Tool
		if err := result.DecodeData(&tool); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(tool.ID, 10)},
			{"Name", tool.Name},
			{"Status", output.StatusText(tool.Status)},
			{"Read Only", output.BoolText(tool.ReadOnly == 1)},
			{"Created", tool.CreateTime},
		})
	},
}

var toolAvailableCmd = &cobra.Command{
	Use:   "available",
	Short: "List available tools",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.List(context.Background(), toolBasePath+"/available", nil)
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

		var tools []Tool
		if err := result.DecodeData(&tools); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		headers := []string{"ID", "Name", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly == 1),
			}
		}
		output.PrintTable(headers, rows)
	},
}

var toolBuiltinCmd = &cobra.Command{
	Use:   "builtin",
	Short: "List builtin tools",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.List(context.Background(), toolBasePath+"/builtin", nil)
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

		var tools []Tool
		if err := result.DecodeData(&tools); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		headers := []string{"ID", "Name", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly == 1),
			}
		}
		output.PrintTable(headers, rows)
	},
}

var toolEnvParamsCmd = &cobra.Command{
	Use:   "env-params <id>",
	Short: "Get required environment parameters for a tool",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := fmt.Sprintf("%s/%s/required-env-params", toolBasePath, args[0])
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

		var params []string
		if err := result.DecodeData(&params); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		if len(params) == 0 {
			fmt.Println("No required environment parameters")
			return
		}

		headers := []string{"Parameter"}
		rows := make([][]string, len(params))
		for i, p := range params {
			rows[i] = []string{p}
		}
		output.PrintTable(headers, rows)
	},
}

func init() {
	toolListCmd.Flags().String("keyword", "", "Filter by keyword")
	toolListCmd.Flags().Int("status", -1, "Filter by status")
	toolListCmd.Flags().Int("page", 1, "Page number")
	toolListCmd.Flags().Int("size", 10, "Page size")

	toolCmd.AddCommand(toolListCmd)
	toolCmd.AddCommand(toolGetCmd)
	toolCmd.AddCommand(toolAvailableCmd)
	toolCmd.AddCommand(toolBuiltinCmd)
	toolCmd.AddCommand(toolEnvParamsCmd)

	rootCmd.AddCommand(toolCmd)
}
