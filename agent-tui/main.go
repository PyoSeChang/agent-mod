package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	tea "github.com/charmbracelet/bubbletea"
)

func findPort() (int, error) {
	searchPaths := []string{
		".agent/bridge-server.json",
		"run/.agent/bridge-server.json",
		"../run/.agent/bridge-server.json",
		"../../run/.agent/bridge-server.json",
	}

	cwd, _ := os.Getwd()
	for _, p := range searchPaths {
		full := filepath.Join(cwd, p)
		data, err := os.ReadFile(full)
		if err != nil {
			continue
		}
		var info struct {
			Port int `json:"port"`
		}
		if err := json.Unmarshal(data, &info); err == nil && info.Port > 0 {
			return info.Port, nil
		}
	}

	return 0, fmt.Errorf("bridge-server.json not found. Use --port <port> or run from the agent-mod directory")
}

func main() {
	portFlag := flag.Int("port", 0, "HTTP bridge port")
	flag.Parse()

	port := *portFlag
	if port == 0 {
		var err error
		port, err = findPort()
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}

	m := NewModel(port)
	p := tea.NewProgram(m, tea.WithAltScreen())

	// Start SSE streaming in background (needs program reference)
	go func() {
		m.client.StreamEvents(p)
	}()

	if _, err := p.Run(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
