package cmd

import (
	"fmt"
	"os"

	"github.com/agnetix/harnax-cli/internal/client"
	"github.com/agnetix/harnax-cli/internal/config"
	"github.com/agnetix/harnax-cli/internal/output"
	"github.com/spf13/cobra"
)

var (
	version    = "dev"
	serverURL  string
	outputFmt  string
	profile    string
	verbose    bool
)

var rootCmd = &cobra.Command{
	Use:   "harnax",
	Short: "Harnax Admin CLI",
	Long:  "Command-line interface for managing Harnax platform resources.",
	Version: version,
	SilenceUsage:  true,
	SilenceErrors: true,
}

func SetVersion(v string) {
	version = v
	rootCmd.Version = v
}

func Execute() error {
	return rootCmd.Execute()
}

func init() {
	rootCmd.PersistentFlags().StringVar(&serverURL, "server-url", "", "Override admin server URL")
	rootCmd.PersistentFlags().StringVar(&outputFmt, "output", "table", "Output format: table or json")
	rootCmd.PersistentFlags().StringVar(&profile, "profile", "", "Use specified profile")
	rootCmd.PersistentFlags().BoolVar(&verbose, "verbose", false, "Show HTTP request/response details")
}

func getOutputFormat() output.Format {
	if outputFmt == "json" {
		return output.FormatJSON
	}
	return output.FormatTable
}

func newAdminClient() (*client.AdminClient, error) {
	creds, err := config.LoadCredentials()
	if err != nil {
		return nil, err
	}

	var c *client.AdminClient

	if creds.Mode == "internal" {
		// Internal secret mode: serverUrl from credentials, --server-url flag overrides
		url := creds.ServerURL
		if serverURL != "" {
			url = serverURL
		}
		if url == "" {
			return nil, fmt.Errorf("internal mode requires serverUrl in credentials or --server-url flag")
		}
		c = client.NewAdminClientWithSecret(url, creds.InternalSecret)
	} else {
		// JWT mode (existing behavior)
		url := serverURL
		if url == "" {
			url, err = config.GetServerURL(profile)
			if err != nil {
				return nil, err
			}
		}
		c = client.NewAdminClient(url, creds.AccessToken)
	}

	c.Verbose = verbose
	return c, nil
}

func exitError(msg string) {
	output.PrintError(msg)
	os.Exit(1)
}

func exitAPIError(err error) {
	if apiErr, ok := err.(*client.APIError); ok {
		if apiErr.Code == 401 {
			output.PrintError("Authentication expired. Please run 'harnax login' to re-authenticate.")
			os.Exit(2)
		}
		output.PrintError(fmt.Sprintf("%s (code: %d)", apiErr.Message, apiErr.Code))
		os.Exit(1)
	}
	output.PrintError(err.Error())
	os.Exit(1)
}
