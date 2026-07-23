package cmd

import (
	"context"
	"fmt"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const tenantPath = "/api/admin/tenant"

type Tenant struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Status      int    `json:"status"`
	CreateTime  string `json:"createTime"`
}

type TenantUser struct {
	UserID   int64  `json:"userId"`
	Username string `json:"username"`
	Role     string `json:"role"`
}

var tenantCmd = &cobra.Command{
	Use:   "tenant",
	Short: "Manage tenants",
}

var tenantListCmd = &cobra.Command{
	Use:   "list",
	Short: "List tenants",
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

		result, err := c.List(ctx, tenantPath, params)
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
		var items []Tenant
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"ID", "Name", "Status", "Created"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.ID),
				item.Name,
				output.StatusText(item.Status),
				item.CreateTime,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var tenantGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get a tenant",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		result, err := c.Get(ctx, tenantPath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		format := getOutputFormat()
		if format == output.FormatJSON {
			output.PrintJSON(result)
			return
		}

		var item Tenant
		if err := result.DecodeData(&item); err != nil {
			exitAPIError(err)
		}
		output.PrintKeyValue([][]string{
			{"ID", fmt.Sprintf("%d", item.ID)},
			{"Name", item.Name},
			{"Description", item.Description},
			{"Status", output.StatusText(item.Status)},
			{"Created", item.CreateTime},
		})
	},
}

var tenantCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a tenant",
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
		if cmd.Flags().Changed("description") {
			body["description"], _ = cmd.Flags().GetString("description")
		}

		_, err = c.Create(ctx, tenantPath, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Tenant created successfully.")
	},
}

var tenantDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a tenant",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Delete(ctx, tenantPath, args[0])
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Tenant deleted successfully.")
	},
}

var tenantToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle tenant status",
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

		_, err = c.Request(ctx, "PUT", fmt.Sprintf("%s/%s/status", tenantPath, args[0]), params, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("Tenant status updated successfully.")
	},
}

var tenantUsersCmd = &cobra.Command{
	Use:   "users <id>",
	Short: "List tenant users",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		params := map[string]string{}
		if cmd.Flags().Changed("page") {
			v, _ := cmd.Flags().GetInt("page")
			params["pageNum"] = strconv.Itoa(v)
		}
		if cmd.Flags().Changed("size") {
			v, _ := cmd.Flags().GetInt("size")
			params["pageSize"] = strconv.Itoa(v)
		}

		result, err := c.List(ctx, fmt.Sprintf("%s/%s/users", tenantPath, args[0]), params)
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
		var items []TenantUser
		if err := page.DecodeList(&items); err != nil {
			exitAPIError(err)
		}

		headers := []string{"User ID", "Username", "Role"}
		rows := make([][]string, 0, len(items))
		for _, item := range items {
			rows = append(rows, []string{
				fmt.Sprintf("%d", item.UserID),
				item.Username,
				item.Role,
			})
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(page.Total, page.PageNum, page.PageSize)
	},
}

var tenantAddUserCmd = &cobra.Command{
	Use:   "add-user <tenant-id> <user-id>",
	Short: "Add a user to a tenant",
	Args:  cobra.ExactArgs(2),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{
			"userId": args[1],
		}

		_, err = c.Request(ctx, "POST", fmt.Sprintf("%s/%s/users", tenantPath, args[0]), nil, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User added to tenant successfully.")
	},
}

var tenantRemoveUserCmd = &cobra.Command{
	Use:   "remove-user <tenant-id> <user-id>",
	Short: "Remove a user from a tenant",
	Args:  cobra.ExactArgs(2),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		_, err = c.Request(ctx, "DELETE", fmt.Sprintf("%s/%s/users/%s", tenantPath, args[0], args[1]), nil, nil)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User removed from tenant successfully.")
	},
}

var tenantUpdateRoleCmd = &cobra.Command{
	Use:   "update-role <tenant-id> <user-id>",
	Short: "Update a user's role in a tenant",
	Args:  cobra.ExactArgs(2),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}
		ctx := context.Background()

		body := map[string]any{}
		if cmd.Flags().Changed("role") {
			body["role"], _ = cmd.Flags().GetString("role")
		}

		_, err = c.Request(ctx, "PUT", fmt.Sprintf("%s/%s/users/%s/role", tenantPath, args[0], args[1]), nil, body)
		if err != nil {
			exitAPIError(err)
		}
		output.PrintSuccess("User role updated successfully.")
	},
}

func init() {
	tenantListCmd.Flags().String("name", "", "Filter by name")
	tenantListCmd.Flags().Int("status", -1, "Filter by status")
	tenantListCmd.Flags().Int("page", 1, "Page number")
	tenantListCmd.Flags().Int("size", 20, "Page size")

	tenantCreateCmd.Flags().String("name", "", "Tenant name")
	tenantCreateCmd.Flags().String("description", "", "Tenant description")
	tenantCreateCmd.MarkFlagRequired("name")

	tenantToggleCmd.Flags().Int("status", 0, "Status value")
	tenantToggleCmd.MarkFlagRequired("status")

	tenantUsersCmd.Flags().Int("page", 1, "Page number")
	tenantUsersCmd.Flags().Int("size", 20, "Page size")

	tenantUpdateRoleCmd.Flags().String("role", "", "User role")
	tenantUpdateRoleCmd.MarkFlagRequired("role")

	tenantCmd.AddCommand(tenantListCmd)
	tenantCmd.AddCommand(tenantGetCmd)
	tenantCmd.AddCommand(tenantCreateCmd)
	tenantCmd.AddCommand(tenantDeleteCmd)
	tenantCmd.AddCommand(tenantToggleCmd)
	tenantCmd.AddCommand(tenantUsersCmd)
	tenantCmd.AddCommand(tenantAddUserCmd)
	tenantCmd.AddCommand(tenantRemoveUserCmd)
	tenantCmd.AddCommand(tenantUpdateRoleCmd)

	rootCmd.AddCommand(tenantCmd)
}
