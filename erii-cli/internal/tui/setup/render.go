package setup

import (
	"fmt"
	"strings"

	style "erii-cli/internal/ui/theme"

	"github.com/charmbracelet/huh"
	"github.com/charmbracelet/lipgloss"
)

// ---- Unified step layout ----

func renderStepPage(title, content string) string {
	var b strings.Builder
	b.WriteString(style.Title(title))
	b.WriteString("\n\n")
	b.WriteString(content)
	return b.String()
}

type tabItem struct {
	Label  string
	Active bool
}

func renderFormStep(title string, form *huh.Form, tabs []tabItem) string {
	if form == nil {
		return renderStepPage(title, "")
	}
	var b strings.Builder
	b.WriteString(style.Title(title))
	b.WriteString("\n\n")
	if len(tabs) > 0 {
		b.WriteString(renderTabs(tabs))
		b.WriteString("\n")
	}
	b.WriteString(form.View())
	return b.String()
}

func renderTabs(tabs []tabItem) string {
	parts := make([]string, 0, len(tabs))
	for _, tab := range tabs {
		label := " " + tab.Label + " "
		if tab.Active {
			parts = append(parts, lipgloss.NewStyle().
				Foreground(style.Accent).
				Border(lipgloss.NormalBorder(), false, false, true, false).
				BorderForeground(style.Accent).
				Bold(true).
				Render(label))
		} else {
			parts = append(parts, lipgloss.NewStyle().
				Foreground(style.TextMuted).
				Border(lipgloss.NormalBorder(), false, false, true, false).
				BorderForeground(style.BorderStrong).
				Render(label))
		}
	}
	return lipgloss.JoinHorizontal(lipgloss.Top, parts...)
}

// ---- Timeline ----

func renderTimeline(currentNode int, data *SetupData) string {
	var b strings.Builder

	for i, label := range nodeLabels {
		status := getNodeStatus(i, currentNode)
		icon := nodeIcon(status)
		suffix := nodeSuffix(i, status, data)
		iconStyled := styleNodeIcon(icon, status)
		labelStyled := styleNodeLabel(label, status)
		suffixStyled := styleNodeSuffix(suffix)

		prefix := "  "
		if i > 0 && i < len(nodeLabels)-1 {
			prefix = "  " + style.StepPending.Render("├─") + " "
		} else if i == len(nodeLabels)-1 {
			prefix = "  " + style.StepPending.Render("└─") + " "
		}
		line := fmt.Sprintf("%s%s %s %s", prefix, iconStyled, labelStyled, suffixStyled)
		b.WriteString(line)
		if i < len(nodeLabels)-1 {
			b.WriteString("\n")
		}
	}

	return b.String()
}

type nodeStatus int

const (
	nodeDone nodeStatus = iota
	nodeCurrent
	nodePending
)

func getNodeStatus(idx int, currentNode int) nodeStatus {
	if idx < currentNode {
		return nodeDone
	}
	if idx == currentNode {
		return nodeCurrent
	}
	return nodePending
}

func nodeIcon(status nodeStatus) string {
	switch status {
	case nodeDone:
		return "●"
	case nodeCurrent:
		return "◉"
	default:
		return "○"
	}
}

func nodeSuffix(idx int, status nodeStatus, data *SetupData) string {
	if status != nodeDone {
		return ""
	}
	switch idx {
	case 0:
		if data.SelectedProv < len(data.Providers) {
			return "→ " + data.Providers[data.SelectedProv].Name
		}
	case 1:
		parts := []string{}
		if data.EmbeddingEnabled {
			parts = append(parts, "embedding")
		}
		if data.SearchEnabled {
			parts = append(parts, "search")
		}
		if data.STTEnabled {
			parts = append(parts, "stt")
		}
		if data.VisionEnabled {
			parts = append(parts, "vision")
		}
		if data.BrowserEnabled {
			parts = append(parts, "browser")
		}
		if data.ProxyEnabled {
			parts = append(parts, "proxy")
		}
		if len(parts) > 0 {
			return "→ " + strings.Join(parts, ", ")
		}
		return "→ (skipped)"
	case 2:
		if data.BotWS != "" {
			return "→ " + data.BotWS
		}
	case 3:
		if data.EnableGroups != "" {
			return "→ " + data.EnableGroups
		}
	case 4:
		if data.ServerUsername != "" {
			return "→ " + data.ServerUsername
		}
	}
	return ""
}

func styleNodeIcon(icon string, status nodeStatus) string {
	switch status {
	case nodeDone:
		return style.StepDone.Render(icon)
	case nodeCurrent:
		return style.StepCurrent.Render(icon)
	default:
		return style.StepPending.Render(icon)
	}
}

func styleNodeLabel(label string, status nodeStatus) string {
	switch status {
	case nodeDone:
		return style.StepDone.Render(label)
	case nodeCurrent:
		return style.StepCurrent.Render(label)
	default:
		return style.StepPending.Render(label)
	}
}

func styleNodeSuffix(suffix string) string {
	if suffix == "" {
		return ""
	}
	return lipgloss.NewStyle().Foreground(style.TextMuted).Render(suffix)
}

// ---- Huh theme (matches style.StyleDelegate color scheme) ----

func huhTheme() *huh.Theme {
	return style.HuhTheme()
}
