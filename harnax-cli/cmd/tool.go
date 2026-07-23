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
	Type       string `json:"type"`
	Status     int    `json:"status"`
	ReadOnly   bool   `json:"readOnly"`
	CreateTime string `json:"createTime"`
}

var toolCmd = &cobra.Command{
	Use:   "tool",
	Short: "Manage tools",
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

		headers := []string{"ID", "Name", "Type", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				t.Type,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly),
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
			{"Type", tool.Type},
			{"Status", output.StatusText(tool.Status)},
			{"Read Only", output.BoolText(tool.ReadOnly)},
			{"Created", tool.CreateTime},
		})
	},
}

var toolUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a tool",
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
		if cmd.Flags().Changed("read-only") {
			v, _ := cmd.Flags().GetBool("read-only")
			body["readOnly"] = v
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), toolBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Tool updated successfully")
	},
}

var toolDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a tool",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), toolBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Tool deleted successfully")
	},
}

var toolToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle tool status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), toolBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Tool status toggled successfully")
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

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("type"); v != "" {
			params["type"] = v
		}

		result, err := c.List(context.Background(), toolBasePath+"/available", params)
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

		headers := []string{"ID", "Name", "Type", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				t.Type,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly),
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

		headers := []string{"ID", "Name", "Type", "Status", "Read Only"}
		rows := make([][]string, len(tools))
		for i, t := range tools {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				t.Type,
				output.StatusText(t.Status),
				output.BoolText(t.ReadOnly),
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
	toolListCmd.Flags().String("type", "", "Filter by type")
	toolListCmd.Flags().Int("status", -1, "Filter by status")
	toolListCmd.Flags().Int("page", 1, "Page number")
	toolListCmd.Flags().Int("size", 10, "Page size")

	toolUpdateCmd.Flags().String("name", "", "Tool name")
	toolUpdateCmd.Flags().String("type", "", "Tool type")
	toolUpdateCmd.Flags().Bool("read-only", false, "Read only")

	toolAvailableCmd.Flags().String("type", "", "Filter by type")

	toolCmd.AddCommand(toolListCmd)
	toolCmd.AddCommand(toolGetCmd)
	toolCmd.AddCommand(toolUpdateCmd)
	toolCmd.AddCommand(toolDeleteCmd)
	toolCmd.AddCommand(toolToggleCmd)
	toolCmd.AddCommand(toolAvailableCmd)
	toolCmd.AddCommand(toolBuiltinCmd)
	toolCmd.AddCommand(toolEnvParamsCmd)

	rootCmd.AddCommand(toolCmd)
}
