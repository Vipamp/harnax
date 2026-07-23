package cmd

import (
	"context"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const agentBasePath = "/api/admin/agents"

type Agent struct {
	ID           int64  `json:"id"`
	Name         string `json:"name"`
	ModelID      int64  `json:"modelId"`
	SystemPrompt string `json:"systemPrompt"`
	Status       int    `json:"status"`
	CreateTime   string `json:"createTime"`
}

var agentCmd = &cobra.Command{
	Use:   "agent",
	Short: "Manage agents",
}

var agentListCmd = &cobra.Command{
	Use:   "list",
	Short: "List agents",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			params["name"] = v
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		page, _ := cmd.Flags().GetInt("page")
		size, _ := cmd.Flags().GetInt("size")
		params["pageNum"] = strconv.Itoa(page)
		params["pageSize"] = strconv.Itoa(size)

		result, err := c.List(context.Background(), agentBasePath+"/page", params)
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

		var agents []Agent
		if err := pageData.DecodeList(&agents); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Model ID", "Status", "Created"}
		rows := make([][]string, len(agents))
		for i, a := range agents {
			rows[i] = []string{
				strconv.FormatInt(a.ID, 10),
				a.Name,
				strconv.FormatInt(a.ModelID, 10),
				output.StatusText(a.Status),
				a.CreateTime,
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var agentGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get agent details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), agentBasePath, args[0])
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

		var agent Agent
		if err := result.DecodeData(&agent); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(agent.ID, 10)},
			{"Name", agent.Name},
			{"Model ID", strconv.FormatInt(agent.ModelID, 10)},
			{"System Prompt", agent.SystemPrompt},
			{"Status", output.StatusText(agent.Status)},
			{"Created", agent.CreateTime},
		})
	},
}

var agentCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create an agent",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); cmd.Flags().Changed("name") {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetInt64("model-id"); cmd.Flags().Changed("model-id") {
			body["modelId"] = v
		}
		if v, _ := cmd.Flags().GetString("system-prompt"); cmd.Flags().Changed("system-prompt") {
			body["systemPrompt"] = v
		}

		result, err := c.Create(context.Background(), agentBasePath, body)
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

		output.PrintSuccess("Agent created successfully")
	},
}

var agentUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update an agent",
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
		if v, _ := cmd.Flags().GetInt64("model-id"); cmd.Flags().Changed("model-id") {
			body["modelId"] = v
		}
		if v, _ := cmd.Flags().GetString("system-prompt"); cmd.Flags().Changed("system-prompt") {
			body["systemPrompt"] = v
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), agentBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Agent updated successfully")
	},
}

var agentDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete an agent",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), agentBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Agent deleted successfully")
	},
}

var agentToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle agent status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), agentBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Agent status toggled successfully")
	},
}

func init() {
	agentListCmd.Flags().String("name", "", "Filter by name")
	agentListCmd.Flags().Int("status", -1, "Filter by status")
	agentListCmd.Flags().Int("page", 1, "Page number")
	agentListCmd.Flags().Int("size", 10, "Page size")

	agentCreateCmd.Flags().String("name", "", "Agent name")
	agentCreateCmd.Flags().Int64("model-id", 0, "Model ID")
	agentCreateCmd.Flags().String("system-prompt", "", "System prompt")
	agentCreateCmd.MarkFlagRequired("name")
	agentCreateCmd.MarkFlagRequired("model-id")
	agentCreateCmd.MarkFlagRequired("system-prompt")

	agentUpdateCmd.Flags().String("name", "", "Agent name")
	agentUpdateCmd.Flags().Int64("model-id", 0, "Model ID")
	agentUpdateCmd.Flags().String("system-prompt", "", "System prompt")

	agentCmd.AddCommand(agentListCmd)
	agentCmd.AddCommand(agentGetCmd)
	agentCmd.AddCommand(agentCreateCmd)
	agentCmd.AddCommand(agentUpdateCmd)
	agentCmd.AddCommand(agentDeleteCmd)
	agentCmd.AddCommand(agentToggleCmd)

	rootCmd.AddCommand(agentCmd)
}
