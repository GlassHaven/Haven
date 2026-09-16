---
layout: default
title: AI chat
---

# AI chat

Chat with models you host yourself or have API access to: llama-server, vLLM, Ollama, CLIProxyAPI, or the Anthropic and Gemini APIs. The endpoint is a connection profile like any other transport, so it rides the same per-profile routing every other connection gets — WireGuard, NetBird, Tailscale, or Cloudflare Access tunnels, a SOCKS4/SOCKS5/HTTP proxy, plus port knocking and SPA — and the transcript is a chat screen, not a terminal.

## Endpoints

An AI profile stores a host, a port, an optional path prefix (for servers mounted under a sub-path, e.g. CLIProxyAPI's `/api`), and an optional API key. Keys are stored encrypted at rest like every other credential. Four wire protocols, selected on the profile:

| Protocol | Wire format | Example servers |
| --- | --- | --- |
| OpenAI | `/v1/chat/completions` + SSE | llama-server, vLLM, CLIProxyAPI |
| Ollama | native `/api/tags` + `/api/chat`, NDJSON | Ollama |
| Anthropic | Messages API `/v1/messages`, SSE | api.anthropic.com |
| Gemini | `generateContent`, `x-goog-api-key` | generativelanguage.googleapis.com |

Connect verifies the endpoint by fetching its model list before the chat opens, and the model picker offers what the server reports. Plain-HTTP LAN endpoints are reachable: entering an `http://` URL is an explicit choice, the way an SSH profile is an explicit choice, and TLS is used whenever the server speaks it.

## Vision

Attach images to a message from the gallery or the camera — up to 4 per message. Staged images are prepared (resized, re-encoded to JPEG or kept as PNG) before send, so a 12 MP photo doesn't become a 12 MB request body, and the model receives them alongside the text. Vision is only as good as the model behind the endpoint; a text-only model will ignore or reject the images.

## Clipboard

Text and images move in and out through the system clipboard, both directions:

- **Long-press a message bubble** for a menu: *Copy text* (any bubble with text) and *Copy image* (any bubble with an image — the image is re-materialized from the message onto the system clipboard, so it can be pasted into any other app).
- **Copy last reply** — a one-tap copy button under the newest finished assistant reply, for pulling the answer out without a long-press.
- **Paste image** — the composer shows a paste button when the clipboard holds an image; tapping stages it like an attachment.

## Saved conversations

A transcript is ephemeral by default — it lives as long as the session and is discarded when you attach to a different profile. The save toggle opts in: the conversation row is created on the spot, everything already in the transcript is back-filled, and every later message (text, model name, and attachments) is persisted to the app database, encrypted at rest the same way as saved credentials. Turning the toggle off deletes the rows.

## Agent access

The MCP transport can drive chat too: `create_connection`/`update_connection` accept `connectionType=OPENAI` with a `protocol` field, and `openai_list_models` / `openai_chat` run completions against a connected profile without the screen. The same consent rules apply as every other tool. See [mcp-tools.md](../mcp-tools.md).

---

[← All features](../FEATURES.md)