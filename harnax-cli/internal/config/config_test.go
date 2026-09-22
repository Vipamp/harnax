package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// emptyHome points HOME at a directory with no ~/.harnax, which is the state of a fresh container.
func emptyHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	return home
}

func TestEffectiveCredentialsUsesInjectedEnvironment(t *testing.T) {
	emptyHome(t)
	t.Setenv(envServerURL, "http://admin:8080")
	t.Setenv(envToken, " internal-secret ")

	creds, err := EffectiveCredentials("")
	if err != nil {
		t.Fatalf("EffectiveCredentials(\"\") error = %v, want the env to be enough on its own", err)
	}
	if creds.Mode != "internal" {
		t.Errorf("Mode = %q, want internal so the secret is sent as the bearer token", creds.Mode)
	}
	if creds.InternalSecret != "internal-secret" {
		t.Errorf("InternalSecret = %q, want the surrounding whitespace trimmed", creds.InternalSecret)
	}
	if creds.ServerURL != "http://admin:8080" {
		t.Errorf("ServerURL = %q, want %q", creds.ServerURL, "http://admin:8080")
	}
}

// TestEffectiveCredentialsIgnoresEnvForANamedProfile pins the two halves agreeing. GetServerURL lets
// a named profile beat HARNAX_URL; if the credentials still came from the environment, `--profile prod`
// would address prod with the platform's own internal secret and report success.
func TestEffectiveCredentialsIgnoresEnvForANamedProfile(t *testing.T) {
	home := emptyHome(t)
	dir := filepath.Join(home, configDir)
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	body := `{"accessToken":"jwt-token","username":"admin"}`
	if err := os.WriteFile(filepath.Join(dir, credentialsFile), []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv(envServerURL, "http://admin:8080")
	t.Setenv(envToken, "internal-secret")

	creds, err := EffectiveCredentials("prod")
	if err != nil {
		t.Fatalf("EffectiveCredentials(\"prod\") error = %v", err)
	}
	if creds.Mode == "internal" {
		t.Fatal("a named profile was handed the injected internal secret")
	}
	if creds.AccessToken != "jwt-token" {
		t.Errorf("AccessToken = %q, want the saved login for the profile the operator named", creds.AccessToken)
	}
}

// TestEffectiveCredentialsNamesTheVariableThatIsMissing: "run harnax login" is impossible advice in a
// container, and that is exactly the state a half-injected environment produces.
func TestEffectiveCredentialsNamesTheVariableThatIsMissing(t *testing.T) {
	emptyHome(t)
	t.Setenv(envServerURL, "http://admin:8080")

	_, err := EffectiveCredentials("")
	if err == nil {
		t.Fatal("EffectiveCredentials(\"\") error = nil, want a URL with no token to fail")
	}
	for _, want := range []string{envServerURL, envToken} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error %q does not name %s", err.Error(), want)
		}
	}
}

func TestEffectiveCredentialsFallsBackToSavedLogin(t *testing.T) {
	home := emptyHome(t)
	dir := filepath.Join(home, configDir)
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	body := `{"accessToken":"jwt-token","username":"admin"}`
	if err := os.WriteFile(filepath.Join(dir, credentialsFile), []byte(body), 0600); err != nil {
		t.Fatal(err)
	}

	creds, err := EffectiveCredentials("")
	if err != nil {
		t.Fatalf("EffectiveCredentials(\"\") error = %v", err)
	}
	if creds.AccessToken != "jwt-token" {
		t.Errorf("AccessToken = %q, want the saved login when no token is injected", creds.AccessToken)
	}
}

func TestEffectiveCredentialsReportsMissingLogin(t *testing.T) {
	emptyHome(t)

	if _, err := EffectiveCredentials(""); err == nil {
		t.Fatal("EffectiveCredentials(\"\") error = nil, want the not-logged-in error with neither env nor file")
	}
}

func TestGetServerURLHonoursEnvOnlyForTheDefaultProfile(t *testing.T) {
	emptyHome(t)
	if err := SaveConfig(&Config{
		CurrentProfile: "default",
		Profiles:       map[string]Profile{"default": {ServerURL: "http://from-config:8080"}, "prod": {ServerURL: "http://from-profile:8080"}},
	}); err != nil {
		t.Fatal(err)
	}
	t.Setenv(envServerURL, "http://from-env:8080")

	got, err := GetServerURL("")
	if err != nil {
		t.Fatalf("GetServerURL(\"\") error = %v", err)
	}
	if got != "http://from-env:8080" {
		t.Errorf("GetServerURL(\"\") = %q, want the injected URL over the stored default", got)
	}

	got, err = GetServerURL("prod")
	if err != nil {
		t.Fatalf("GetServerURL(\"prod\") error = %v", err)
	}
	if got != "http://from-profile:8080" {
		t.Errorf("GetServerURL(\"prod\") = %q, want the named profile to beat the env", got)
	}
}
