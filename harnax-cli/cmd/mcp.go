package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const mcpBasePath = "/api/admin/mcp"

type McpServer struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Type       string `json:"type"`
	Command    string `json:"command"`
	URL        string `json:"url"`
	Status     int    `json:"status"`
	CreateTime string `json:"createTime"`
}

var mcpCmd = &cobra.Command{
	Use:   "mcp",
	Short: "Manage MCP servers",
}

var mcpListCmd = &cobra.Command{
	Use:   "list",
	Short: "List MCP servers",
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
		if v, _ := cmd.Flags().GetInt("page"); v > 0 {
			params["pageNum"] = strconv.Itoa(v)
		}
		if v, _ := cmd.Flags().GetInt("size"); v > 0 {
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(context.Background(), mcpBasePath+"/page", params)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var page client.Page
		if err := result.DecodeData(&page); err != nil {
			exitError("decode page: " + err.Error())
		}

		var items []McpServer
		if err := page.DecodeList(&items); err != nil {
			exitError("decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Type", "Status", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				strconv.FormatInt(item.ID, 10),
				item.Name,
				item.Type,
				output.StatusText(item.Status),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var mcpGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get MCP server details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), mcpBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item McpServer
		if err := result.DecodeData(&item); err != nil {
			exitError("decode data: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(item.ID, 10)},
			{"Name", item.Name},
			{"Type", item.Type},
			{"Command", item.Command},
			{"URL", item.URL},
			{"Status", output.StatusText(item.Status)},
			{"Created", item.CreateTime},
		})
	},
}

var mcpCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create an MCP server",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetString("type"); v != "" {
			body["type"] = v
		}
		if v, _ := cmd.Flags().GetString("command"); v != "" {
			body["command"] = v
		}
		if v, _ := cmd.Flags().GetString("url"); v != "" {
			body["url"] = v
		}
		if v, _ := cmd.Flags().GetString("headers"); v != "" {
			var parsed any
			if err := json.Unmarshal([]byte(v), &parsed); err != nil {
				exitError("invalid --headers JSON: " + err.Error())
			}
			body["headers"] = parsed
		}
		if v, _ := cmd.Flags().GetString("env-params"); v != "" {
			var parsed any
			if err := json.Unmarshal([]byte(v), &parsed); err != nil {
				exitError("invalid --env-params JSON: " + err.Error())
			}
			body["envParams"] = parsed
		}

		result, err := c.Create(context.Background(), mcpBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("MCP server created successfully.")
	},
}

var mcpUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update an MCP server",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			v, _ := cmd.Flags().GetString("name")
			body["name"] = v
		}
		if cmd.Flags().Changed("type") {
			v, _ := cmd.Flags().GetString("type")
			body["type"] = v
		}
		if cmd.Flags().Changed("command") {
			v, _ := cmd.Flags().GetString("command")
			body["command"] = v
		}
		if cmd.Flags().Changed("url") {
			v, _ := cmd.Flags().GetString("url")
			body["url"] = v
		}

		result, err := c.Update(context.Background(), mcpBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("MCP server updated successfully.")
	},
}

var mcpDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete an MCP server",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Delete(context.Background(), mcpBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("MCP server deleted successfully.")
	},
}

var mcpToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle MCP server status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Toggle(context.Background(), mcpBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("MCP server status toggled successfully.")
	},
}

var mcpTestCmd = &cobra.Command{
	Use:   "test <id>",
	Short: "Test MCP server connectivity",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := fmt.Sprintf("%s/%s/connectivity-test", mcpBasePath, args[0])
		result, err := c.Request(context.Background(), "POST", path, nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var data any
		if err := result.DecodeData(&data); err != nil {
			exitError("decode data: " + err.Error())
		}
		output.PrintSuccess("Connectivity test passed.")
		output.PrintJSON(data)
	},
}

var mcpListToolsCmd = &cobra.Command{
	Use:   "list-tools <id>",
	Short: "List tools from MCP server",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := fmt.Sprintf("%s/%s/list_tools", mcpBasePath, args[0])
		result, err := c.Request(context.Background(), "GET", path, nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var tools []map[string]any
		if err := result.DecodeData(&tools); err != nil {
			exitError("decode data: " + err.Error())
		}

		if len(tools) == 0 {
			fmt.Println("No tools found.")
			return
		}

		headers := []string{"Name", "Description"}
		rows := make([][]string, 0, len(tools))
		for _, t := range tools {
			name, _ := t["name"].(string)
			desc, _ := t["description"].(string)
			rows = append(rows, []string{name, desc})
		}
		output.PrintTable(headers, rows)
	},
}

func init() {
	mcpListCmd.Flags().String("keyword", "", "Filter by keyword")
	mcpListCmd.Flags().String("type", "", "Filter by type (stdio, sse, streamablehttp)")
	mcpListCmd.Flags().Int("status", 0, "Filter by status")
	mcpListCmd.Flags().Int("page", 1, "Page number")
	mcpListCmd.Flags().Int("size", 10, "Page size")

	mcpCreateCmd.Flags().String("name", "", "Server name (required)")
	mcpCreateCmd.Flags().String("type", "", "Server type (required)")
	mcpCreateCmd.Flags().String("command", "", "Command to execute")
	mcpCreateCmd.Flags().String("url", "", "Server URL")
	mcpCreateCmd.Flags().String("headers", "", "Headers as JSON string")
	mcpCreateCmd.Flags().String("env-params", "", "Environment params as JSON string")
	mcpCreateCmd.MarkFlagRequired("name")
	mcpCreateCmd.MarkFlagRequired("type")

	mcpUpdateCmd.Flags().String("name", "", "Server name")
	mcpUpdateCmd.Flags().String("type", "", "Server type")
	mcpUpdateCmd.Flags().String("command", "", "Command to execute")
	mcpUpdateCmd.Flags().String("url", "", "Server URL")

	mcpCmd.AddCommand(mcpListCmd)
	mcpCmd.AddCommand(mcpGetCmd)
	mcpCmd.AddCommand(mcpCreateCmd)
	mcpCmd.AddCommand(mcpUpdateCmd)
	mcpCmd.AddCommand(mcpDeleteCmd)
	mcpCmd.AddCommand(mcpToggleCmd)
	mcpCmd.AddCommand(mcpTestCmd)
	mcpCmd.AddCommand(mcpListToolsCmd)

	rootCmd.AddCommand(mcpCmd)
}
