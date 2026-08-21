package cmd

import (
	"context"
	"os"
	"strconv"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

const skillBasePath = "/api/admin/skills"
const skillRepoBasePath = "/api/admin/skill-repositories"

type Skill struct {
	ID             int64  `json:"id"`
	Name           string `json:"name"`
	RepositoryID   int64  `json:"repositoryId"`
	RepositoryName string `json:"repositoryName"`
	Description    string `json:"description"`
	SkillMD        string `json:"skillmd"`
	Resources      string `json:"resources"`
	Status         int    `json:"status"`
	IsPublic       int    `json:"isPublic"`
	Creator        string `json:"creator"`
	CreateTime     string `json:"createTime"`
}

type SkillRepository struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	URL         string `json:"url"`
	Branch      string `json:"branch"`
	SourceType  string `json:"sourceType"`
	Description string `json:"description"`
	Status      int    `json:"status"`
	IsPublic    int    `json:"isPublic"`
	Creator     string `json:"creator"`
	CreateTime  string `json:"createTime"`
}

type SyncSkill struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Exists      bool   `json:"exists"`
}

var skillCmd = &cobra.Command{
	Use:   "skill",
	Short: "Manage skills",
}

var skillListCmd = &cobra.Command{
	Use:   "list",
	Short: "List skills",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		params := map[string]string{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			params["name"] = v
		}
		if cmd.Flags().Changed("repository-id") {
			v, _ := cmd.Flags().GetInt64("repository-id")
			params["repositoryId"] = strconv.FormatInt(v, 10)
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			params["status"] = strconv.Itoa(v)
		}
		page, _ := cmd.Flags().GetInt("page")
		size, _ := cmd.Flags().GetInt("size")
		params["pageNum"] = strconv.Itoa(page)
		params["pageSize"] = strconv.Itoa(size)

		result, err := c.List(context.Background(), skillBasePath+"/page", params)
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

		var skills []Skill
		if err := pageData.DecodeList(&skills); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Repository", "Status", "Created"}
		rows := make([][]string, len(skills))
		for i, s := range skills {
			rows[i] = []string{
				strconv.FormatInt(s.ID, 10),
				s.Name,
				s.RepositoryName,
				output.StatusText(s.Status),
				s.CreateTime,
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var skillGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get skill details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), skillBasePath, args[0])
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

		var skill Skill
		if err := result.DecodeData(&skill); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(skill.ID, 10)},
			{"Name", skill.Name},
			{"Repository", skill.RepositoryName},
			{"Description", skill.Description},
			{"Status", output.StatusText(skill.Status)},
			{"Public", output.BoolText(skill.IsPublic == 1)},
			{"Creator", skill.Creator},
			{"Created", skill.CreateTime},
			{"skill.md", skill.SkillMD},
			{"Resources", skill.Resources},
		})
	},
}

func resolveSkillMD(cmd *cobra.Command) string {
	if cmd.Flags().Changed("skillmd-file") {
		path, _ := cmd.Flags().GetString("skillmd-file")
		data, err := os.ReadFile(path)
		if err != nil {
			exitError("Failed to read skillmd file: " + err.Error())
		}
		return string(data)
	}
	v, _ := cmd.Flags().GetString("skillmd")
	return v
}

var skillCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a skill",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		name, _ := cmd.Flags().GetString("name")
		repoID, _ := cmd.Flags().GetInt64("repository-id")
		body := map[string]any{
			"name":         name,
			"repositoryId": repoID,
		}
		if v, _ := cmd.Flags().GetString("description"); v != "" {
			body["description"] = v
		}
		if v := resolveSkillMD(cmd); v != "" {
			body["skillmd"] = v
		}
		if v, _ := cmd.Flags().GetString("resources"); v != "" {
			body["resources"] = v
		}
		if cmd.Flags().Changed("status") {
			v, _ := cmd.Flags().GetInt("status")
			body["status"] = v
		}

		_, err = c.Create(context.Background(), skillBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill created successfully")
	},
}

var skillUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a skill",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("repository-id") {
			body["repositoryId"], _ = cmd.Flags().GetInt64("repository-id")
		}
		if cmd.Flags().Changed("description") {
			body["description"], _ = cmd.Flags().GetString("description")
		}
		if cmd.Flags().Changed("skillmd") || cmd.Flags().Changed("skillmd-file") {
			body["skillmd"] = resolveSkillMD(cmd)
		}
		if cmd.Flags().Changed("resources") {
			body["resources"], _ = cmd.Flags().GetString("resources")
		}
		if cmd.Flags().Changed("status") {
			body["status"], _ = cmd.Flags().GetInt("status")
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), skillBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill updated successfully")
	},
}

var skillToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle skill status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), skillBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill status toggled successfully")
	},
}

var skillDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a skill",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), skillBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill deleted successfully")
	},
}

var skillRepoCmd = &cobra.Command{
	Use:   "skill-repo",
	Short: "Manage skill repositories",
}

var skillRepoListCmd = &cobra.Command{
	Use:   "list",
	Short: "List skill repositories",
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

		result, err := c.List(context.Background(), skillRepoBasePath+"/page", params)
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

		var repos []SkillRepository
		if err := pageData.DecodeList(&repos); err != nil {
			exitError("Failed to decode list: " + err.Error())
		}

		headers := []string{"ID", "Name", "Source", "Branch", "Status", "Created"}
		rows := make([][]string, len(repos))
		for i, r := range repos {
			rows[i] = []string{
				strconv.FormatInt(r.ID, 10),
				r.Name,
				r.SourceType,
				r.Branch,
				output.StatusText(r.Status),
				r.CreateTime,
			}
		}
		output.PrintTable(headers, rows)
		output.PrintPageInfo(pageData.Total, pageData.PageNum, pageData.PageSize)
	},
}

var skillRepoGetCmd = &cobra.Command{
	Use:   "get <id>",
	Short: "Get skill repository details",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), skillRepoBasePath, args[0])
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

		var repo SkillRepository
		if err := result.DecodeData(&repo); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		output.PrintKeyValue([][]string{
			{"ID", strconv.FormatInt(repo.ID, 10)},
			{"Name", repo.Name},
			{"URL", repo.URL},
			{"Branch", repo.Branch},
			{"Source Type", repo.SourceType},
			{"Description", repo.Description},
			{"Status", output.StatusText(repo.Status)},
			{"Public", output.BoolText(repo.IsPublic == 1)},
			{"Creator", repo.Creator},
			{"Created", repo.CreateTime},
		})
	},
}

var skillRepoCreateCmd = &cobra.Command{
	Use:   "create",
	Short: "Create a skill repository",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if v, _ := cmd.Flags().GetString("name"); v != "" {
			body["name"] = v
		}
		if v, _ := cmd.Flags().GetString("url"); v != "" {
			body["url"] = v
		}
		if v, _ := cmd.Flags().GetString("branch"); v != "" {
			body["branch"] = v
		}
		if v, _ := cmd.Flags().GetString("description"); v != "" {
			body["description"] = v
		}
		if cmd.Flags().Changed("status") {
			body["status"], _ = cmd.Flags().GetInt("status")
		}

		_, err = c.Create(context.Background(), skillRepoBasePath, body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill repository created successfully")
	},
}

var skillRepoUpdateCmd = &cobra.Command{
	Use:   "update <id>",
	Short: "Update a skill repository",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		body := map[string]any{}
		if cmd.Flags().Changed("name") {
			body["name"], _ = cmd.Flags().GetString("name")
		}
		if cmd.Flags().Changed("url") {
			body["url"], _ = cmd.Flags().GetString("url")
		}
		if cmd.Flags().Changed("branch") {
			body["branch"], _ = cmd.Flags().GetString("branch")
		}
		if cmd.Flags().Changed("description") {
			body["description"], _ = cmd.Flags().GetString("description")
		}
		if cmd.Flags().Changed("status") {
			body["status"], _ = cmd.Flags().GetInt("status")
		}

		if len(body) == 0 {
			exitError("No fields to update")
		}

		_, err = c.Update(context.Background(), skillRepoBasePath+"/update", args[0], body)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill repository updated successfully")
	},
}

var skillRepoToggleCmd = &cobra.Command{
	Use:   "toggle <id>",
	Short: "Toggle skill repository status",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Toggle(context.Background(), skillRepoBasePath, args[0], nil)
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill repository status toggled successfully")
	},
}

var skillRepoDeleteCmd = &cobra.Command{
	Use:   "delete <id>",
	Short: "Delete a skill repository",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		_, err = c.Delete(context.Background(), skillRepoBasePath, args[0])
		if err != nil {
			exitAPIError(err)
		}

		output.PrintSuccess("Skill repository deleted successfully")
	},
}

var skillRepoActiveCmd = &cobra.Command{
	Use:   "active",
	Short: "List active skill repositories",
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.List(context.Background(), skillRepoBasePath+"/active", nil)
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

		var repos []SkillRepository
		if err := result.DecodeData(&repos); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		headers := []string{"ID", "Name", "Source", "Branch", "Status"}
		rows := make([][]string, len(repos))
		for i, r := range repos {
			rows[i] = []string{
				strconv.FormatInt(r.ID, 10),
				r.Name,
				r.SourceType,
				r.Branch,
				output.StatusText(r.Status),
			}
		}
		output.PrintTable(headers, rows)
	},
}

