package cmd

import (
	"context"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const modelBasePath = "/api/admin/models"

// thinkingModeText maps the model three-state thinking mode to readable text.
func thinkingModeText(mode int) string {
	switch mode {
	case 2:
		return "Required"
	case 1:
		return "Optional"
	default:
		return "Not Supported"
	}
}

type Model struct {
	ID               int64  `json:"id"`
	Name             string `json:"name"`
	ProviderID       int64  `json:"providerId"`
	ModelType        string `json:"modelType"`
	SupportInternet  int    `json:"supportInternet"`
	SupportReasoning int    `json:"supportReasoning"`
	SupportTool      int    `json:"supportTool"`
	SupportMcp       int    `json:"supportMcp"`
	SupportVision    int    `json:"supportVision"`
	ThinkingMode     int    `json:"thinkingMode"`
	Status           int    `json:"status"`
	CreateTime       string `json:"createTime"`
}

var modelCmd = &cobra.Command{
	Use:   "model",
	Short: "Manage models",
}

var modelListCmd = &cobra.Command{
	Use:   "list",
	Short: "List models",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			params["name"] = v
		}
		if cmd.Flags().Changed("provider-id") {
			v, _ := cmd.Flags().GetInt64("provider-id")
			params["providerId"] = strconv.FormatInt(v, 10)
		}
		if v, _ := cmd.Flags().GetString("model-type"); v != "" {
			params["modelType"] = v
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		page, _ := cmd.Flags().GetInt("page")
		size, _ := cmd.Flags().GetInt("size")
		params["pageNum"] = strconv.Itoa(page)
		params["pageSize"] = strconv.Itoa(size)

		result, err := c.List(context.Background(), modelBasePath+"/page", params)
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

		var models []Model
		if err := pageData.DecodeList(&models); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Provider ID", "Model Type", "Status", "Created"}
		rows := make([][]string, len(models))
		for i, m := range models {
			rows[i] = []string{
				strconv.FormatInt(m.ID, 10),
				m.Name,
				strconv.FormatInt(m.ProviderID, 10),
				m.ModelType,
				output.StatusText(m.Status),
				m.CreateTime,
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var modelGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get model details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), modelBasePath, args[0])
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

		var model Model
		if err := result.DecodeData(&model); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(model.ID, 10)},
			{"Name", model.Name},
			{"Provider ID", strconv.FormatInt(model.ProviderID, 10)},
			{"Model Type", model.ModelType},
			{"Support Internet", output.BoolText(model.SupportInternet == 1)},
			{"Support Reasoning", output.BoolText(model.SupportReasoning == 1)},
			{"Support Tool", output.BoolText(model.SupportTool == 1)},
			{"Support MCP", output.BoolText(model.SupportMcp == 1)},
			{"Support Vision", output.BoolText(model.SupportVision == 1)},
			{"Thinking Mode", thinkingModeText(model.ThinkingMode)},
			{"Status", output.StatusText(model.Status)},
			{"Created", model.CreateTime},
		})
	},
}

var modelCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a model",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); cmd.Flags().Changed("name") {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetInt64("provider-id"); cmd.Flags().Changed("provider-id") {
			body["providerId"] = v
		}
		if v, _ := cmd.Flags().GetString("model-type"); cmd.Flags().Changed("model-type") {
			body["modelType"] = v
		}
		if cmd.Flags().Changed("support-internet") {
			v, _ := cmd.Flags().GetBool("support-internet")
			body["supportInternet"] = v
		}
		if cmd.Flags().Changed("support-reasoning") {
			v, _ := cmd.Flags().GetBool("support-reasoning")
			body["supportReasoning"] = v
		}
		if cmd.Flags().Changed("support-tool") {
			v, _ := cmd.Flags().GetBool("support-tool")
			body["supportTool"] = v
		}
		if cmd.Flags().Changed("support-mcp") {
			v, _ := cmd.Flags().GetBool("support-mcp")
			body["supportMcp"] = v
		}
		if cmd.Flags().Changed("support-vision") {
			v, _ := cmd.Flags().GetBool("support-vision")
			body["supportVision"] = v
		}

		result, err := c.Create(context.Background(), modelBasePath, body)
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

		output.PrintSuccess("Model created successfully")
	},
}

var modelUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a model",
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
		if v, _ := cmd.Flags().GetInt64("provider-id"); cmd.Flags().Changed("provider-id") {
			body["providerId"] = v
		}
		if v, _ := cmd.Flags().GetString("model-type"); cmd.Flags().Changed("model-type") {
			body["modelType"] = v
		}
		if cmd.Flags().Changed("support-internet") {
			v, _ := cmd.Flags().GetBool("support-internet")
			body["supportInternet"] = v
		}
		if cmd.Flags().Changed("support-reasoning") {
			v, _ := cmd.Flags().GetBool("support-reasoning")
			body["supportReasoning"] = v
		}
		if cmd.Flags().Changed("support-tool") {
			v, _ := cmd.Flags().GetBool("support-tool")
			body["supportTool"] = v
		}
		if cmd.Flags().Changed("support-mcp") {
			v, _ := cmd.Flags().GetBool("support-mcp")
			body["supportMcp"] = v
		}
		if cmd.Flags().Changed("support-vision") {
			v, _ := cmd.Flags().GetBool("support-vision")
			body["supportVision"] = v
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), modelBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model updated successfully")
	},
}

var modelDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a model",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), modelBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model deleted successfully")
	},
}

var modelToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle model status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), modelBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Model status toggled successfully")
	},
}

func init() {
	modelListCmd.Flags().String("name", "", "Filter by name")
	modelListCmd.Flags().Int64("provider-id", 0, "Filter by provider ID")
	modelListCmd.Flags().String("model-type", "", "Filter by model type")
	modelListCmd.Flags().Int("status", -1, "Filter by status")
	modelListCmd.Flags().Int("page", 1, "Page number")
	modelListCmd.Flags().Int("size", 10, "Page size")

	modelCreateCmd.Flags().String("name", "", "Model name")
	modelCreateCmd.Flags().Int64("provider-id", 0, "Provider ID")
	modelCreateCmd.Flags().String("model-type", "", "Model type")
	modelCreateCmd.Flags().Bool("support-internet", false, "Support internet access")
	modelCreateCmd.Flags().Bool("support-reasoning", false, "Support reasoning")
	modelCreateCmd.Flags().Bool("support-tool", false, "Support tool calling")
	modelCreateCmd.Flags().Bool("support-mcp", false, "Support MCP")
	modelCreateCmd.Flags().Bool("support-vision", false, "Support vision")
	modelCreateCmd.MarkFlagRequired("name")
	modelCreateCmd.MarkFlagRequired("provider-id")
	modelCreateCmd.MarkFlagRequired("model-type")

	modelUpdateCmd.Flags().String("name", "", "Model name")
	modelUpdateCmd.Flags().Int64("provider-id", 0, "Provider ID")
	modelUpdateCmd.Flags().String("model-type", "", "Model type")
	modelUpdateCmd.Flags().Bool("support-internet", false, "Support internet access")
	modelUpdateCmd.Flags().Bool("support-reasoning", false, "Support reasoning")
	modelUpdateCmd.Flags().Bool("support-tool", false, "Support tool calling")
	modelUpdateCmd.Flags().Bool("support-mcp", false, "Support MCP")
	modelUpdateCmd.Flags().Bool("support-vision", false, "Support vision")

	modelCmd.AddCommand(modelListCmd)
	modelCmd.AddCommand(modelGetCmd)
	modelCmd.AddCommand(modelCreateCmd)
	modelCmd.AddCommand(modelUpdateCmd)
	modelCmd.AddCommand(modelDeleteCmd)
	modelCmd.AddCommand(modelToggleCmd)

	rootCmd.AddCommand(modelCmd)
}
