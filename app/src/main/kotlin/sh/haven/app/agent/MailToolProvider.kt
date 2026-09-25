package sh.haven.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.app.agent.mailrules.MailWatchManager
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.MailRuleRepository
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.mcp.McpError
import sh.haven.feature.sftp.transport.TransportSelector

/**
 * The email MCP tools (#mcp-backbone Stage 5, Layer E): inbound-email
 * automation (mail rules CRUD + status/poke over MailRuleRepository /
 * MailWatchManager) and the read/write mail verbs — folders, messages,
 * read/search/modify, send, drafts, folder CRUD, attachment save — over
 * MailSessionManager's per-profile IMAP/Proton engines. Attachment save
 * writes to any connected filesystem via TransportSelector. The mail-only
 * helpers travel with it; ctx supplies profileLabel for consent summaries.
 */
internal class MailToolProvider(
    private val ctx: ToolContext,
    private val mailSessionManager: MailSessionManager,
    private val mailRuleRepository: MailRuleRepository,
    private val mailWatchManager: MailWatchManager,
    private val transportSelector: TransportSelector,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val connectionRepository: ConnectionRepository,
    private val agentUiCommandBus: AgentUiCommandBus,
) : ToolProvider {

    /** Hard cap on an attachment save (send or download) — 25 MiB. */
    private val mailAttachmentMaxBytes: Long = 25L * 1024 * 1024

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "list_mail_rules" to ToolHandler(
            description = "List inbound-email automation rules (Mail Rules). Returns each rule's id, name, enabled, orderIndex, accountProfileId (null=any), folderId, criteria, actions, lastFiredAt. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { listMailRules() },

        "create_mail_rule" to ToolHandler(
            description = "Create an inbound-email automation rule: when a message in folderId (default INBOX) of accountProfileId (omit = any connected email account) matches `criteria`, run the ordered `actions`. criteria = {combinator:\"ALL\"|\"ANY\", conditions:[{type, op, value}]} where type is from|to|subject|is_unread|body|has_attachment|attachment_name|attachment_mime|header and op is CONTAINS|EQUALS|REGEX|GLOB. actions = an ordered array of {type, …}: save_attachments{destProfileId,destDir,nameGlob?,mimeGlob?} | run_command{template,background?} | send_to_agent{messageTemplate,targetSessionId?} | notify{titleTemplate,bodyTemplate} | imap_filter{op: MARK_READ|MARK_UNREAD|SET_FLAGGED|UNSET_FLAGGED|MOVE|DELETE, destFolderId?} | forward{to[],template?} | invoke_mcp_tool{toolName,argsTemplateJson}. Templates may use {from} {fromName} {subject} {to} {uid}. Creating + enabling a rule is your standing authorization for its actions (they fire without a per-call prompt); destructive actions (move/delete/forward/run-command, or a non-NEVER MCP tool) are queued for foreground approval when Haven is backgrounded. Turn the master switch on with set_preference mail_automation_enabled=true.",
            inputSchema = objectSchema {
                string("name", "Human label for the rule.", required = true)
                string("accountProfileId", "EMAIL profile id to watch; omit for any connected email account.")
                string("folderId", "Folder to watch (default INBOX).")
                property(
                    "criteria",
                    JSONObject().put("type", "object").put("description", "{combinator, conditions:[…]} — see the tool description."),
                    required = true,
                )
                objectArray(
                    "actions",
                    "Ordered actions — see the tool description.",
                    required = true,
                ) { string("type", "Action kind — see the tool description.", required = true) }
                boolean("enabled", "Default true.")
                integer("orderIndex", "Evaluation order; lower runs first.")
                boolean("stopOnMatch", "Stop evaluating later rules when this one matches.")
                boolean("notifyOnFire", "Raise a notification each time the rule fires.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Create mail rule \"${args.optString("name")}\" (grants standing authorization for its actions)?" },
        ) { args -> createMailRule(args) },

        "delete_mail_rule" to ToolHandler(
            description = "Delete a Mail Rule by id (see list_mail_rules).",
            inputSchema = objectSchema {
                string("id", "Rule id from list_mail_rules.", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Delete mail rule ${args.optString("id")}?" },
        ) { args -> deleteMailRule(args) },

        "get_mail_automation_status" to ToolHandler(
            description = "Mail-Rules automation status: master switch, rule counts, recent firings (the audit log), and destructive actions queued for foreground approval. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { getMailAutomationStatus() },

        "poke_mail_watch" to ToolHandler(
            description = "Force a Mail-Rules poll cycle now instead of waiting for the periodic timer (for testing/immediacy). No-op when the master switch is off. Returns { poked }.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) {
            mailWatchManager.pokeNow()
            JSONObject().put("poked", true)
        },

        "list_mail_folders" to ToolHandler(
            description = "List folders/labels for a connected EMAIL profile (IMAP/Gmail or Proton). Pass profileId (from list_connections). The profile must already be connected (connect_profile first). Returns each folder's id, name, type, and role (inbox/sent/trash/…). Read-only.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listMailFolders(args) },

        "list_mail_folders" to ToolHandler(
            description = "List folders/labels for a connected EMAIL profile (IMAP/Gmail or Proton). Pass profileId (from list_connections). The profile must already be connected (connect_profile first). Returns each folder's id, name, type, and role (inbox/sent/trash/…). Read-only.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listMailFolders(args) },

        "list_mail_messages" to ToolHandler(
            description = "List message envelopes in a folder of a connected EMAIL profile. Pass profileId and folderId (default '0'/INBOX; see list_mail_folders). Returns id, subject, from, unread, time, numAttachments per message, newest first. Page with limit (default 100) + offset (skip from the newest end; offset = page*limit walks older). Read-only.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("folderId", "Folder/label id (default '0'/INBOX).")
                integer("limit", "Max envelopes to return (default 100, 1..500).")
                integer("offset", "Skip this many from the newest end (default 0) — page older with offset = page*limit.")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listMailMessages(args) },

        "read_mail_message" to ToolHandler(
            description = "Fetch one message from a connected EMAIL profile (IMAP/Gmail or Proton; Proton messages are decrypted), returning parsed headers (from, to[], cc[] — cc enables reply-all) and plain-text body (HTML is stripped; remote content is never loaded). Pass profileId and messageId (from list_mail_messages). Each attachment carries an { index, filename, mimeType, sizeBytes, isInline } — pass the index to save_mail_attachment to write its bytes to any connected filesystem. Read-only.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("messageId", "Message id from list_mail_messages.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readMailMessage(args) },

        "send_mail" to ToolHandler(
            description = "Send a plain-text email from a connected EMAIL profile. Pass profileId (from list_connections; connect_profile first), to (array of recipient addresses, at least one), subject, and body (plain text). Optional cc/bcc arrays. Optional attachments: an array of { profileId, path } files on any connected backend (\"local\" or a connected profile id) to attach. To reply in-thread, pass inReplyToMessageId (a messageId from list_mail_messages) — the engine sets In-Reply-To/References from that message so the reply threads (set your own \"Re: …\" subject). Returns { sent, messageId, appendedToSent }. IMAP/SMTP only — Proton send is not yet implemented and returns an error. Side-effectful: prompts for consent on every call and is recorded in the connection log.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                stringArray("to", "Recipient email addresses (at least one).", required = true)
                string("subject", "Subject line.", required = true)
                string("body", "Plain-text message body.", required = true)
                stringArray("cc", "Optional Cc addresses.")
                stringArray("bcc", "Optional Bcc addresses.")
                string("inReplyToMessageId", "Optional: messageId (from list_mail_messages) this is a reply to — threads via In-Reply-To/References.")
                objectArray("attachments", "Optional files to attach, each { profileId, path } on a connected backend.") {
                    string("profileId", "Backend profile id holding the file, or \"local\".", required = true)
                    string("path", "Absolute path of the file to attach on that backend.", required = true)
                }
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("to")?.length() ?: 0
                val na = args.optJSONArray("attachments")?.length() ?: 0
                "Send email to $n recipient(s) — \"${args.optString("subject")}\" — " +
                    "from \"${ctx.profileLabel(args.optString("profileId"))}\"" +
                    (if (na > 0) " with $na attachment(s)" else "") + "?"
            },
        ) { args -> sendMail(args) },

        "save_mail_attachment" to ToolHandler(
            description = "Save one attachment from a message on a connected EMAIL profile to any connected filesystem (local, SFTP, SMB, rclone, Reticulum). Pass profileId + messageId + attachmentIndex (the index from read_mail_message), and the destination as destProfileId (\"local\" or any connected profile id) + destPath (a directory). Optional destFilename overrides the saved name. The file is named after the attachment (sanitised); a collision gets \" (1)\", \" (2)\", … Returns { saved, destProfileId, backend, destPath, filename, bytes }. Works for both IMAP and Proton. Writes a file — prompts for consent on every call.",
            inputSchema = objectSchema {
                string("profileId", "Source EMAIL connection profile id.", required = true)
                string("messageId", "Message id from list_mail_messages.", required = true)
                integer("attachmentIndex", "Attachment index from read_mail_message.", required = true)
                string("destProfileId", "Destination backend profile id, or \"local\" for the device filesystem.", required = true)
                string("destPath", "Destination directory on the chosen backend.", required = true)
                string("destFilename", "Optional name to save as (defaults to the attachment's own filename).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Save attachment #${args.optInt("attachmentIndex")} from \"${ctx.profileLabel(args.optString("profileId"))}\" " +
                    "to ${args.optString("destPath")} on \"${ctx.profileLabel(args.optString("destProfileId"))}\"?"
            },
        ) { args -> saveMailAttachment(args) },

        "modify_mail_message" to ToolHandler(
            description = "Mutate one message on a connected EMAIL profile: mark read/unread, flag/unflag (star), move to another folder, copy/apply-a-label, or delete. Pass profileId + messageId (from list_mail_messages) + op (mark_read | mark_unread | flag | unflag | move | copy | delete). op=move and op=copy also require destFolderId (a folder id from list_mail_folders). IMAP/Gmail only — the Proton engine returns 501. On Gmail: move relabels (removes the source label, adds dest); copy is additive — it applies the dest label and KEEPS the message in its current folders (use copy to label without archiving from Inbox); delete moves to Trash. Returns { ok, op, messageId }. Side-effectful — prompts for consent on every call.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("messageId", "Message id from list_mail_messages.", required = true)
                string("op", "mark_read | mark_unread | flag | unflag | move | copy | delete", required = true)
                string("destFolderId", "Destination folder/label id (from list_mail_folders) — required when op=move or op=copy.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val op = args.optString("op")
                val dest = args.optString("destFolderId").ifBlank { null }
                "Mail: $op message on \"${ctx.profileLabel(args.optString("profileId"))}\"" +
                    (if ((op == "move" || op == "copy") && dest != null) " → $dest" else "") + "?"
            },
        ) { args -> modifyMailMessage(args) },

        "search_mail" to ToolHandler(
            description = "Server-side search of a folder on a connected EMAIL profile. Pass profileId, optional folderId (default INBOX; see list_mail_folders), and one or more criteria: from, to, subject, body (substring matches), unreadOnly (bool), sinceEpochSec / beforeEpochSec (Unix seconds, day granularity). Criteria are ANDed; at least one is required. Optional limit (default 100, 1..500). Returns the same envelope shape as list_mail_messages (newest first) — feed ids into read_mail_message / modify_mail_message. IMAP/Gmail only — Proton returns 501. Read-only.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("folderId", "Folder/label id to search (default INBOX).")
                string("from", "Match sender address/name (substring).")
                string("to", "Match a To recipient (substring).")
                string("subject", "Match subject (substring).")
                string("body", "Match body text (substring).")
                boolean("unreadOnly", "Only unread (no \\Seen) messages.")
                integer("sinceEpochSec", "On/after this Unix-seconds date (day granularity).")
                integer("beforeEpochSec", "On/before this Unix-seconds date (day granularity).")
                integer("limit", "Max results (default 100, 1..500).")
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> searchMail(args) },

        "save_mail_draft" to ToolHandler(
            description = "Save a draft (NOT sent) to the account's Drafts folder on a connected EMAIL profile — use to compose a message for the user to review/send later. Same fields as send_mail (to/cc/bcc/subject/body, optional attachments, optional inReplyToMessageId to thread) but all are optional — a draft may be incomplete. Returns { saved, draftFolderId }. IMAP/Gmail only — Proton returns 501. Writes to the mailbox — prompts for consent on every call.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                stringArray("to", "Recipient addresses (optional for a draft).")
                string("subject", "Subject line.")
                string("body", "Plain-text body.")
                stringArray("cc", "Optional Cc addresses.")
                stringArray("bcc", "Optional Bcc addresses.")
                string("inReplyToMessageId", "Optional: messageId this draft replies to — threads via In-Reply-To/References.")
                objectArray("attachments", "Optional files to attach, each { profileId, path }.") {
                    string("profileId", "Backend profile id holding the file, or \"local\".", required = true)
                    string("path", "Absolute path of the file on that backend.", required = true)
                }
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Save a draft to \"${ctx.profileLabel(args.optString("profileId"))}\" — \"${args.optString("subject")}\"?" },
        ) { args -> saveMailDraft(args) },

        "create_mail_folder" to ToolHandler(
            description = "Create a new folder/label on a connected EMAIL profile (IMAP CREATE; on Gmail this is a new label). Pass profileId + name (use the server's hierarchy separator for nesting, e.g. \"Work/2026\"). Returns { created, folderId } — use folderId as a destination for modify_mail_message move/copy. Fails if it already exists. IMAP/Gmail only — Proton returns 501. Changes the mailbox — prompts for consent on every call.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("name", "New folder/label name (e.g. \"Receipts\" or \"Work/2026\").", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Create mail folder/label \"${args.optString("name")}\" on \"${ctx.profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> createMailFolder(args) },

        "delete_mail_folder" to ToolHandler(
            description = "Delete a folder/label on a connected EMAIL profile (IMAP DELETE). Pass profileId + folderId (from list_mail_folders). On Gmail this removes the LABEL — messages survive in All Mail; on a plain IMAP server it deletes the mailbox AND its messages (destructive). System folders (Inbox/Sent/Drafts/Trash/Spam/All Mail/…) are refused. Returns { deleted, folderId }. IMAP/Gmail only — Proton returns 501. Destructive — prompts for consent on every call.",
            inputSchema = objectSchema {
                string("profileId", "EMAIL connection profile id.", required = true)
                string("folderId", "Folder/label id to delete (from list_mail_folders). System folders are refused.", required = true)
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "DELETE mail folder/label \"${args.optString("folderId")}\" on \"${ctx.profileLabel(args.optString("profileId"))}\"? (On Gmail removes the label; on other IMAP deletes the folder + its messages.)" },
        ) { args -> deleteMailFolder(args) },
    )

    private fun JSONArray?.toTrimmedStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i, "").trim().ifBlank { null } }
    }

    private fun requireMailSession(profileId: String): String =
        mailSessionManager.getSessionIdForProfile(profileId)
            ?: throw McpError(-32603, "Profile $profileId is not a connected email account — call connect_profile first.")

    /** The engine client backing [sessionId] (Proton or IMAP), routed by the session manager. */
    private fun mailClientFor(sessionId: String): sh.haven.core.mail.MailClient =
        mailSessionManager.clientForSession(sessionId)
            ?: throw McpError(-32603, "Session $sessionId has no registered mail engine.")

    private suspend fun listMailRules(): JSONObject {
        val rules = mailRuleRepository.observeRules().first()
        return JSONObject().apply {
            put("count", rules.size)
            put("rules", JSONArray().apply {
                rules.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("name", r.name)
                        put("enabled", r.enabled)
                        put("orderIndex", r.orderIndex)
                        put("accountProfileId", r.accountProfileId ?: JSONObject.NULL)
                        put("folderId", r.folderId)
                        put("stopOnMatch", r.stopOnMatch)
                        put("notifyOnFire", r.notifyOnFire)
                        put("criteria", JSONObject(r.criteriaJson))
                        put("actions", JSONArray(r.actionsJson))
                        put("lastFiredAt", r.lastFiredAt ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    private suspend fun createMailRule(args: JSONObject): JSONObject {
        val name = args.optString("name").ifBlank { throw McpError(-32602, "Missing required argument: name") }
        val criteria = args.optJSONObject("criteria") ?: throw McpError(-32602, "Missing required argument: criteria")
        val actions = args.optJSONArray("actions") ?: throw McpError(-32602, "Missing required argument: actions")
        // Validate by round-tripping through the parser; reject empty/unrecognised input.
        val parsedCriteria = runCatching { sh.haven.core.data.mailrule.MailRuleJson.criteriaFromJson(criteria.toString()) }
            .getOrElse { throw McpError(-32602, "Invalid criteria: ${it.message}") }
        if (parsedCriteria.conditions.isEmpty()) throw McpError(-32602, "criteria has no recognised conditions")
        val parsedActions = runCatching { sh.haven.core.data.mailrule.MailRuleJson.actionsFromJson(actions.toString()) }
            .getOrElse { throw McpError(-32602, "Invalid actions: ${it.message}") }
        if (parsedActions.isEmpty()) throw McpError(-32602, "actions is empty or has no recognised entries")

        val rule = sh.haven.core.data.db.entities.MailRule(
            name = name,
            enabled = args.optBoolean("enabled", true),
            orderIndex = args.optInt("orderIndex", 0),
            accountProfileId = args.optString("accountProfileId").ifBlank { null },
            folderId = args.optString("folderId").ifBlank { "INBOX" },
            criteriaJson = criteria.toString(),
            actionsJson = actions.toString(),
            stopOnMatch = args.optBoolean("stopOnMatch", false),
            notifyOnFire = args.optBoolean("notifyOnFire", false),
        )
        mailRuleRepository.saveRule(rule)
        return JSONObject().apply {
            put("created", true)
            put("id", rule.id)
            put("conditions", parsedCriteria.conditions.size)
            put("actions", parsedActions.size)
            put("masterEnabled", preferencesRepository.mailAutomationEnabled.first())
        }
    }

    private suspend fun deleteMailRule(args: JSONObject): JSONObject {
        val id = args.optString("id").ifBlank { throw McpError(-32602, "Missing required argument: id") }
        val existed = mailRuleRepository.getRule(id) != null
        mailRuleRepository.deleteRule(id)
        return JSONObject().put("deleted", existed).put("id", id)
    }

    private suspend fun getMailAutomationStatus(): JSONObject {
        val rules = mailRuleRepository.observeRules().first()
        val firings = mailRuleRepository.recentFirings(50)
        val pending = mailRuleRepository.pendingActions()
        return JSONObject().apply {
            put("masterEnabled", preferencesRepository.mailAutomationEnabled.first())
            put("ruleCount", rules.size)
            put("enabledRuleCount", rules.count { it.enabled })
            put("recentFirings", JSONArray().apply {
                firings.forEach { f ->
                    put(JSONObject().apply {
                        put("ruleId", f.ruleId ?: JSONObject.NULL)
                        put("kind", f.kind)
                        put("profileId", f.profileId)
                        put("folderId", f.folderId)
                        put("uid", f.uid)
                        put("subject", f.messageSubject ?: JSONObject.NULL)
                        put("firedAt", f.firedAt)
                        put("outcome", f.outcomeSummary ?: JSONObject.NULL)
                    })
                }
            })
            put("pendingActions", JSONArray().apply {
                pending.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("ruleId", p.ruleId)
                        put("subject", p.messageSubject ?: JSONObject.NULL)
                        put("queuedAt", p.queuedAt)
                    })
                }
            })
        }
    }

    private suspend fun listMailFolders(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val folders = mailClientFor(sessionId).listFolders(sessionId)
            JSONObject().apply {
                put("count", folders.size)
                put("folders", JSONArray().apply {
                    folders.forEach { f ->
                        put(JSONObject().apply {
                            put("id", f.id)
                            put("name", f.name)
                            put("type", f.type)
                            // Special-use class so the agent can pick Inbox/Sent/Trash/etc.
                            put("role", f.role.name.lowercase())
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list mail folders: ${e.message}")
        }
    }

    private suspend fun listMailMessages(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifEmpty { sh.haven.core.mail.MailFolder.INBOX_ID }
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val sessionId = requireMailSession(profileId)
        return try {
            val msgs = mailClientFor(sessionId).listMessages(sessionId, folderId, desc = true, limit = limit, offset = offset)
            JSONObject().apply {
                put("folderId", folderId)
                put("offset", offset)
                put("count", msgs.size)
                put("messages", mailEnvelopes(msgs))
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list mail messages: ${e.message}")
        }
    }

    private fun mailEnvelopes(msgs: List<sh.haven.core.mail.MailMessage>): JSONArray =
        JSONArray().apply {
            msgs.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("subject", m.subject)
                    put("from", m.from?.display() ?: "")
                    put("unread", m.unread)
                    put("time", m.timeSeconds)
                    put("numAttachments", m.numAttachments)
                })
            }
        }

    private suspend fun searchMail(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifEmpty { "INBOX" }
        val criteria = sh.haven.core.mail.MailSearchCriteria(
            from = args.optString("from").ifBlank { null },
            to = args.optString("to").ifBlank { null },
            subject = args.optString("subject").ifBlank { null },
            bodyText = args.optString("body").ifBlank { null },
            unreadOnly = args.optBoolean("unreadOnly", false),
            sinceEpochSec = if (args.has("sinceEpochSec")) args.optLong("sinceEpochSec") else null,
            beforeEpochSec = if (args.has("beforeEpochSec")) args.optLong("beforeEpochSec") else null,
        )
        if (criteria.isEmpty) {
            throw McpError(-32602, "Provide at least one search criterion (from/to/subject/body/unreadOnly/sinceEpochSec/beforeEpochSec)")
        }
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val sessionId = requireMailSession(profileId)
        return try {
            val msgs = mailClientFor(sessionId).search(sessionId, folderId, criteria, limit)
            JSONObject().apply {
                put("folderId", folderId)
                put("count", msgs.size)
                put("messages", mailEnvelopes(msgs))
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to search mail: ${e.message}")
        }
    }

    private suspend fun saveMailDraft(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val to = args.optJSONArray("to").toTrimmedStringList()
        val cc = args.optJSONArray("cc").toTrimmedStringList()
        val bcc = args.optJSONArray("bcc").toTrimmedStringList()
        val subject = args.optString("subject")
        val body = args.optString("body")
        val inReplyToMessageId = args.optString("inReplyToMessageId").ifBlank { null }
        val sessionId = requireMailSession(profileId)
        val attachments = resolveSendAttachments(args.optJSONArray("attachments"))
        val mail = sh.haven.core.mail.OutgoingMail(
            to = to, cc = cc, bcc = bcc, subject = subject, bodyText = body,
            attachments = attachments, inReplyToMessageId = inReplyToMessageId,
        )
        return try {
            val draftFolderId = mailClientFor(sessionId).saveDraft(sessionId, mail)
            JSONObject().put("saved", true).put("draftFolderId", draftFolderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to save draft: ${e.message}")
        }
    }

    private suspend fun createMailFolder(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val name = args.optString("name").ifBlank {
            throw McpError(-32602, "Missing required argument: name")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val folderId = mailClientFor(sessionId).createFolder(sessionId, name)
            JSONObject().put("created", true).put("folderId", folderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to create mail folder: ${e.message}")
        }
    }

    private suspend fun deleteMailFolder(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifBlank {
            throw McpError(-32602, "Missing required argument: folderId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            mailClientFor(sessionId).deleteFolder(sessionId, folderId)
            JSONObject().put("deleted", true).put("folderId", folderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to delete mail folder: ${e.message}")
        }
    }

    private suspend fun readMailMessage(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val raw = mailClientFor(sessionId).getMessageRaw(sessionId, messageId)
            val parsed = sh.haven.feature.mail.MimeParser.parse(raw)
            JSONObject().apply {
                put("subject", parsed.subject)
                put("from", parsed.from)
                put("to", JSONArray().apply { parsed.to.forEach { put(it) } })
                put("cc", JSONArray().apply { parsed.cc.forEach { put(it) } })
                parsed.dateMillis?.let { put("dateMillis", it) }
                put("bodyWasHtml", parsed.bodyWasHtml)
                put("body", parsed.bodyText)
                put("attachments", JSONArray().apply {
                    parsed.attachments.forEach { a ->
                        put(JSONObject().apply {
                            put("index", a.index)
                            put("filename", a.filename)
                            put("mimeType", a.mimeType)
                            put("sizeBytes", a.sizeBytes)
                            put("isInline", a.isInline)
                            a.contentId?.let { put("contentId", it) }
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to read mail message: ${e.message}")
        }
    }

    private suspend fun modifyMailMessage(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        val op = args.optString("op").ifEmpty {
            throw McpError(-32602, "Missing required argument: op")
        }.lowercase()
        val sessionId = requireMailSession(profileId)
        val client = mailClientFor(sessionId)
        return try {
            when (op) {
                "mark_read" -> client.setSeen(sessionId, messageId, true)
                "mark_unread" -> client.setSeen(sessionId, messageId, false)
                "flag" -> client.setFlagged(sessionId, messageId, true)
                "unflag" -> client.setFlagged(sessionId, messageId, false)
                "move" -> {
                    val dest = args.optString("destFolderId").ifEmpty {
                        throw McpError(-32602, "op=move requires destFolderId (see list_mail_folders)")
                    }
                    client.moveMessage(sessionId, messageId, dest)
                }
                "copy" -> {
                    val dest = args.optString("destFolderId").ifEmpty {
                        throw McpError(-32602, "op=copy requires destFolderId (see list_mail_folders)")
                    }
                    client.copyMessage(sessionId, messageId, dest)
                }
                "delete" -> client.deleteMessage(sessionId, messageId)
                else -> throw McpError(-32602, "Unknown op '$op' (mark_read|mark_unread|flag|unflag|move|copy|delete)")
            }
            JSONObject().put("ok", true).put("op", op).put("messageId", messageId)
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to $op mail message: ${e.message}")
        }
    }

    private suspend fun sendMail(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val to = args.optJSONArray("to").toTrimmedStringList()
        if (to.isEmpty()) {
            throw McpError(-32602, "Missing required argument: to (at least one recipient)")
        }
        if (!args.has("subject")) throw McpError(-32602, "Missing required argument: subject")
        if (!args.has("body")) throw McpError(-32602, "Missing required argument: body")
        val subject = args.optString("subject")
        val body = args.optString("body")
        val cc = args.optJSONArray("cc").toTrimmedStringList()
        val bcc = args.optJSONArray("bcc").toTrimmedStringList()
        val inReplyToMessageId = args.optString("inReplyToMessageId").ifBlank { null }
        val sessionId = requireMailSession(profileId)
        val attachments = resolveSendAttachments(args.optJSONArray("attachments"))
        val mail = sh.haven.core.mail.OutgoingMail(
            to = to, cc = cc, bcc = bcc, subject = subject, bodyText = body,
            attachments = attachments, inReplyToMessageId = inReplyToMessageId,
        )
        return try {
            val result = mailClientFor(sessionId).send(sessionId, mail)
            // Audit-log the send so it shows in Settings → connection log.
            runCatching {
                connectionLogRepository.logEvent(
                    profileId,
                    sh.haven.core.data.db.entities.ConnectionLog.Status.CONNECTED,
                    details = "Sent mail to ${to.size} recipient(s)" +
                        (if (subject.isNotBlank()) " — \"$subject\"" else ""),
                )
            }
            JSONObject().apply {
                put("sent", true)
                result.messageId?.let { put("messageId", it) }
                put("appendedToSent", result.appendedToSent)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to send mail: ${e.message}")
        }
    }

    private suspend fun resolveSendAttachments(
        arr: JSONArray?,
    ): List<sh.haven.core.mail.OutgoingAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<sh.haven.core.mail.OutgoingAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: throw McpError(-32602, "attachments[$i] must be an object { profileId, path }")
            val pid = o.optString("profileId").ifEmpty {
                throw McpError(-32602, "attachments[$i].profileId is required")
            }
            val path = o.optString("path").ifEmpty {
                throw McpError(-32602, "attachments[$i].path is required")
            }
            val backend = transportSelector.resolveFileBackend(pid)?.backend
                ?: throw McpError(-32603, "No connected backend for profile $pid (attachments[$i])")
            val size = try {
                backend.stat(path).size
            } catch (e: Exception) {
                throw McpError(-32602, "attachments[$i]: cannot stat $path on $pid: ${e.message}")
            }
            if (size > mailAttachmentMaxBytes) {
                throw McpError(
                    -32603,
                    "attachments[$i] ($path) is $size bytes — exceeds ${mailAttachmentMaxBytes / (1024 * 1024)} MiB cap",
                )
            }
            val bytes = try {
                backend.readBytes(path)
            } catch (e: Exception) {
                throw McpError(-32603, "attachments[$i]: failed to read $path: ${e.message}")
            }
            val name = sanitizeFilename(path.substringAfterLast('/'))
            out += sh.haven.core.mail.OutgoingAttachment(name, guessContentType(name), bytes)
        }
        return out
    }

    private suspend fun saveMailAttachment(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        if (!args.has("attachmentIndex")) {
            throw McpError(-32602, "Missing required argument: attachmentIndex")
        }
        val attachmentIndex = args.optInt("attachmentIndex", -1)
        if (attachmentIndex < 0) throw McpError(-32602, "attachmentIndex must be a non-negative integer")
        val destProfileId = args.optString("destProfileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: destProfileId")
        }
        val destDir = args.optString("destPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: destPath (destination directory)")
        }

        val sessionId = requireMailSession(profileId)
        val extracted = try {
            val raw = mailClientFor(sessionId).getMessageRaw(sessionId, messageId)
            sh.haven.feature.mail.MimeParser.extractAttachment(raw, attachmentIndex)
        } catch (e: IndexOutOfBoundsException) {
            throw McpError(-32602, "No attachment at index $attachmentIndex (see read_mail_message)")
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to read attachment: ${e.message}")
        }
        if (extracted.bytes.size > mailAttachmentMaxBytes) {
            throw McpError(
                -32603,
                "Attachment is ${extracted.bytes.size} bytes — exceeds ${mailAttachmentMaxBytes / (1024 * 1024)} MiB cap",
            )
        }

        val resolution = transportSelector.resolveFileBackend(destProfileId)
            ?: throw McpError(-32603, "No connected backend for profile $destProfileId")
        val backend = resolution.backend
        val name = sanitizeFilename(args.optString("destFilename").ifBlank { extracted.filename })
        val destPath = uniqueDestPath(backend, destDir.trimEnd('/'), name)
        try {
            backend.writeBytes(destPath, extracted.bytes)
        } catch (e: Exception) {
            throw McpError(-32603, "Save failed: ${e.message}")
        }
        JSONObject().apply {
            put("saved", true)
            put("destProfileId", destProfileId)
            put("backend", backend.label)
            put("destPath", destPath)
            put("filename", name)
            put("bytes", extracted.bytes.size)
        }
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f]"), "")
            .trim()
        return if (base.isBlank() || base == "." || base == "..") "attachment" else base
    }

    private suspend fun uniqueDestPath(
        backend: sh.haven.feature.sftp.transport.FileBackend,
        dir: String,
        name: String,
    ): String {
        fun join(n: String) = if (dir.isEmpty()) n else "$dir/$n"
        suspend fun exists(p: String): Boolean = try {
            backend.stat(p); true
        } catch (e: Exception) {
            false
        }
        if (!exists(join(name))) return join(name)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..99) {
            val cand = join("$stem ($n)$ext")
            if (!exists(cand)) return cand
        }
        return join("$stem (${System.currentTimeMillis()})$ext")
    }
}
