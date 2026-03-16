package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
)

// Client handles HTTP API + SSE streaming
type Client struct {
	baseURL string
}

func NewClient(port int) *Client {
	return &Client{baseURL: fmt.Sprintf("http://localhost:%d", port)}
}

// --- HTTP commands (return tea.Cmd) ---

type TellResultMsg struct{ Err error }
type SpawnResultMsg struct{ Err error }
type DespawnResultMsg struct{ Err error }

// SessionInfoMsg carries initial agent list
type SessionInfoMsg struct {
	Agents []struct {
		Name           string `json:"name"`
		Spawned        bool   `json:"spawned"`
		RuntimeRunning bool   `json:"runtimeRunning"`
		HasLaunched    bool   `json:"hasLaunched"`
	}
	Err error
}

// HistoryMsg carries past events
type HistoryMsg struct {
	Events []SSEEventMsg
	Err    error
}

func (c *Client) Tell(agent, message string) tea.Cmd {
	return func() tea.Msg {
		err := c.post(fmt.Sprintf("/agent/%s/tell", agent), map[string]string{"message": message})
		return TellResultMsg{Err: err}
	}
}

func (c *Client) TellManager(message string) tea.Cmd {
	return func() tea.Msg {
		err := c.post("/manager/tell", map[string]string{"message": message})
		return TellResultMsg{Err: err}
	}
}

func (c *Client) Spawn(agent string) tea.Cmd {
	return func() tea.Msg {
		err := c.post(fmt.Sprintf("/agent/%s/spawn", agent), map[string]interface{}{
			"name": agent, "x": 0, "y": 64, "z": 0,
		})
		return SpawnResultMsg{Err: err}
	}
}

type StopResultMsg struct{ Err error }

func (c *Client) Stop(agent string) tea.Cmd {
	return func() tea.Msg {
		err := c.post(fmt.Sprintf("/agent/%s/stop", agent), nil)
		return StopResultMsg{Err: err}
	}
}

func (c *Client) Despawn(agent string) tea.Cmd {
	return func() tea.Msg {
		err := c.post(fmt.Sprintf("/agent/%s/despawn", agent), nil)
		return DespawnResultMsg{Err: err}
	}
}

func (c *Client) FetchSessionInfo() tea.Cmd {
	return func() tea.Msg {
		resp, err := http.Get(c.baseURL + "/session/info")
		if err != nil {
			return SessionInfoMsg{Err: err}
		}
		defer resp.Body.Close()

		var raw struct {
			OK     bool `json:"ok"`
			Agents []struct {
				Name           string `json:"name"`
				Spawned        bool   `json:"spawned"`
				RuntimeRunning bool   `json:"runtimeRunning"`
				HasLaunched    bool   `json:"hasLaunched"`
			} `json:"agents"`
		}
		json.NewDecoder(resp.Body).Decode(&raw)
		return SessionInfoMsg{Agents: raw.Agents}
	}
}

func (c *Client) FetchHistory() tea.Cmd {
	return func() tea.Msg {
		resp, err := http.Get(c.baseURL + "/events/history")
		if err != nil {
			return HistoryMsg{Err: err}
		}
		defer resp.Body.Close()

		var raw struct {
			Events []json.RawMessage `json:"events"`
		}
		json.NewDecoder(resp.Body).Decode(&raw)

		var events []SSEEventMsg
		for _, ev := range raw.Events {
			var parsed map[string]interface{}
			json.Unmarshal(ev, &parsed)
			evType, _ := parsed["type"].(string)
			events = append(events, SSEEventMsg{Type: evType, Data: string(ev)})
		}
		return HistoryMsg{Events: events}
	}
}

// StreamEvents connects to SSE and sends events via p.Send
func (c *Client) StreamEvents(p *tea.Program) {
	go func() {
		resp, err := http.Get(c.baseURL + "/events/stream")
		if err != nil {
			p.Send(SSEErrorMsg{Err: err})
			return
		}
		defer resp.Body.Close()

		p.Send(SSEConnectedMsg{})

		scanner := bufio.NewScanner(resp.Body)
		// Increase buffer for large events
		scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

		var eventType, data string
		for scanner.Scan() {
			line := scanner.Text()
			if line == "" {
				// Empty line = end of event
				if eventType != "" && data != "" {
					p.Send(SSEEventMsg{Type: eventType, Data: data})
				}
				eventType, data = "", ""
			} else if strings.HasPrefix(line, "event: ") {
				eventType = strings.TrimPrefix(line, "event: ")
			} else if strings.HasPrefix(line, "data: ") {
				data = strings.TrimPrefix(line, "data: ")
			}
			// Ignore comments (lines starting with :) like heartbeats
		}

		if err := scanner.Err(); err != nil && err != io.EOF {
			p.Send(SSEErrorMsg{Err: err})
		}
	}()
}

// --- helpers ---

func (c *Client) post(path string, body interface{}) error {
	var buf bytes.Buffer
	if body != nil {
		json.NewEncoder(&buf).Encode(body)
	} else {
		buf.WriteString("{}")
	}
	resp, err := http.Post(c.baseURL+path, "application/json", &buf)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	var result struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}
	json.NewDecoder(resp.Body).Decode(&result)
	if !result.OK && result.Error != "" {
		return fmt.Errorf("%s", result.Error)
	}
	return nil
}
