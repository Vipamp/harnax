package main

import (
	"os"

	"github.com/agnetix/harnax-cli/cmd"
	"github.com/agnetix/harnax-cli/internal/output"
)

var version = "dev"

func main() {
	cmd.SetVersion(version)
	if err := cmd.Execute(); err != nil {
		// rootCmd sets SilenceErrors, so cobra reports nothing itself and this is the only place
		// its errors can surface: a missing required flag, an unknown subcommand or a bad argument
		// count all arrive here. Dropping the message left them failing as a bare exit code 1.
		output.PrintError(err.Error())
		os.Exit(1)
	}
}