var skillRepoFetchCmd = &cobra.Command{
	Use:   "fetch <id>",
	Short: "List syncable skills from a remote repository",
	Args:  cobra.ExactArgs(1),
	Run: func(cmd *cobra.Command, args []string) {
		c, err := newAdminClient()
		if err != nil {
			exitError(err.Error())
		}

		result, err := c.Get(context.Background(), skillRepoBasePath+"/fetch", args[0])
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

		var skills []SyncSkill
		if err := result.DecodeData(&skills); err != nil {
			exitError("Failed to decode response: " + err.Error())
		}

		headers := []string{"Name", "Description", "Exists"}
		rows := make([][]string, len(skills))
		for i, s := range skills {
			rows[i] = []string{
				s.Name,
				s.Description,
				output.BoolText(s.Exists),
			}
		}
		output.PrintTable(headers, rows)
	},
}

func init() {
	skillListCmd.Flags().String("name", "", "Filter by skill name")
	skillListCmd.Flags().Int64("repository-id", 0, "Filter by repository ID")
	skillListCmd.Flags().Int("status", -1, "Filter by status")
	skillListCmd.Flags().Int("page", 1, "Page number")
	skillListCmd.Flags().Int("size", 10, "Page size")

	skillCreateCmd.Flags().String("name", "", "Skill name")
	skillCreateCmd.MarkFlagRequired("name")
	skillCreateCmd.Flags().Int64("repository-id", 0, "Repository ID")
	skillCreateCmd.MarkFlagRequired("repository-id")
	skillCreateCmd.Flags().String("description", "", "Skill description")
	skillCreateCmd.Flags().String("skillmd", "", "skill.md content")
	skillCreateCmd.Flags().String("skillmd-file", "", "Read skill.md content from file")
	skillCreateCmd.Flags().String("resources", "", "Resource information")
	skillCreateCmd.Flags().Int("status", 1, "Status (0/1)")

	skillUpdateCmd.Flags().String("name", "", "Skill name")
	skillUpdateCmd.Flags().Int64("repository-id", 0, "Repository ID")
	skillUpdateCmd.Flags().String("description", "", "Skill description")
	skillUpdateCmd.Flags().String("skillmd", "", "skill.md content")
	skillUpdateCmd.Flags().String("skillmd-file", "", "Read skill.md content from file")
	skillUpdateCmd.Flags().String("resources", "", "Resource information")
	skillUpdateCmd.Flags().Int("status", -1, "Status (0/1)")

	skillRepoListCmd.Flags().String("name", "", "Filter by repository name")
	skillRepoListCmd.Flags().Int("status", -1, "Filter by status")
	skillRepoListCmd.Flags().Int("page", 1, "Page number")
	skillRepoListCmd.Flags().Int("size", 10, "Page size")

	skillRepoCreateCmd.Flags().String("name", "", "Repository name")
	skillRepoCreateCmd.Flags().String("url", "", "Repository URL")
	skillRepoCreateCmd.Flags().String("branch", "", "Branch name")
	skillRepoCreateCmd.Flags().String("description", "", "Repository description")
	skillRepoCreateCmd.Flags().Int("status", 1, "Status (0/1)")

	skillRepoUpdateCmd.Flags().String("name", "", "Repository name")
	skillRepoUpdateCmd.Flags().String("url", "", "Repository URL")
	skillRepoUpdateCmd.Flags().String("branch", "", "Branch name")
	skillRepoUpdateCmd.Flags().String("description", "", "Repository description")
	skillRepoUpdateCmd.Flags().Int("status", -1, "Status (0/1)")

	skillCmd.AddCommand(skillListCmd)
	skillCmd.AddCommand(skillGetCmd)
	skillCmd.AddCommand(skillCreateCmd)
	skillCmd.AddCommand(skillUpdateCmd)
	skillCmd.AddCommand(skillToggleCmd)
	skillCmd.AddCommand(skillDeleteCmd)

	skillRepoCmd.AddCommand(skillRepoListCmd)
	skillRepoCmd.AddCommand(skillRepoGetCmd)
	skillRepoCmd.AddCommand(skillRepoCreateCmd)
	skillRepoCmd.AddCommand(skillRepoUpdateCmd)
	skillRepoCmd.AddCommand(skillRepoToggleCmd)
	skillRepoCmd.AddCommand(skillRepoDeleteCmd)
	skillRepoCmd.AddCommand(skillRepoActiveCmd)
	skillRepoCmd.AddCommand(skillRepoFetchCmd)

	rootCmd.AddCommand(skillCmd)
	rootCmd.AddCommand(skillRepoCmd)
}
