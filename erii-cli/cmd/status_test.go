package cmd

import "testing"

func TestStatusCommandReplacesStats(t *testing.T) {
	commandNames := make(map[string]bool)
	for _, command := range rootCmd.Commands() {
		commandNames[command.Name()] = true
	}

	if !commandNames["status"] {
		t.Fatal("status command is not registered")
	}
	if commandNames["stats"] {
		t.Fatal("legacy stats command is still registered")
	}
}
