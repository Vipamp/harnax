package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const sessionBasePath = "/api/admin/sessions"

type Session struct {
	ID         int64  `json:"id"`
	Title      string `json:"title"`
	SessionID  string `json:"sessionId"`
	AgentID    string `json:"agentId"`
	ModelID    string `json:"modelId"`
	Status     int    `json:"status"`
	CreateTime string `json:"createTime"`
}

var sessionCmd = &cobra.Command{
	Use:   "session",
	Short: "Manage sessions",
}

var sessionListCmd = &cobra.Command{
	Use:   "list",
	Short: "List sessions",
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
		if v, _ := cmd.Flags().GetInt("page"); v > 0 {
			params["pageNum"] = strconv.Itoa(v)
		}
		if v, _ := cmd.Flags().GetInt("size"); v > 0 {
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(context.Background(), sessionBasePath+"/page", params)
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

		var items []Session
		if err := page.DecodeList(&items); err != nil {
			exitError("decode list: " + err.Error())
		}

		headers := []string{"ID", "Title", "Session ID", "Agent ID", "Status", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				strconv.FormatInt(item.ID, 10),
				item.Title,
				item.SessionID,
				item.AgentID,
				output.StatusText(item.Status),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var sessionGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get session details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), sessionBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item Session
		if err := result.DecodeData(&item); err != nil {
			exitError("decode data: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(item.ID, 10)},
			{"Title", item.Title},
			{"Session ID", item.SessionID},
			{"Agent ID", item.AgentID},
			{"Model ID", item.ModelID},
			{"Status", output.StatusText(item.Status)},
			{"Created", item.CreateTime},
		})
	},
}

var sessionCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a session",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("title"); v != "" {
			body["title"] = v
		}
		if v, _ := cmd.Flags().GetString("agent-id"); v != "" {
			body["agentId"] = v
		}
		if cmd.Flags().Changed("model-id") {
			v, _ := cmd.Flags().GetString("model-id")
			body["modelId"] = v
		}

		result, err := c.Create(context.Background(), sessionBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Session created successfully.")
	},
}

var sessionUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a session",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if cmd.Flags().Changed("title") {
			v, _ := cmd.Flags().GetString("title")
			body["title"] = v
		}
		if cmd.Flags().Changed("model-id") {
			v, _ := cmd.Flags().GetString("model-id")
			body["modelId"] = v
		}

		result, err := c.Update(context.Background(), sessionBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Session updated successfully.")
	},
}

var sessionDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a session",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Delete(context.Background(), sessionBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Session deleted successfully.")
	},
}

var sessionToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle session status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Toggle(context.Background(), sessionBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Session status toggled successfully.")
	},
}

var sessionConfigCmd = &cobra.Command{
	Use:   "config",
	Short: "Manage session configuration",
}

var sessionConfigGetCmd = &cobra.Command{
	Use:   "get <session-id>",
	Short: "Get session configuration",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		path := fmt.Sprintf("%s/%s/config", sessionBasePath, args[0])
		result, err := c.Request(context.Background(), "GET", path, nil, nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var data map[string]any
		if err := result.DecodeData(&data); err != nil {
			exitError("decode data: " + err.Error())
		}

		pairs := make([][]string, 0, len(data))
		for k, v := range data {
			pairs = append(pairs, []string{k, fmt.Sprintf("%v", v)})
		}
		output.PrintKeyValue(pairs)
	},
}

var sessionConfigUpdateCmd = &cobra.Command{
	Use:   "update <session-id>",
	Short: "Update session configuration",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if cmd.Flags().Changed("enable-think") {
			v, _ := cmd.Flags().GetBool("enable-think")
			body["enableThink"] = v
		}
		if cmd.Flags().Changed("enable-search") {
			v, _ := cmd.Flags().GetBool("enable-search")
			body["enableSearch"] = v
		}
		if cmd.Flags().Changed("enable-plan") {
			v, _ := cmd.Flags().GetBool("enable-plan")
			body["enablePlan"] = v
		}
		if cmd.Flags().Changed("permission-mode") {
			v, _ := cmd.Flags().GetString("permission-mode")
			body["permissionMode"] = v
		}

		path := fmt.Sprintf("%s/%s/config", sessionBasePath, args[0])
		result, err := c.Request(context.Background(), "PUT", path, nil, body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Session configuration updated successfully.")
	},
}

func init() {
	sessionListCmd.Flags().String("keyword", "", "Filter by keyword")
	sessionListCmd.Flags().Int("status", 0, "Filter by status")
	sessionListCmd.Flags().Int("page", 1, "Page number")
	sessionListCmd.Flags().Int("size", 10, "Page size")

	sessionCreateCmd.Flags().String("title", "", "Session title (required)")
	sessionCreateCmd.Flags().String("agent-id", "", "Agent ID (required)")
	sessionCreateCmd.Flags().String("model-id", "", "Model ID")
	sessionCreateCmd.MarkFlagRequired("title")
	sessionCreateCmd.MarkFlagRequired("agent-id")

	sessionUpdateCmd.Flags().String("title", "", "Session title")
	sessionUpdateCmd.Flags().String("model-id", "", "Model ID")

	sessionConfigUpdateCmd.Flags().Bool("enable-think", false, "Enable thinking mode")
	sessionConfigUpdateCmd.Flags().Bool("enable-search", false, "Enable search")
	sessionConfigUpdateCmd.Flags().Bool("enable-plan", false, "Enable planning")
	sessionConfigUpdateCmd.Flags().String("permission-mode", "", "Permission mode")

	sessionConfigCmd.AddCommand(sessionConfigGetCmd)
	sessionConfigCmd.AddCommand(sessionConfigUpdateCmd)

	sessionCmd.AddCommand(sessionListCmd)
	sessionCmd.AddCommand(sessionGetCmd)
	sessionCmd.AddCommand(sessionCreateCmd)
	sessionCmd.AddCommand(sessionUpdateCmd)
	sessionCmd.AddCommand(sessionDeleteCmd)
	sessionCmd.AddCommand(sessionToggleCmd)
	sessionCmd.AddCommand(sessionConfigCmd)

	rootCmd.AddCommand(sessionCmd)
}
