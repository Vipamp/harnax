package cmd

import (
	"context"
	"strconv"
	"strings"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const cliBasePath = "/api/admin/clis"

type CliTool struct {
	ID            int64  `json:"id"`
	Name          string `json:"name"`
	Description   string `json:"description"`
	Version       string `json:"version"`
	InstallScript string `json:"installScript"`
	CheckCommand  string `json:"checkCommand"`
	Status        int    `json:"status"`
	IsPublic      int    `json:"isPublic"`
	Creator       string `json:"creator"`
	CreateTime    string `json:"createTime"`
	SkillList     []struct {
		SkillID   int64  `json:"skillId"`
		SkillName string `json:"skillName"`
	} `json:"skillList"`
}

func (c CliTool) skillNames() string {
	names := make([]string, len(c.SkillList))
	for i, s := range c.SkillList {
		names[i] = s.SkillName
	}
	return strings.Join(names, ",")
}

var cliCmd = &cobra.Command{
	Use:   "cli",
	Short: "Manage CLI tools (installed into agent sandbox images)",
}

var cliListCmd = &cobra.Command{
	Use:   "list",
	Short: "List CLI tools",
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

		result, err := c.List(context.Background(), cliBasePath+"/page", params)
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

		var clis []CliTool
		if err := pageData.DecodeList(&clis); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Version", "Status", "Skills", "Creator"}
		rows := make([][]string, len(clis))
		for i, t := range clis {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				t.Version,
				output.StatusText(t.Status),
				t.skillNames(),
				t.Creator,
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var cliGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get CLI tool details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), cliBasePath, args[0])
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

		var cli CliTool
		if err := result.DecodeData(&cli); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(cli.ID, 10)},
			{"Name", cli.Name},
			{"Description", cli.Description},
			{"Version", cli.Version},
			{"Install Script", cli.InstallScript},
			{"Check Command", cli.CheckCommand},
			{"Status", output.StatusText(cli.Status)},
			{"Skills", cli.skillNames()},
			{"Creator", cli.Creator},
			{"Created", cli.CreateTime},
		})
	},
}

var cliCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a CLI tool",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		name, _ := cmd.Flags().GetString("name")
		installScript, _ := cmd.Flags().GetString("install-script")
		if name == "" || installScript == "" {
			exitError("--name and --install-script are required")
		}

		body := map[string]any{
			"name":          name,
			"installScript": installScript,
		}
		if v, _ := cmd.Flags().GetString("description"); v != "" {
			body["description"] = v
		}
		if v, _ := cmd.Flags().GetString("version"); v != "" {
			body["version"] = v
		}
		if v, _ := cmd.Flags().GetString("check-command"); v != "" {
			body["checkCommand"] = v
		}
		if v, _ := cmd.Flags().GetString("skill-ids"); v != "" {
			body["skillIds"] = parseIDList(v)
		}
		if cmd.Flags().Changed("public") {
			v, _ := cmd.Flags().GetBool("public")
			if v {
				body["isPublic"] = 1
			} else {
				body["isPublic"] = 0
			}
		}

		_, err = c.Create(context.Background(), cliBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("CLI tool created successfully")
	},
}

var cliUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a CLI tool",
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
		if v, _ := cmd.Flags().GetString("description"); cmd.Flags().Changed("description") {
			body["description"] = v
		}
		if v, _ := cmd.Flags().GetString("version"); cmd.Flags().Changed("version") {
			body["version"] = v
		}
		if v, _ := cmd.Flags().GetString("install-script"); cmd.Flags().Changed("install-script") {
			body["installScript"] = v
		}
		if v, _ := cmd.Flags().GetString("check-command"); cmd.Flags().Changed("check-command") {
			body["checkCommand"] = v
		}
		if v, _ := cmd.Flags().GetString("skill-ids"); cmd.Flags().Changed("skill-ids") {
			body["skillIds"] = parseIDList(v)
		}
		if cmd.Flags().Changed("public") {
			v, _ := cmd.Flags().GetBool("public")
			if v {
				body["isPublic"] = 1
			} else {
				body["isPublic"] = 0
			}
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), cliBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("CLI tool updated successfully")
	},
}

var cliDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a CLI tool",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), cliBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("CLI tool deleted successfully")
	},
}

var cliToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle CLI tool status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		status, _ := cmd.Flags().GetInt("status")
		_, err = c.Toggle(context.Background(), cliBasePath, args[0], &status)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("CLI tool status toggled successfully")
	},
}

func parseIDList(s string) []int64 {
	parts := strings.Split(s, ",")
	ids := make([]int64, 0, len(parts))
	for _, p := range parts {
		if id, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64); err == nil {
			ids = append(ids, id)
		}
	}
	return ids
}

func init() {
	cliListCmd.Flags().String("name", "", "Filter by name")
	cliListCmd.Flags().Int("status", -1, "Filter by status")
	cliListCmd.Flags().Int("page", 1, "Page number")
	cliListCmd.Flags().Int("size", 10, "Page size")

	cliCreateCmd.Flags().String("name", "", "CLI name (required)")
	cliCreateCmd.Flags().String("description", "", "CLI description")
	cliCreateCmd.Flags().String("version", "", "CLI version")
	cliCreateCmd.Flags().String("install-script", "", "Dockerfile RUN fragment that installs this CLI (required)")
	cliCreateCmd.Flags().String("check-command", "", "Command to verify installation")
	cliCreateCmd.Flags().String("skill-ids", "", "Associated skill IDs (comma separated)")
	cliCreateCmd.Flags().Bool("public", false, "Make CLI public")

	cliUpdateCmd.Flags().String("name", "", "CLI name")
	cliUpdateCmd.Flags().String("description", "", "CLI description")
	cliUpdateCmd.Flags().String("version", "", "CLI version")
	cliUpdateCmd.Flags().String("install-script", "", "Dockerfile RUN fragment that installs this CLI")
	cliUpdateCmd.Flags().String("check-command", "", "Command to verify installation")
	cliUpdateCmd.Flags().String("skill-ids", "", "Associated skill IDs (comma separated)")
	cliUpdateCmd.Flags().Bool("public", false, "Make CLI public")

	cliToggleCmd.Flags().Int("status", 1, "Target status (0:disabled, 1:enabled)")

	cliCmd.AddCommand(cliListCmd)
	cliCmd.AddCommand(cliGetCmd)
	cliCmd.AddCommand(cliCreateCmd)
	cliCmd.AddCommand(cliUpdateCmd)
	cliCmd.AddCommand(cliDeleteCmd)
	cliCmd.AddCommand(cliToggleCmd)

	rootCmd.AddCommand(cliCmd)
}
