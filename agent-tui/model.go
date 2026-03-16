package main

import (
	"encoding/json"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

type Mode int

const (
	ModeList  Mode = iota
	ModeInput
)

type Model struct {
	mode      Mode
	verbose   bool // Ctrl+O toggle
	width     int
	height    int
	agentList AgentListModel
	session   SessionModel
	client    *Client
	selected  string
	ready     bool
	status    string
}

func NewModel(port int) Model {
	client := NewClient(port)
	al := NewAgentListModel()
	al.EnsureAgent("manager")
	al.SortedNames()
	al.focused = true

	s := NewSessionModel()
	s.agentName = "manager"

	return Model{
		mode:      ModeList,
		agentList: al,
		session:   s,
		client:    client,
		selected:  "manager",
		status:    "Connecting...",
	}
}

func (m Model) Init() tea.Cmd {
	return tea.Batch(
		m.client.FetchSessionInfo(),
		m.client.FetchHistory(),
	)
}

// isVisibleEvent returns whether an event should be shown in current mode
func (m *Model) isVisibleEvent(eventType string) bool {
	switch eventType {
	// Always visible
	case "CHAT", "TEXT", "ERROR", "SPAWNED", "DESPAWNED":
		return true
	// Tool calls + results always visible (useful for user feedback)
	case "TOOL_CALL", "ACTION_STARTED", "ACTION_COMPLETED", "ACTION_FAILED":
		return true
	// Only in verbose
	case "THOUGHT", "TOOL_RESULT", "RUNTIME_STARTED", "RUNTIME_STOPPED",
		"PAUSED", "RESUMED", "SCHEDULE_TRIGGERED", "OBSERVER_FIRED":
		return m.verbose
	default:
		return m.verbose
	}
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmds []tea.Cmd

	switch msg := msg.(type) {

	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		m.resize()
		if !m.ready {
			m.ready = true
		}
		return m, nil

	case tea.KeyMsg:
		switch msg.Type {
		case tea.KeyCtrlC:
			return m, tea.Quit

		case tea.KeyCtrlO:
			m.verbose = !m.verbose
			if m.verbose {
				m.status = "Verbose mode ON (Ctrl+O to toggle)"
			} else {
				m.status = "Verbose mode OFF"
			}
			// Rebuild conversation with new filter
			m.rebuildConversation()
			return m, nil

		case tea.KeyEscape:
			// ESC = stop agent in any mode
			return m, func() tea.Msg { return StopAgentMsg{} }

		case tea.KeyTab:
			if m.mode == ModeList {
				m.mode = ModeInput
			} else {
				m.mode = ModeList
			}
			m.updateFocus()
			return m, nil
		}

		// Route keys based on mode
		if m.mode == ModeList {
			switch msg.Type {
			case tea.KeyUp:
				m.agentList.MoveUp()
				m.switchAgent(m.agentList.SelectedName())
				return m, nil
			case tea.KeyDown:
				m.agentList.MoveDown()
				m.switchAgent(m.agentList.SelectedName())
				return m, nil
			case tea.KeyEnter, tea.KeyRight:
				m.mode = ModeInput
				m.updateFocus()
				return m, nil
			}
			if msg.String() == "q" {
				return m, tea.Quit
			}
			if msg.String() == "r" {
				return m, m.client.FetchSessionInfo()
			}
		} else {
			var cmd tea.Cmd
			m.session, cmd = m.session.Update(msg)
			cmds = append(cmds, cmd)
			return m, tea.Batch(cmds...)
		}

	case SubmitMsg:
		text := strings.TrimSpace(msg.Text)
		if text == "" {
			return m, nil
		}
		agent := m.selected
		state := m.agentList.EnsureAgent(agent)
		userLine := FormatUserInput(text)
		state.Messages = append(state.Messages, rawMessage{formatted: userLine, eventType: "USER"})
		if agent == m.selected {
			m.session.AddMessage(userLine)
		}

		switch text {
		case "/spawn":
			m.status = "Spawning " + agent + "..."
			return m, m.client.Spawn(agent)
		case "/despawn":
			m.status = "Despawning " + agent + "..."
			return m, m.client.Despawn(agent)
		case "/stop":
			m.status = "Stopping " + agent + "..."
			return m, nil
		default:
			if agent == "manager" {
				return m, m.client.TellManager(text)
			}
			return m, m.client.Tell(agent, text)
		}

	case StopAgentMsg:
		agent := m.selected
		state := m.agentList.EnsureAgent(agent)
		if state.RuntimeRunning {
			m.status = "Stopping " + agent + "..."
			stopLine := metaStyle.Render("  ⏹ Stop requested")
			state.Messages = append(state.Messages, rawMessage{formatted: stopLine, eventType: "USER"})
			m.session.AddMessage(stopLine)
			return m, m.client.Stop(agent)
		}
		return m, nil

	case StopResultMsg:
		if msg.Err != nil {
			m.status = "Stop error: " + msg.Err.Error()
		} else {
			m.status = "Agent stopped"
		}
		return m, nil

	case SessionInfoMsg:
		if msg.Err != nil {
			m.status = "Error: " + msg.Err.Error()
			return m, nil
		}
		for _, a := range msg.Agents {
			state := m.agentList.EnsureAgent(a.Name)
			state.Spawned = a.Spawned
			state.RuntimeRunning = a.RuntimeRunning
			state.HasLaunched = a.HasLaunched
		}
		m.agentList.SortedNames()
		m.status = "Connected"
		return m, func() tea.Msg { return startSSEMsg{} }

	case startSSEMsg:
		return m, nil

	case HistoryMsg:
		if msg.Err != nil {
			return m, nil
		}
		for _, ev := range msg.Events {
			m.handleSSEEvent(ev)
		}
		return m, nil

	case SSEConnectedMsg:
		m.status = "Connected (live)"
		return m, nil

	case SSEErrorMsg:
		m.status = "SSE error: " + msg.Err.Error()
		return m, nil

	case SSEEventMsg:
		m.handleSSEEvent(msg)
		return m, nil

	case TellResultMsg:
		if msg.Err != nil {
			m.status = "Tell error: " + msg.Err.Error()
		}
		return m, nil

	case SpawnResultMsg:
		if msg.Err != nil {
			m.status = "Spawn error: " + msg.Err.Error()
		} else {
			m.status = "Spawn requested"
		}
		return m, nil

	case DespawnResultMsg:
		if msg.Err != nil {
			m.status = "Despawn error: " + msg.Err.Error()
		} else {
			m.status = "Despawn requested"
		}
		return m, nil
	}

	return m, tea.Batch(cmds...)
}

type startSSEMsg struct{}

// rawMessage stores both the formatted string and event type for filtering
type rawMessage struct {
	formatted string
	eventType string
}

func (m *Model) handleSSEEvent(msg SSEEventMsg) {
	var d map[string]interface{}
	json.Unmarshal([]byte(msg.Data), &d)
	agentName, _ := d["agentName"].(string)
	if agentName == "" {
		agentName = "manager"
	}

	state := m.agentList.EnsureAgent(agentName)

	switch msg.Type {
	case "SPAWNED":
		state.Spawned = true
	case "DESPAWNED":
		state.Spawned = false
		state.RuntimeRunning = false
	case "RUNTIME_STARTED":
		state.RuntimeRunning = true
		state.HasLaunched = true
	case "RUNTIME_STOPPED":
		state.RuntimeRunning = false
	}

	formatted := FormatEvent(msg)
	state.Messages = append(state.Messages, rawMessage{formatted: formatted, eventType: msg.Type})

	// Only add to viewport if visible under current filter
	if agentName == m.selected && m.isVisibleEvent(msg.Type) {
		m.session.AddMessage(formatted)
	}

	m.agentList.SortedNames()
}

// rebuildConversation re-filters messages after verbose toggle
func (m *Model) rebuildConversation() {
	state := m.agentList.EnsureAgent(m.selected)
	var visible []string
	for _, msg := range state.Messages {
		rm, ok := msg.(rawMessage)
		if !ok {
			continue
		}
		if rm.eventType == "USER" || m.isVisibleEvent(rm.eventType) {
			visible = append(visible, rm.formatted)
		}
	}
	m.session.SetAgent(m.selected, visible)
}

func (m *Model) switchAgent(name string) {
	if name == "" || name == m.selected {
		return
	}
	m.selected = name
	m.rebuildConversation()
}

func (m *Model) updateFocus() {
	m.agentList.focused = m.mode == ModeList
	m.session.SetFocused(m.mode == ModeInput)
}

func (m *Model) resize() {
	listW := agentListWidth(m.width)
	sessionW := m.width - listW
	m.agentList.SetSize(listW, m.height-2)
	m.session.SetSize(sessionW, m.height-2)
}

func (m Model) View() string {
	if !m.ready {
		return "Loading..."
	}

	left := m.agentList.View()
	right := m.session.View()
	main := lipgloss.JoinHorizontal(lipgloss.Top, left, right)

	verboseIndicator := ""
	if m.verbose {
		verboseIndicator = " [VERBOSE]"
	}
	statusBar := dimStyle.
		Width(m.width).
		Render(" " + m.status + verboseIndicator + "  |  Tab: switch  Esc: stop  Ctrl+O: verbose  q: quit  r: refresh")

	return main + "\n" + statusBar
}
