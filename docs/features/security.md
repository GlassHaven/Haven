---
layout: default
title: Security & privacy
---

# Security & privacy

Screen lock with biometric or device PIN/password/pattern, configurable timeout (immediate/30s/1m/5m/never), no telemetry or analytics, local storage only. Keyboard security: credential, PIN, token, SSH key, and terminal/desktop compose inputs enforce `IME_FLAG_NO_PERSONALIZED_LEARNING` so keyboard apps do not add secrets to personalized dictionaries, with a warning when the active keyboard has internet access. SSH connections verify server host keys during key exchange before sending user credentials (failing closed on unknown host keys during background auto-reconnect and auto-deploy). Encrypted backup/restore with AES-256-GCM. See the [privacy policy](../privacy-policy.html) and [SECURITY.md](https://github.com/GlassHaven/Haven/blob/main/SECURITY.md) for private vulnerability reporting.

For desktop coding agents, `host/linux-sandbox/` provides a Linux network-namespace jail and root egress proxy that asks Haven (`request_egress_grant`) for user approval (`Allow once`, `Allow session`, or `Deny`) on the phone, verifying HMAC-signed grants before opening outbound connections.

The encrypted backup wire format (PBKDF2/AES-GCM envelope) and a Python recipe for manual decryption are documented in [Backup file format](../backup-format.md).

---

[← All features](../FEATURES.md)
