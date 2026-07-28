package output

import (
	"fmt"
	"strings"

	"erii-cli/internal/ui/theme"

	"github.com/charmbracelet/lipgloss"
	"github.com/mattn/go-runewidth"
)

var (
	titleStyle   = lipgloss.NewStyle().Foreground(theme.Text).Bold(true)
	mutedStyle   = lipgloss.NewStyle().Foreground(theme.TextMuted)
	labelStyle   = lipgloss.NewStyle().Foreground(theme.TextMuted).Width(14)
	valueStyle   = lipgloss.NewStyle().Foreground(theme.Text).Bold(true)
	sectionStyle = lipgloss.NewStyle().Foreground(theme.Accent).Bold(true)
	pluginStyle  = lipgloss.NewStyle().Foreground(theme.Accent).Bold(true)
	successStyle = lipgloss.NewStyle().Foreground(theme.Success).Bold(true)
	warningStyle = lipgloss.NewStyle().Foreground(theme.Warning).Bold(true)
	errorStyle   = lipgloss.NewStyle().Foreground(theme.Error).Bold(true)
)

const (
	projectLogo = `  _____ ____  ___ ___
 | ____|  _ \|_ _|_ _|
 |  _| | |_) || | | |
 | |___|  _ < | | | |
 |_____|_| \_\___|___|`
	projectURL         = "https://github.com/spcookie/erii"
	projectDescription = "An AI group chat bot with emotions, memory, and personality."
)

func Title(value string) string   { return titleStyle.Render(value) }
func Muted(value string) string   { return mutedStyle.Render(value) }
func Section(value string) string { return sectionStyle.Render(value) }
func Plugin(value string) string  { return pluginStyle.Render(value) }
func Success(value string) string { return successStyle.Render(value) }
func Warning(value string) string { return warningStyle.Render(value) }
func Error(value string) string   { return errorStyle.Render(value) }

// HelpHeader renders the project identity shown above the root command help.
func HelpHeader() string {
	logoStyle := lipgloss.NewStyle().Foreground(theme.Accent).Bold(true)
	urlStyle := lipgloss.NewStyle().Foreground(theme.Accent)
	descriptionStyle := lipgloss.NewStyle().Foreground(theme.TextBody)
	logoLines := strings.Split(projectLogo, "\n")
	details := []string{
		"",
		"",
		"",
		urlStyle.Render(projectURL),
		descriptionStyle.Render(projectDescription),
	}
	logoWidth := 0
	for _, line := range logoLines {
		logoWidth = max(logoWidth, runewidth.StringWidth(line))
	}

	var header strings.Builder
	for i, line := range logoLines {
		header.WriteString(logoStyle.Render(line))
		if details[i] != "" {
			header.WriteString(strings.Repeat(" ", logoWidth-runewidth.StringWidth(line)+3))
			header.WriteString(details[i])
		}
		header.WriteByte('\n')
	}
	header.WriteByte('\n')
	return header.String()
}

func Row(label, value string) string {
	return "  " + labelStyle.Render(label) + valueStyle.Render(value) + "\n"
}

func WrappedRow(label, value string, valueWidth int) string {
	lines := wrapText(value, valueWidth)
	if len(lines) == 0 {
		return Row(label, "")
	}
	var b strings.Builder
	b.WriteString("  ")
	b.WriteString(labelStyle.Render(label))
	b.WriteString(valueStyle.Render(lines[0]))
	b.WriteString("\n")
	continuation := strings.Repeat(" ", 16)
	for _, line := range lines[1:] {
		b.WriteString(continuation)
		b.WriteString(valueStyle.Render(line))
		b.WriteString("\n")
	}
	return b.String()
}

func IndentedRow(label, value string) string {
	return IndentedRowWithWidth(label, value, 14)
}

func IndentedRowWithWidth(label, value string, labelWidth int) string {
	return "    " + labelStyle.Width(labelWidth).Render(label) + value + "\n"
}

func Status(status string) string {
	label := strings.ToUpper(status)
	marker := "●"
	var rendered string
	switch strings.ToLower(status) {
	case "ok", "success", "completed", "ready":
		rendered = successStyle.Render(marker + " " + label)
	case "error", "failed":
		rendered = errorStyle.Render(marker + " " + label)
	default:
		rendered = warningStyle.Render(marker + " " + label)
	}
	return rendered
}

func ErrorResult(title, scope string, err error) string {
	var b strings.Builder
	b.WriteString(Title(title))
	b.WriteString("  ")
	b.WriteString(Status("error"))
	b.WriteString("\n\n")
	b.WriteString(Section("Error"))
	b.WriteString("\n")
	if scope != "" {
		b.WriteString(Row("Scope", scope))
	}
	if err != nil {
		b.WriteString(WrappedRow("Message", err.Error(), 72))
	}
	return b.String()
}

func wrapText(value string, width int) []string {
	if width <= 0 || runewidth.StringWidth(value) <= width {
		return []string{value}
	}
	var lines []string
	for _, paragraph := range strings.Split(value, "\n") {
		remaining := []rune(paragraph)
		for len(remaining) > 0 {
			lineWidth := 0
			cut := 0
			lastSpace := -1
			for i, r := range remaining {
				nextWidth := lineWidth + runewidth.RuneWidth(r)
				if nextWidth > width {
					break
				}
				lineWidth = nextWidth
				cut = i + 1
				if r == ' ' {
					lastSpace = i
				}
			}
			if cut == len(remaining) {
				lines = append(lines, strings.TrimSpace(string(remaining)))
				break
			}
			if lastSpace > 0 {
				cut = lastSpace
			}
			lines = append(lines, strings.TrimSpace(string(remaining[:cut])))
			remaining = remaining[cut:]
			for len(remaining) > 0 && remaining[0] == ' ' {
				remaining = remaining[1:]
			}
		}
	}
	return lines
}

func HTTPStatus(code int) string {
	if code < 400 {
		return ""
	}
	return Muted(fmt.Sprintf("HTTP %d", code))
}

// Help adds the same restrained hierarchy to Cobra help without changing its
// wording or spacing when ANSI is disabled.
func Help(value string) string {
	lines := strings.Split(value, "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if !strings.HasPrefix(line, " ") && strings.HasSuffix(trimmed, ":") {
			lines[i] = Section(trimmed)
			continue
		}
		if strings.HasPrefix(line, "  ") {
			indent := line[:len(line)-len(strings.TrimLeft(line, " "))]
			body := strings.TrimLeft(line, " ")
			if body == "" {
				continue
			}
			end := strings.IndexAny(body, " \t")
			if end > 0 {
				lines[i] = indent + lipgloss.NewStyle().Foreground(theme.Accent).Render(body[:end]) + body[end:]
			}
		}
	}
	return strings.Join(lines, "\n")
}
