package main

import "github.com/charmbracelet/lipgloss"

var (
	focusedBorder   = lipgloss.NormalBorder()
	unfocusedBorder = lipgloss.NormalBorder()

	focusedStyle = lipgloss.NewStyle().
			BorderStyle(focusedBorder).
			BorderForeground(lipgloss.Color("10")) // green

	unfocusedStyle = lipgloss.NewStyle().
			BorderStyle(unfocusedBorder).
			BorderForeground(lipgloss.Color("14")) // cyan

	titleStyle = lipgloss.NewStyle().
			Bold(true).
			Foreground(lipgloss.Color("14"))

	thoughtStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("240")) // gray
	toolStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("14"))  // cyan
	errorStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("9"))   // red
	successStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("10"))  // green
	warnStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("11"))  // yellow
	schedStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("13"))  // magenta
	userStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("15")).Bold(true)
	chatStyle    = lipgloss.NewStyle().Foreground(lipgloss.Color("15")) // agent response — white

	statusRunning = lipgloss.NewStyle().Foreground(lipgloss.Color("10")).Bold(true).Render("●")
	statusSpawned = lipgloss.NewStyle().Foreground(lipgloss.Color("12")).Render("●")
	statusIdle    = "○"
	statusOffline = lipgloss.NewStyle().Foreground(lipgloss.Color("240")).Render("·")

	selectedStyle = lipgloss.NewStyle().Background(lipgloss.Color("39")).Foreground(lipgloss.Color("0")).Bold(true)
	dimStyle      = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
)
