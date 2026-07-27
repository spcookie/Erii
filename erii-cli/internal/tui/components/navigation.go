package components

import tea "github.com/charmbracelet/bubbletea"

// ScreenStack is a stack of tea.Model screens managed by a root navigator.
type ScreenStack []tea.Model

// PushScreen appends a screen to the stack, runs its Init, and forwards
// a WindowSizeMsg so the new screen knows dimensions immediately. Commands
// returned while applying the initial size must also be executed.
func PushScreen(stack *ScreenStack, screen tea.Model, width, height int) tea.Cmd {
	*stack = append(*stack, screen)
	initCmd := screen.Init()
	var sizeCmd tea.Cmd
	if width > 0 && height > 0 {
		newTop, cmd := screen.Update(tea.WindowSizeMsg{Width: width, Height: height})
		(*stack)[len(*stack)-1] = newTop
		sizeCmd = cmd
	}
	return tea.Batch(initCmd, sizeCmd)
}

// PopScreen removes the top screen from the stack and forwards WindowSizeMsg
// to the new top. Returns false when only one screen remains (can't pop).
func PopScreen(stack *ScreenStack, width, height int) bool {
	if len(*stack) <= 1 {
		return false
	}
	*stack = (*stack)[:len(*stack)-1]
	if width > 0 && height > 0 {
		top := (*stack)[len(*stack)-1]
		newTop, _ := top.Update(tea.WindowSizeMsg{Width: width, Height: height})
		(*stack)[len(*stack)-1] = newTop
	}
	return true
}
