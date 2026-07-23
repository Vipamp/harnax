package cmd

import (
	"context"
	"encoding/json"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const channelBasePath = "/api/admin/channels"

type Channel struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Type       string `json:"type"`
	Status     int    `json:"status"`
	CreateTime string `json:"createTime"`
}

var channelCmd = &cobra.Command{
	Use:   "channel",
	Short: "Manage channels",
}

var channelListCmd = &cobra.Command{
	Use:   "list",
	Short: "List channels",
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

		result, err := c.List(context.Background(), channelBasePath+"/page", params)
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

		var items []Channel
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

var channelGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get channel details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), channelBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item Channel
		if err := result.DecodeData(&item); err != nil {
			exitError("decode data: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(item.ID, 10)},
			{"Name", item.Name},
			{"Type", item.Type},
			{"Status", output.StatusText(item.Status)},
			{"Created", item.CreateTime},
		})
	},
}

var channelCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a channel",
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
		if v, _ := cmd.Flags().GetString("config"); v != "" {
			var parsed any
			if err := json.Unmarshal([]byte(v), &parsed); err != nil {
				exitError("invalid --config JSON: " + err.Error())
			}
			body["config"] = parsed
		}

		result, err := c.Create(context.Background(), channelBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Channel created successfully.")
	},
}

var channelUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a channel",
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
		if cmd.Flags().Changed("config") {
			v, _ := cmd.Flags().GetString("config")
			var parsed any
			if err := json.Unmarshal([]byte(v), &parsed); err != nil {
				exitError("invalid --config JSON: " + err.Error())
			}
			body["config"] = parsed
		}

		result, err := c.Update(context.Background(), channelBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Channel updated successfully.")
	},
}

var channelDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a channel",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Delete(context.Background(), channelBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Channel deleted successfully.")
	},
}

var channelToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle channel status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Toggle(context.Background(), channelBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		outFmt := getOutputFormat()
		if outFmt == output.FormatJSON {
			output.PrintJSON(result)
			return
		}
		output.PrintSuccess("Channel status toggled successfully.")
	},
}

func init() {
	channelListCmd.Flags().String("keyword", "", "Filter by keyword")
	channelListCmd.Flags().String("type", "", "Filter by type")
	channelListCmd.Flags().Int("status", 0, "Filter by status")
	channelListCmd.Flags().Int("page", 1, "Page number")
	channelListCmd.Flags().Int("size", 10, "Page size")

	channelCreateCmd.Flags().String("name", "", "Channel name (required)")
	channelCreateCmd.Flags().String("type", "", "Channel type (required)")
	channelCreateCmd.Flags().String("config", "", "Channel config as JSON string")
	channelCreateCmd.MarkFlagRequired("name")
	channelCreateCmd.MarkFlagRequired("type")

	channelUpdateCmd.Flags().String("name", "", "Channel name")
	channelUpdateCmd.Flags().String("config", "", "Channel config as JSON string")

	channelCmd.AddCommand(channelListCmd)
	channelCmd.AddCommand(channelGetCmd)
	channelCmd.AddCommand(channelCreateCmd)
	channelCmd.AddCommand(channelUpdateCmd)
	channelCmd.AddCommand(channelDeleteCmd)
	channelCmd.AddCommand(channelToggleCmd)

	rootCmd.AddCommand(channelCmd)
}
