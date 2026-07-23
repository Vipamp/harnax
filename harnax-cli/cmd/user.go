package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const userPath = "/api/admin/users"

type User struct {
	ID         int64  `json:"id"`
	Username   string `json:"username"`
	Phone      string `json:"phone"`
	Email      string `json:"email"`
	Status     int    `json:"status"`
	CreateTime string `json:"createTime"`
}

var userCmd = &cobra.Command{
	Use:   "user",
	Short: "Manage users",
}

var userListCmd = &cobra.Command{
	Use:   "list",
	Short: "List users",
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
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("page") {
			v, _ := cmd.Flags().GetInt("page")
			params["pageNum"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("size") {
			v, _ := cmd.Flags().GetInt("size")
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(ctx, userPath+"/page", params)
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
		var items []User
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"ID", "Username", "Phone", "Email", "Status", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.Username,
				item.Phone,
				item.Email,
				output.StatusText(item.Status),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var userGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get a user",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Get(ctx, userPath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item User
		if err := result.DecodeData(&item); err != nil {
			exitAPIError(err)
		}
		output.PrintKeyValue([][]string{
			{"ID", fmt.Sprintf("%d", item.ID)},
			{"Username", item.Username},
			{"Phone", item.Phone},
			{"Email", item.Email},
			{"Status", output.StatusText(item.Status)},
			{"Created", item.CreateTime},
		})
	},
}

var userCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a user",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("username") {
			body["username"], _ = cmd.Flags().GetString("username")
		}
		if cmd.Flags().Changed("password") {
			body["password"], _ = cmd.Flags().GetString("password")
		}
		if cmd.Flags().Changed("phone") {
			body["phone"], _ = cmd.Flags().GetString("phone")
		}
		if cmd.Flags().Changed("email") {
			body["email"], _ = cmd.Flags().GetString("email")
		}

		_, err = c.Create(ctx, userPath, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User created successfully.")
	},
}

var userUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a user",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("username") {
			body["username"], _ = cmd.Flags().GetString("username")
		}
		if cmd.Flags().Changed("phone") {
			body["phone"], _ = cmd.Flags().GetString("phone")
		}
		if cmd.Flags().Changed("email") {
			body["email"], _ = cmd.Flags().GetString("email")
		}

		_, err = c.Update(ctx, userPath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User updated successfully.")
	},
}

var userDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a user",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Delete(ctx, userPath, args[0])
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User deleted successfully.")
	},
}

var userToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle a user",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Toggle(ctx, userPath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User toggled successfully.")
	},
}

func init() {
	userListCmd.Flags().String("keyword", "", "Filter by keyword")
	userListCmd.Flags().Int("status", -1, "Filter by status")
	userListCmd.Flags().Int("page", 1, "Page number")
	userListCmd.Flags().Int("size", 20, "Page size")

	userCreateCmd.Flags().String("username", "", "Username")
	userCreateCmd.Flags().String("password", "", "Password")
	userCreateCmd.Flags().String("phone", "", "Phone number")
	userCreateCmd.Flags().String("email", "", "Email address")
	userCreateCmd.MarkFlagRequired("username")
	userCreateCmd.MarkFlagRequired("password")

	userUpdateCmd.Flags().String("username", "", "Username")
	userUpdateCmd.Flags().String("phone", "", "Phone number")
	userUpdateCmd.Flags().String("email", "", "Email address")

	userCmd.AddCommand(userListCmd)
	userCmd.AddCommand(userGetCmd)
	userCmd.AddCommand(userCreateCmd)
	userCmd.AddCommand(userUpdateCmd)
	userCmd.AddCommand(userDeleteCmd)
	userCmd.AddCommand(userToggleCmd)

	rootCmd.AddCommand(userCmd)
}
