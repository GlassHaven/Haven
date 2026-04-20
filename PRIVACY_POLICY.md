# Privacy Policy

**Haven — SSH, VNC, RDP & SFTP Client for Android**

Last updated: 2026-03-19

## Data Collection

Haven does not collect, transmit, or share any personal data. All data stays on your device.

## What Haven stores locally

- **Connection profiles**: hostnames, ports, usernames, and optionally RDP/VNC passwords you configure. Stored in a local database on your device.
- **SSH keys**: private keys you generate or import. Stored as BLOBs in Haven's local Room database (`haven.db`) within the app's private storage directory (`/data/data/sh.haven.app/`). This directory is protected by Android's app sandbox — no other app can access it. If the key was encrypted with a passphrase, Haven stores the decrypted version after you unlock it during import, so the passphrase is not needed at connect time. The original passphrase is not stored.
- **Known hosts**: SSH server fingerprints accepted via trust-on-first-use. Stored locally.
- **App preferences**: settings such as theme, font size, color scheme, biometric lock preference, and lock timeout. Stored in local preferences on your device.

## Network Activity

Haven connects only to servers that you explicitly configure:

- **SSH/Mosh/ET**: connections to SSH servers you add as profiles.
- **VNC**: connections to VNC servers you add as profiles, optionally tunnelled through SSH.
- **RDP**: connections to RDP servers you add as profiles, optionally tunnelled through SSH.
- **SFTP**: file transfer over your existing SSH connections.
- **Reticulum**: connections over Reticulum mesh networks when configured.
- **Local network scan** (optional): when you tap "Scan Network" in the connection dialog, Haven probes your local subnet for SSH servers (port 22). This scan is limited to your local network and no data is sent externally.

No other network connections are made. Haven does not contact any analytics, tracking, or advertising services.

## Permissions

- **Internet**: required to connect to your servers (SSH, VNC, RDP, SFTP) and for optional local network scanning.
- **Biometric**: optional, used to lock app access behind fingerprint or face authentication.
- **File access**: used only when importing SSH key files or transferring files via SFTP. Haven does not access files without user action.

## Third-Party Services

Haven does not use any third-party analytics, crash reporting, or advertising SDKs.

## Open Source

Haven is open source software licensed under the GNU General Public License v3.0. The source code is available at https://github.com/GlassHaven/Haven.

If concerned, build it yourself, or ask an AI to build it for you.

## Contact

For questions about this privacy policy, open an issue at https://github.com/GlassHaven/Haven/issues.
