package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const envVarPath = "/api/admin/env-variables"

type EnvVar struct {
	ID         int64  `json:"id"`
	EnvKey     string `json:"envKey"`
	EnvValue   string `json:"envValue"`
	Sensitive  int    `json:"sensitive"`
	Enabled    int    `json:"enabled"`
	CreateTime string `json:"createTime"`
}

var envVarCmd = &cobra.Command{
	Use:   "env-var",
	Short: "Manage environment variables",
}

var envVarListCmd = &cobra.Command{
	Use:   "list",
	Short: "List environment variables",
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

		result, err := c.List(ctx, envVarPath+"/page", params)
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
		var items []EnvVar
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"ID", "Key", "Sensitive", "Enabled", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.EnvKey,
				output.BoolText(item.Sensitive == 1),
				output.BoolText(item.Enabled == 1),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var envVarGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get an environment variable",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Get(ctx, envVarPath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item EnvVar
		if err := result.DecodeData(&item); err != nil {
			exitAPIError(err)
		}
		output.PrintKeyValue([][]string{
			{"ID", fmt.Sprintf("%d", item.ID)},
			{"Key", item.EnvKey},
			{"Value", item.EnvValue},
			{"Sensitive", output.BoolText(item.Sensitive == 1)},
			{"Enabled", output.BoolText(item.Enabled == 1)},
			{"Created", item.CreateTime},
		})
	},
}

var envVarCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create an environment variable",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("key") {
			body["envKey"], _ = cmd.Flags().GetString("key")
		}
		if cmd.Flags().Changed("value") {
			body["envValue"], _ = cmd.Flags().GetString("value")
		}
		if cmd.Flags().Changed("sensitive") {
			body["sensitive"], _ = cmd.Flags().GetBool("sensitive")
		}
		if cmd.Flags().Changed("enabled") {
			body["enabled"], _ = cmd.Flags().GetBool("enabled")
		}

		_, err = c.Create(ctx, envVarPath, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Environment variable created successfully.")
	},
}

var envVarUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update an environment variable",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("key") {
			body["envKey"], _ = cmd.Flags().GetString("key")
		}
		if cmd.Flags().Changed("value") {
			body["envValue"], _ = cmd.Flags().GetString("value")
		}
		if cmd.Flags().Changed("sensitive") {
			body["sensitive"], _ = cmd.Flags().GetBool("sensitive")
		}
		if cmd.Flags().Changed("enabled") {
			body["enabled"], _ = cmd.Flags().GetBool("enabled")
		}

		_, err = c.Update(ctx, envVarPath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Environment variable updated successfully.")
	},
}

var envVarDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete an environment variable",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Delete(ctx, envVarPath, args[0])
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Environment variable deleted successfully.")
	},
}

var envVarToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle an environment variable",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Request(ctx, "PUT", fmt.Sprintf("%s/%s/toggle", envVarPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Environment variable toggled successfully.")
	},
}

func init() {
	envVarListCmd.Flags().String("keyword", "", "Filter by keyword")
	envVarListCmd.Flags().Int("page", 1, "Page number")
	envVarListCmd.Flags().Int("size", 20, "Page size")

	envVarCreateCmd.Flags().String("key", "", "Environment variable key")
	envVarCreateCmd.Flags().String("value", "", "Environment variable value")
	envVarCreateCmd.Flags().Bool("sensitive", false, "Mark as sensitive")
	envVarCreateCmd.Flags().Bool("enabled", true, "Enable the variable")
	envVarCreateCmd.MarkFlagRequired("key")
	envVarCreateCmd.MarkFlagRequired("value")

	envVarUpdateCmd.Flags().String("key", "", "Environment variable key")
	envVarUpdateCmd.Flags().String("value", "", "Environment variable value")
	envVarUpdateCmd.Flags().Bool("sensitive", false, "Mark as sensitive")
	envVarUpdateCmd.Flags().Bool("enabled", false, "Enable the variable")

	envVarCmd.AddCommand(envVarListCmd)
	envVarCmd.AddCommand(envVarGetCmd)
	envVarCmd.AddCommand(envVarCreateCmd)
	envVarCmd.AddCommand(envVarUpdateCmd)
	envVarCmd.AddCommand(envVarDeleteCmd)
	envVarCmd.AddCommand(envVarToggleCmd)

	rootCmd.AddCommand(envVarCmd)
}
