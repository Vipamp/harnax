package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	configDir       = ".harnax"
	configFile      = "config.yaml"
	credentialsFile = "credentials.json"
	profilesDir     = "profiles"

	// HARNAX_URL / HARNAX_TOKEN carry the admin address and the internal secret into a container
	// that has no home directory to write ~/.harnax into.
	envServerURL = "HARNAX_URL"
	envToken     = "HARNAX_TOKEN"
)

type Config struct {
	CurrentProfile string             `yaml:"currentProfile" json:"currentProfile"`
	DefaultOutput  string             `yaml:"defaultOutput" json:"defaultOutput"`
	Profiles       map[string]Profile `yaml:"profiles" json:"profiles"`
}

type Profile struct {
	ServerURL string `yaml:"serverUrl" json:"serverUrl" mapstructure:"serverUrl"`
}

type Credentials struct {
	// JWT mode (existing)
	AccessToken  string `json:"accessToken,omitempty"`
	RefreshToken string `json:"refreshToken,omitempty"`
	ExpiresAt    int64  `json:"expiresAt,omitempty"`
	TenantID     int64  `json:"tenantId,omitempty"`
	Username     string `json:"username,omitempty"`

	// Internal Secret mode (new)
	Mode           string `json:"mode,omitempty"` // "" or "jwt" = JWT mode; "internal" = internal secret
	InternalSecret string `json:"internalSecret,omitempty"`
	ServerURL      string `json:"serverUrl,omitempty"` // used in internal mode
}

func GetConfigDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("get home dir: %w", err)
	}
	return filepath.Join(home, configDir), nil
}

func EnsureConfigDir() (string, error) {
	dir, err := GetConfigDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", fmt.Errorf("create config dir: %w", err)
	}
	return dir, nil
}

func LoadConfig() (*Config, error) {
	dir, err := GetConfigDir()
	if err != nil {
		return nil, err
	}

	path := filepath.Join(dir, configFile)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return defaultConfig(), nil
		}
		return nil, fmt.Errorf("read config: %w", err)
	}

	// Simple YAML-like parsing: we store as JSON for simplicity
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	return &cfg, nil
}

func SaveConfig(cfg *Config) error {
	dir, err := EnsureConfigDir()
	if err != nil {
		return err
	}

	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal config: %w", err)
	}

	path := filepath.Join(dir, configFile)
	return os.WriteFile(path, data, 0644)
}

func LoadCredentials() (*Credentials, error) {
	dir, err := GetConfigDir()
	if err != nil {
		return nil, err
	}

	path := filepath.Join(dir, credentialsFile)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, fmt.Errorf("not logged in. Please run 'harnax login' first")
		}
		return nil, fmt.Errorf("read credentials: %w", err)
	}

	var creds Credentials
	if err := json.Unmarshal(data, &creds); err != nil {
		return nil, fmt.Errorf("parse credentials: %w", err)
	}
	return &creds, nil
}

func SaveCredentials(creds *Credentials) error {
	dir, err := EnsureConfigDir()
	if err != nil {
		return err
	}

	data, err := json.MarshalIndent(creds, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal credentials: %w", err)
	}

	path := filepath.Join(dir, credentialsFile)
	return os.WriteFile(path, data, 0600)
}

func ClearCredentials() error {
	dir, err := GetConfigDir()
	if err != nil {
		return err
	}

	path := filepath.Join(dir, credentialsFile)
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("remove credentials: %w", err)
	}
	return nil
}

// EnvServerURL is the admin address the platform injected, or "" when the variable is absent.
func EnvServerURL() string { return strings.TrimSpace(os.Getenv(envServerURL)) }

// EnvInternalToken is the internal secret the platform injected, or "" when the variable is absent.
func EnvInternalToken() string { return strings.TrimSpace(os.Getenv(envToken)) }

// EffectiveCredentials answers with the injected environment when a sandbox carries it, and with the
// saved login otherwise. Commands with no profile resolve their client through here, so an agent
// container needs neither a ~/.harnax directory nor a login.
//
// A named profile is a request to talk somewhere other than the deployment this process was started
// in, so it deliberately does not get the injected secret: the alternative is `--profile prod`
// sending the platform's own credentials to whatever prod happens to be while reporting success.
func EffectiveCredentials(profileName string) (*Credentials, error) {
	if profileName == "" {
		if secret := EnvInternalToken(); secret != "" {
			return &Credentials{Mode: "internal", ServerURL: EnvServerURL(), InternalSecret: secret}, nil
		}
		if EnvServerURL() != "" {
			return nil, fmt.Errorf("%s is set but %s is not, so this process points at a platform it cannot authenticate to", envServerURL, envToken)
		}
	}
	return LoadCredentials()
}

func GetServerURL(profileName string) (string, error) {
	if profileName == "" {
		if url := EnvServerURL(); url != "" {
			return url, nil
		}
	}

	cfg, err := LoadConfig()
	if err != nil {
		return "", err
	}

	name := profileName
	if name == "" {
		name = cfg.CurrentProfile
	}
	if name == "" {
		name = "default"
	}

	profile, ok := cfg.Profiles[name]
	if !ok {
		return "", fmt.Errorf("profile '%s' not found. Run 'harnax config set serverUrl <url>' first", name)
	}
	if profile.ServerURL == "" {
		return "", fmt.Errorf("serverUrl not set for profile '%s'. Run 'harnax config set serverUrl <url>'", name)
	}
	return profile.ServerURL, nil
}

func defaultConfig() *Config {
	return &Config{
		CurrentProfile: "default",
		DefaultOutput:  "table",
		Profiles: map[string]Profile{
			"default": {ServerURL: "http://localhost:8080"},
		},
	}
}
