# Haven Roadmap

## Near-term

- [x] **Paste into terminal** — Paste button on keyboard toolbar and selection toolbar. Clipboard text sent as terminal input; selection cleared on paste.
- [x] **Import existing SSH keys** — Allow importing private keys (PEM/OpenSSH format) from device storage.
- [x] **Background connection notification** — Persistent Android notification while SSH sessions are active, so users know there's an open connection.

## Medium-term

- [ ] **Keyboard toolbar customization** — Smaller keys, more keys per row, user-editable layout, multi-row option (JuiceSSH-style).
- [x] **OSC sequence support** — OSC 8 hyperlinks (clickable links in terminal output), OSC 9/777 notifications (toast foreground, Android notification background), OSC 7 working directory tracking.
- [x] **Bracket paste mode** — Wrap pasted text in `ESC[200~`/`ESC[201~` when DECSET 2004 is enabled, preventing accidental execution of multi-line paste.
- [ ] **SFTP integration with CWD** — Use OSC 7 working directory to open SFTP browser at the current remote path.

## Longer-term

- [ ] **Port forwarding** — Local and remote SSH port forwarding (L/R tunnels).
- [ ] **Agent forwarding** — SSH agent forwarding for key-based authentication to hop hosts.
- [ ] **Snippet/command library** — Save and recall frequently used commands.
- [ ] **Connection groups/folders** — Organize saved connections by project or environment.
- [ ] **Mosh support** — UDP-based mobile shell for unreliable network connections.
