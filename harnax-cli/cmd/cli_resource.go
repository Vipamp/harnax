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

type CliEnvParam struct {
	EnvParamName string `json:"envParamName"`
	Description  string `json:"description"`
	Required     bool   `json:"required"`
	Secret       bool   `json:"secret"`
	DefaultValue string `json:"defaultValue"`
}

type CliTool struct {
	ID            int64         `json:"id"`
	Name          string        `json:"name"`
	Description   string        `json:"description"`
	Version       string        `json:"version"`
	CheckCommand  string        `json:"checkCommand"`
	PackageDigest string        `json:"packageDigest"`
	EnvParams     []CliEnvParam `json:"envParams"`
	Status        int           `json:"status"`
	CreateTime    string        `json:"createTime"`
	Skill         struct {
		SkillID          int64  `json:"skillId"`
		SkillName        string `json:"skillName"`
		SkillDescription string `json:"skillDescription"`
	} `json:"skill"`
}

func (c CliTool) skillText() string {
	if c.Skill.SkillName == "" {
		return "-"
	}
	return c.Skill.SkillName
}

func (c CliTool) packageText() string {
	if len(c.PackageDigest) <= 12 {
		return c.PackageDigest
	}
	return c.PackageDigest[:12]
}

func (c CliTool) envParamsText() string {
	if len(c.EnvParams) == 0 {
		return "-"
	}
	parts := make([]string, len(c.EnvParams))
	for i, e := range c.EnvParams {
		var flags []string
		if e.Required {
			flags = append(flags, "required")
		}
		if e.Secret {
			flags = append(flags, "secret")
		}
		s := e.EnvParamName
		if len(flags) > 0 {
			s += "(" + strings.Join(flags, ",") + ")"
		}
		if e.DefaultValue != "" {
			s += "=" + e.DefaultValue
		}
		parts[i] = s
	}
	return strings.Join(parts, "; ")
}

var cliCmd = &cobra.Command{
	Use:   "cli",
	Short: "Inspect CLI plugin packages registered by admin (status is the only writable field)",
}

var cliListCmd = &cobra.Command{
	Use:   "list",
	Short: "List registered CLI packages",
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

		headers := []string{"ID", "Name", "Version", "Status", "Envs", "Skill", "Package"}
		rows := make([][]string, len(clis))
		for i, t := range clis {
			rows[i] = []string{
				strconv.FormatInt(t.ID, 10),
				t.Name,
				t.Version,
				output.StatusText(t.Status),
				strconv.Itoa(len(t.EnvParams)),
				t.skillText(),
				t.packageText(),
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var cliGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get CLI package details",
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
			{"Check Command", cli.CheckCommand},
			{"Package Digest", cli.PackageDigest},
			{"Env Params", cli.envParamsText()},
			{"Status", output.StatusText(cli.Status)},
			{"Skill", cli.skillText()},
			{"Skill Description", cli.Skill.SkillDescription},
			{"Created", cli.CreateTime},
		})
	},
}

// toggleStatus is the status the operator named, or nil when they named none — and nil is what makes
// `cli toggle <id>` flip the current value. A default of 1 here would have the command named
// "toggle" enable a package every time it ran, including one the content scan had put away.
func toggleStatus(cmd *cobra.Command) *int {
	if !cmd.Flags().Changed("status") {
		return nil
	}
	v, _ := cmd.Flags().GetInt("status")
	return &v
}

var cliToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Enable or disable a CLI package",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), cliBasePath, args[0], toggleStatus(cmd))
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("CLI package status toggled successfully")
	},
}

func init() {
	cliListCmd.Flags().String("name", "", "Filter by name")
	cliListCmd.Flags().Int("status", -1, "Filter by status")
	cliListCmd.Flags().Int("page", 1, "Page number")
	cliListCmd.Flags().Int("size", 10, "Page size")

	cliToggleCmd.Flags().Int("status", -1, "Target status (0:disabled, 1:enabled); omit to flip the current one")

	cliCmd.AddCommand(cliListCmd)
	cliCmd.AddCommand(cliGetCmd)
	cliCmd.AddCommand(cliToggleCmd)

	rootCmd.AddCommand(cliCmd)
}
