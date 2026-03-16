package main

import (
	"strings"

	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// SessionModel is the right panel — conversation view + input
type SessionModel struct {
	viewport  viewport.Model
	textinput textinput.Model
	messages  []string
	focused   bool
	width     int
	height    int
	agentName string
}

func NewSessionModel() SessionModel {
	ti := textinput.New()
	ti.Placeholder = "Type message or /spawn /despawn /stop"
	ti.CharLimit = 500
	ti.Prompt = "> "

	vp := viewport.New(0, 0)
	vp.Style = lipgloss.NewStyle() // no default styling/scrollbar

	return SessionModel{
		viewport:  vp,
		textinput: ti,
	}
}

func (m *SessionModel) SetSize(w, h int) {
	m.width = w
	m.height = h
	// Layout: title(1) + border(2) + viewport + input border(2) + input(1)
	// viewport height = h - 1(title) - 2(vp border) - 3(input with border)
	vpHeight := h - 6
	if vpHeight < 1 {
		vpHeight = 1
	}
	vpWidth := w - 4
	if vpWidth < 1 {
		vpWidth = 1
	}
	m.viewport.Width = vpWidth
	m.viewport.Height = vpHeight
	m.textinput.Width = vpWidth - 4
}

func (m *SessionModel) SetFocused(focused bool) {
	m.focused = focused
	if focused {
		m.textinput.Focus()
	} else {
		m.textinput.Blur()
	}
}

func (m *SessionModel) SetAgent(name string, messages []string) {
	m.agentName = name
	m.messages = messages
	m.refreshViewport()
}

func (m *SessionModel) AddMessage(msg string) {
	m.messages = append(m.messages, msg)
	m.refreshViewport()
}

func (m *SessionModel) refreshViewport() {
	content := strings.Join(m.messages, "\n")
	if content == "" {
		content = metaStyle.Render("  No events yet")
	}
	// Wrap long lines to viewport width
	if m.viewport.Width > 0 {
		content = wrapText(content, m.viewport.Width)
	}
	m.viewport.SetContent(content)
	m.viewport.GotoBottom()
}

// wrapText wraps lines that exceed maxWidth
func wrapText(text string, maxWidth int) string {
	if maxWidth <= 0 {
		return text
	}
	var result []string
	for _, line := range strings.Split(text, "\n") {
		// Account for ANSI escape codes (don't count them in width)
		visible := stripAnsi(line)
		if len(visible) <= maxWidth {
			result = append(result, line)
			continue
		}
		// Simple wrap: break at maxWidth of visible chars
		// For styled text, just let it wrap naturally
		result = append(result, line)
	}
	return strings.Join(result, "\n")
}

func stripAnsi(s string) string {
	var result []byte
	inEscape := false
	for i := 0; i < len(s); i++ {
		if s[i] == '\033' {
			inEscape = true
			continue
		}
		if inEscape {
			if (s[i] >= 'a' && s[i] <= 'z') || (s[i] >= 'A' && s[i] <= 'Z') {
				inEscape = false
			}
			continue
		}
		result = append(result, s[i])
	}
	return string(result)
}

func (m SessionModel) Update(msg tea.Msg) (SessionModel, tea.Cmd) {
	var cmds []tea.Cmd

	switch msg := msg.(type) {
	case tea.KeyMsg:
		if m.focused {
			switch msg.Type {
			case tea.KeyEnter:
				if msg.Paste {
					// Pasted newline — insert space instead of submitting
					m.textinput.SetValue(m.textinput.Value() + " ")
					return m, nil
				}
				value := m.textinput.Value()
				m.textinput.Reset()
				if value != "" {
					return m, func() tea.Msg {
						return SubmitMsg{Text: value}
					}
				}
				return m, nil
			case tea.KeyEscape:
				m.textinput.Reset()
				return m, func() tea.Msg {
					return StopAgentMsg{}
				}
			}
		}
	}

	if m.focused {
		var cmd tea.Cmd
		m.textinput, cmd = m.textinput.Update(msg)
		cmds = append(cmds, cmd)
	}

	// Only scroll viewport when session is focused (prevents arrow key leaking to agent list)
	if m.focused {
		var cmd tea.Cmd
		m.viewport, cmd = m.viewport.Update(msg)
		cmds = append(cmds, cmd)
	}

	return m, tea.Batch(cmds...)
}

func (m SessionModel) View() string {
	// Title line (outside viewport)
	title := titleStyle.Render(" " + m.agentName + " ")

	// Viewport (conversation area)
	vpContent := unfocusedStyle.
		Width(m.width - 2).
		Height(m.viewport.Height).
		Render(m.viewport.View())

	// Input
	inputBorder := unfocusedStyle
	if m.focused {
		inputBorder = focusedStyle
	}
	inputContent := inputBorder.
		Width(m.width - 2).
		Height(1).
		Render(m.textinput.View())

	return title + "\n" + vpContent + "\n" + inputContent
}

// SubmitMsg is sent when user presses Enter in input
type SubmitMsg struct{ Text string }

// StopAgentMsg is sent when user presses Escape — stops the agent runtime
type StopAgentMsg struct{}
