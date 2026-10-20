package ocbridge

import "strings"

// Prompter lets the gateway's login questions reach the user — an SMS or
// e-mail code, a RADIUS challenge, or the password itself when the tunnel
// doesn't store one. Implemented on the Android side (gomobile turns this
// into a Java interface) by a dialog plus a heads-up notification, so a
// prompt raised while Haven is backgrounded, e.g. by a re-login during
// reconnect, still reaches the user.
//
// Prompt is called on a Go goroutine and may block for as long as the user
// takes; the gateway's own auth timeout bounds it. Return "" to cancel.
type Prompter interface {
	Prompt(title string, message string, label string, secret bool) string
}

// ask routes one question to the prompter, or reports that none can answer.
func (a *authClient) ask(in xmlInput, st *authState, secret bool) (string, bool) {
	if a.prompter == nil {
		return "", false
	}
	title := strings.TrimSpace(st.serverTitle)
	if title == "" {
		title = "VPN sign-in"
	}
	msg := strings.Join(strings.Fields(st.serverMessage), " ")
	return a.prompter.Prompt(title, msg, promptName(in), secret), true
}
