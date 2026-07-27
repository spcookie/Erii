package output

import (
	"strings"
	"testing"

	"erii-cli/internal/ui/theme"

	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"
	"github.com/muesli/termenv"
)

func TestRendererUsesAdaptiveVercelPalette(t *testing.T) {
	lipgloss.SetColorProfile(termenv.TrueColor)
	theme.Apply(theme.ModeDark)
	dark := Title("Erii")
	if !strings.Contains(dark, "38;2;237;237;237") {
		t.Fatalf("dark title did not use dark text token: %q", dark)
	}

	theme.Apply(theme.ModeLight)
	light := Title("Erii")
	if !strings.Contains(light, "38;2;23;23;23") {
		t.Fatalf("light title did not use light text token: %q", light)
	}
}

func TestErrorResultKeepsReadableStructure(t *testing.T) {
	got := ErrorResult("Config refresh", "Backend", assertError("offline"))
	for _, want := range []string{"Config refresh", "ERROR", "Backend", "offline"} {
		if !strings.Contains(got, want) {
			t.Fatalf("ErrorResult missing %q: %q", want, got)
		}
	}
}

func TestErrorResultIndentsWrappedMessage(t *testing.T) {
	got := ErrorResult("Plugin refresh", "Connection", assertError(strings.Repeat("x", 90)))
	if !strings.Contains(got, "\n                ") {
		t.Fatalf("wrapped error continuation is not indented: %q", got)
	}
}

func TestIndentedRowWithWidthKeepsLongLabelOnOneLine(t *testing.T) {
	got := ansi.Strip(IndentedRowWithWidth("application.conf", "skipped", 18))
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("long label wrapped unexpectedly: %q", got)
	}
	if !strings.Contains(got, "application.conf  skipped") {
		t.Fatalf("label and value should remain on one line: %q", got)
	}
}

func TestHelpHeaderContainsProjectIdentity(t *testing.T) {
	got := ansi.Strip(HelpHeader())
	for _, want := range []string{
		"https://github.com/spcookie/erii",
		"An AI group chat bot with emotions, memory, and personality.",
		"|_____|_| \\_\\___|___|",
	} {
		if !strings.Contains(got, want) {
			t.Fatalf("HelpHeader missing %q: %q", want, got)
		}
	}
}

type assertError string

func (e assertError) Error() string { return string(e) }
