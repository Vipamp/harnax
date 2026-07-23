package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const taskPath = "/api/admin/agent-tasks"

type Task struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	AgentID    int64  `json:"agentId"`
	TaskStatus int    `json:"taskStatus"`
	CreateTime string `json:"createTime"`
}

type TaskLog struct {
	LogID     int64  `json:"logId"`
	TaskName  string `json:"taskName"`
	Status    int    `json:"status"`
	StartTime string `json:"startTime"`
}

var taskCmd = &cobra.Command{
	Use:   "task",
	Short: "Manage agent tasks",
}

var taskListCmd = &cobra.Command{
	Use:   "list",
	Short: "List agent tasks",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		params := map[string]string{}
		if cmd.Flags().Changed("name") {
			params["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("agent-id") {
			v, _ := cmd.Flags().GetString("agent-id")
			params["agentId"] = v
		}
		if cmd.Flags().Changed("task-status") {
			params["taskStatus"], _ = cmd.Flags().GetString("task-status")
		}
		if cmd.Flags().Changed("page") {
			v, _ := cmd.Flags().GetInt("page")
			params["pageNum"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("size") {
			v, _ := cmd.Flags().GetInt("size")
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(ctx, taskPath+"/page", params)
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
		var items []Task
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"ID", "Name", "Agent ID", "Task Status", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.Name,
				fmt.Sprintf("%d", item.AgentID),
				output.StatusText(item.TaskStatus),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var taskGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Get(ctx, taskPath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item Task
		if err := result.DecodeData(&item); err != nil {
			exitAPIError(err)
		}
		output.PrintKeyValue([][]string{
			{"ID", fmt.Sprintf("%d", item.ID)},
			{"Name", item.Name},
			{"Agent ID", fmt.Sprintf("%d", item.AgentID)},
			{"Task Status", output.StatusText(item.TaskStatus)},
			{"Created", item.CreateTime},
		})
	},
}

var taskCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create an agent task",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("agent-id") {
			v, _ := cmd.Flags().GetInt64("agent-id")
			body["agentId"] = v
		}
		if cmd.Flags().Changed("cron") {
			body["cronExpression"], _ = cmd.Flags().GetString("cron")
		}
		if cmd.Flags().Changed("input") {
			body["input"], _ = cmd.Flags().GetString("input")
		}

		_, err = c.Create(ctx, taskPath, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task created successfully.")
	},
}

var taskUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("cron") {
			body["cronExpression"], _ = cmd.Flags().GetString("cron")
		}
		if cmd.Flags().Changed("input") {
			body["input"], _ = cmd.Flags().GetString("input")
		}

		_, err = c.Update(ctx, taskPath, args[0], body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task updated successfully.")
	},
}

var taskDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Delete(ctx, taskPath, args[0])
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task deleted successfully.")
	},
}

var taskToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle an agent task status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		status, _ := cmd.Flags().GetInt("status")
		params := map[string]string{
			"status": strconv.Itoa(status),
		}

		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/toggle/%s", taskPath, args[0]), params, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task toggled successfully.")
	},
}

var taskStartCmd = &cobra.Command{
	Use:   "start <id>",
	Short: "Start an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/%s/start", taskPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task started successfully.")
	},
}

var taskPauseCmd = &cobra.Command{
	Use:   "pause <id>",
	Short: "Pause an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/%s/pause", taskPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task paused successfully.")
	},
}

var taskTriggerCmd = &cobra.Command{
	Use:   "trigger <id>",
	Short: "Trigger an agent task",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/%s/trigger", taskPath, args[0]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Agent task triggered successfully.")
	},
}

var taskLogsCmd = &cobra.Command{
	Use:   "logs <task-id>",
	Short: "List task execution logs",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		params := map[string]string{}
		if cmd.Flags().Changed("status") {
			params["status"], _ = cmd.Flags().GetString("status")
		}
		if cmd.Flags().Changed("page") {
			v, _ := cmd.Flags().GetInt("page")
			params["pageNum"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("size") {
			v, _ := cmd.Flags().GetInt("size")
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(ctx, fmt.Sprintf("%s/%s/logs", taskPath, args[0]), params)
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
		var items []TaskLog
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"Log ID", "Task Name", "Status", "Start Time"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.LogID),
				item.TaskName,
				output.StatusText(item.Status),
				item.StartTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

func init() {
	taskListCmd.Flags().String("name", "", "Filter by name")
	taskListCmd.Flags().String("agent-id", "", "Filter by agent ID")
	taskListCmd.Flags().String("task-status", "", "Filter by task status")
	taskListCmd.Flags().Int("page", 1, "Page number")
	taskListCmd.Flags().Int("size", 20, "Page size")

	taskCreateCmd.Flags().String("name", "", "Task name")
	taskCreateCmd.Flags().Int64("agent-id", 0, "Agent ID")
	taskCreateCmd.Flags().String("cron", "", "Cron expression")
	taskCreateCmd.Flags().String("input", "", "Task input")
	taskCreateCmd.MarkFlagRequired("name")
	taskCreateCmd.MarkFlagRequired("agent-id")

	taskUpdateCmd.Flags().String("name", "", "Task name")
	taskUpdateCmd.Flags().String("cron", "", "Cron expression")
	taskUpdateCmd.Flags().String("input", "", "Task input")

	taskToggleCmd.Flags().Int("status", 0, "Task status value")
	taskToggleCmd.MarkFlagRequired("status")

	taskLogsCmd.Flags().String("status", "", "Filter by log status")
	taskLogsCmd.Flags().Int("page", 1, "Page number")
	taskLogsCmd.Flags().Int("size", 20, "Page size")

	taskCmd.AddCommand(taskListCmd)
	taskCmd.AddCommand(taskGetCmd)
	taskCmd.AddCommand(taskCreateCmd)
	taskCmd.AddCommand(taskUpdateCmd)
	taskCmd.AddCommand(taskDeleteCmd)
	taskCmd.AddCommand(taskToggleCmd)
	taskCmd.AddCommand(taskStartCmd)
	taskCmd.AddCommand(taskPauseCmd)
	taskCmd.AddCommand(taskTriggerCmd)
	taskCmd.AddCommand(taskLogsCmd)

	rootCmd.AddCommand(taskCmd)
}
