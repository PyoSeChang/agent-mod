package main

import (
	"fmt"
	"sort"
	"strings"

	"github.com/charmbracelet/lipgloss"
)

// AgentListModel is the left panel showing agents
type AgentListModel struct {
	cursor  int
	names   []string
	agents  map[string]*AgentState
	focused bool
	width   int
	height  int
}

func NewAgentListModel() AgentListModel {
	return AgentListModel{
		agents: make(map[string]*AgentState),
	}
}

func (m *AgentListModel) SetSize(w, h int) {
	m.width = w
	m.height = h
}

// SortedNames returns manager first, then alphabetical
func (m *AgentListModel) SortedNames() []string {
	var names []string
	hasManager := false
	for name := range m.agents {
		if name == "manager" {
			hasManager = true
		} else {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	if hasManager {
		names = append([]string{"manager"}, names...)
	}
	m.names = names
	return names
}

func (m *AgentListModel) SelectedName() string {
	if m.cursor >= 0 && m.cursor < len(m.names) {
		return m.names[m.cursor]
	}
	return ""
}

func (m *AgentListModel) MoveUp() {
	if m.cursor > 0 {
		m.cursor--
	}
}

func (m *AgentListModel) MoveDown() {
	if m.cursor < len(m.names)-1 {
		m.cursor++
	}
}

func (m *AgentListModel) EnsureAgent(name string) *AgentState {
	if a, ok := m.agents[name]; ok {
		return a
	}
	a := &AgentState{Name: name}
	m.agents[name] = a
	m.SortedNames()
	return a
}

func (m AgentListModel) View() string {
	names := m.names
	if len(names) == 0 {
		names = m.SortedNames()
	}

	var lines []string
	for i, name := range names {
		state := m.agents[name]
		label := name
		if name == "manager" {
			label = "Manager"
		}

		// Determine indicator character (no pre-styling)
		ind := "·"
		if state != nil {
			if state.RuntimeRunning {
				ind = "●"
			} else if state.Spawned {
				ind = "●"
			} else if state.HasLaunched {
				ind = "○"
			}
		}

		selected := i == m.cursor
		var line string
		if selected {
			// Bright: white bold, indicator colored
			indColor := lipgloss.Color("240")
			if state != nil && state.RuntimeRunning {
				indColor = lipgloss.Color("10") // green
			} else if state != nil && state.Spawned {
				indColor = lipgloss.Color("12") // blue
			}
			styledInd := lipgloss.NewStyle().Foreground(indColor).Bold(true).Render(ind)
			styledLabel := lipgloss.NewStyle().Foreground(lipgloss.Color("15")).Bold(true).Render(label)
			line = fmt.Sprintf(" %s %s", styledInd, styledLabel)
		} else {
			// Dim: everything gray
			dimAll := lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
			line = dimAll.Render(fmt.Sprintf(" %s %s", ind, label))
		}

		lines = append(lines, line)
	}

	content := strings.Join(lines, "\n")

	// Pad to fill height (minus border)
	innerHeight := m.height - 2
	if len(lines) < innerHeight {
		content += strings.Repeat("\n", innerHeight-len(lines))
	}

	style := unfocusedStyle
	if m.focused {
		style = focusedStyle
	}

	return style.
		Width(m.width - 2).
		Height(innerHeight).
		Render(titleStyle.Render(" Agents ") + "\n" + content)
}

func (m *AgentListModel) SetCursorToAgent(name string) {
	for i, n := range m.names {
		if n == name {
			m.cursor = i
			return
		}
	}
}

func agentListWidth(totalWidth int) int {
	w := totalWidth / 5
	if w < 15 {
		w = 15
	}
	if w > 30 {
		w = 30
	}
	return w
}
