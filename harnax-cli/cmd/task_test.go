package cmd

import (
	"encoding/json"
	"testing"
)

// TestTaskLogUsesIDField pins the field name the admin API actually sends: the endpoint returns the
// AgentTaskLog entity, whose key is `id`. Reading `logId` silently yields 0, which makes
// `task stop <id>` target the wrong row.
func TestTaskLogUsesIDField(t *testing.T) {
	raw := `{"id":42,"taskName":"Daily News","status":3,"startTime":"2026-09-11 10:00:00"}`

	var log TaskLog
	if err := json.Unmarshal([]byte(raw), &log); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if log.ID != 42 {
		t.Fatalf("ID = %d, want 42", log.ID)
	}
}
