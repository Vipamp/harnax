package cmd

import (
	"encoding/json"
	"testing"

	"github.com/spf13/cobra"
)

// TestToggleStatusOmittingTheFlagFlips pins the difference between `cli toggle 3` and an
// unconditional enable. The flag used to default to 1 and always get sent, so the command named
// "toggle" switched a package on every time it ran — including one the content scan had disabled.
func TestToggleStatusOmittingTheFlagFlips(t *testing.T) {
	cmd := &cobra.Command{}
	cmd.Flags().Int("status", -1, "Target status (0:disabled, 1:enabled); omit to flip the current one")

	if got := toggleStatus(cmd); got != nil {
		t.Fatalf("toggleStatus() = %d, want nil so the client reads the row and flips it", *got)
	}

	if err := cmd.Flags().Set("status", "0"); err != nil {
		t.Fatal(err)
	}
	if got := toggleStatus(cmd); got == nil || *got != 0 {
		t.Fatalf("toggleStatus() = %v, want an explicit 0 to be sent as 0", got)
	}
}

// TestCliToolMatchesTheAdminResponse guards the field names against the DTO admin actually renders.
// A key that drifted is not a compile error here: json.Unmarshal leaves the field zero, the table
// shows an empty Version and Package, and an operator reads that as "the package is broken".
func TestCliToolMatchesTheAdminResponse(t *testing.T) {
	raw := `{
		"id": 7,
		"name": "harnax-cli",
		"description": "Harnax command line",
		"version": "1.4.0",
		"checkCommand": "harnax --version",
		"packageDigest": "0123456789abcdef0123456789abcdef",
		"envParams": [{"envParamName": "HARNAX_TOKEN", "required": true, "secret": true}],
		"status": 1,
		"skill": {"skillId": 12, "skillName": "harnax-cli", "skillDescription": "Drive harnax-cli"}
	}`

	var cli CliTool
	if err := json.Unmarshal([]byte(raw), &cli); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if cli.CheckCommand != "harnax --version" {
		t.Errorf("CheckCommand = %q, want the command the image verification runs", cli.CheckCommand)
	}
	if cli.Version != "1.4.0" {
		t.Errorf("Version = %q, want 1.4.0", cli.Version)
	}
	if cli.Skill.SkillName != "harnax-cli" {
		t.Errorf("Skill.SkillName = %q, want the skill shipped inside the package", cli.Skill.SkillName)
	}
	if got := cli.packageText(); got != "0123456789ab" {
		t.Errorf("packageText() = %q, want the digest shortened to 12 hex digits", got)
	}
	if got := cli.envParamsText(); got != "HARNAX_TOKEN(required,secret)" {
		t.Errorf("envParamsText() = %q, want the flags an operator needs before binding it", got)
	}
}

// TestCliToolRendersAbsentOptionalFields keeps a package without a skill or declarations readable:
// the list prints a dash rather than an empty cell that looks like a failed registration.
func TestCliToolRendersAbsentOptionalFields(t *testing.T) {
	var cli CliTool
	if err := json.Unmarshal([]byte(`{"id": 8, "name": "bare"}`), &cli); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got := cli.skillText(); got != "-" {
		t.Errorf("skillText() = %q, want a dash for a package carrying no skill", got)
	}
	if got := cli.envParamsText(); got != "-" {
		t.Errorf("envParamsText() = %q, want a dash when nothing is declared", got)
	}
}
