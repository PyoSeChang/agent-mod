package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss"
)

// SSEEventMsg is a tea.Msg for SSE events
type SSEEventMsg struct {
	Type string
	Data string // raw JSON
}

// SSEErrorMsg signals SSE connection error
type SSEErrorMsg struct{ Err error }

// SSEConnectedMsg signals SSE connected
type SSEConnectedMsg struct{}

// AgentState tracks per-agent status
type AgentState struct {
	Name           string
	Spawned        bool
	RuntimeRunning bool
	HasLaunched    bool
	Messages       []interface{} // rawMessage entries
}

func formatTimestamp(ts int64) string {
	t := time.UnixMilli(ts)
	return t.Format("15:04")
}

func stripMcpPrefix(name string) string {
	if i := strings.LastIndex(name, "__"); i >= 0 {
		return name[i+2:]
	}
	return name
}

// Claude Code-inspired minimal styles
var (
	// Agent response: clean white, 2-space indent
	responseStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("15"))

	// Tool call: dim, indented
	toolCallStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("8"))

	// Thinking: very dim
	thinkingStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("237"))

	// Success: subdued green
	okStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("2"))

	// Error: red but not screaming
	errStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("1"))

	// Meta/status: very dim
	metaStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))

	// User input: bold
	promptStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("15")).Bold(true)

	// Schedule: dim magenta
	scheduleStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("5"))
)

// FormatEvent converts an SSE event into a styled string
func FormatEvent(msg SSEEventMsg) string {
	var d map[string]interface{}
	json.Unmarshal([]byte(msg.Data), &d)
	if d == nil {
		d = map[string]interface{}{}
	}

	data, _ := d["data"].(map[string]interface{})
	if data == nil {
		data = map[string]interface{}{}
	}

	switch msg.Type {
	case "CHAT":
		text, _ := data["text"].(string)
		lines := strings.Split(text, "\n")
		var indented []string
		for _, l := range lines {
			indented = append(indented, "  "+l)
		}
		return "\n" + strings.Join(indented, "\n")

	case "THOUGHT":
		text, _ := data["text"].(string)
		firstLine := strings.SplitN(text, "\n", 2)[0]
		if len(firstLine) > 100 {
			firstLine = firstLine[:97] + "..."
		}
		return thinkingStyle.Render("  ⠿ " + firstLine)

	case "TOOL_CALL":
		name, _ := data["name"].(string)
		name = stripMcpPrefix(name)
		return toolCallStyle.Render("  ⏵ " + name)

	case "TOOL_RESULT":
		name, _ := data["name"].(string)
		name = stripMcpPrefix(name)
		success := data["success"] != false
		if success {
			return metaStyle.Render("  ✓ " + name)
		}
		return errStyle.Render("  ✗ " + name)

	case "TEXT":
		turns := ""
		if v, ok := data["turns"].(float64); ok {
			turns = fmt.Sprintf(" (%d turns)", int(v))
		}
		return "\n" + okStyle.Render("  ✓ Done"+turns) + "\n"

	case "ERROR":
		errMsg, _ := data["message"].(string)
		if errMsg == "" {
			errMsg = "unknown"
		}
		return errStyle.Render("  ✗ " + errMsg)

	case "RUNTIME_STARTED":
		return metaStyle.Render("  ● runtime started")

	case "RUNTIME_STOPPED":
		return metaStyle.Render("  ○ runtime stopped")

	case "SPAWNED":
		return okStyle.Render("  ↑ spawned")

	case "DESPAWNED":
		return metaStyle.Render("  ↓ despawned")

	case "ACTION_STARTED":
		action, _ := data["action"].(string)
		return toolCallStyle.Render("  ⏵ " + action)

	case "ACTION_COMPLETED":
		action, _ := data["action"].(string)
		return metaStyle.Render("  ✓ " + action)

	case "ACTION_FAILED":
		action, _ := data["action"].(string)
		reason, _ := data["error"].(string)
		s := "  ✗ " + action
		if reason != "" {
			s += " — " + reason
		}
		return errStyle.Render(s)

	case "SCHEDULE_TRIGGERED":
		title, _ := data["title"].(string)
		target, _ := data["targetAgent"].(string)
		return scheduleStyle.Render("  ⏰ " + title + " → " + target)

	case "OBSERVER_FIRED":
		evType, _ := data["eventType"].(string)
		return scheduleStyle.Render("  👁 " + evType)

	case "PAUSED":
		return metaStyle.Render("  ⏸ paused")

	case "RESUMED":
		return metaStyle.Render("  ▶ resumed")

	default:
		return metaStyle.Render("  " + msg.Type)
	}
}

// FormatUserInput formats user's sent message — like Claude Code's > prompt
func FormatUserInput(text string) string {
	return "\n" + promptStyle.Render("> "+text) + "\n"
}
