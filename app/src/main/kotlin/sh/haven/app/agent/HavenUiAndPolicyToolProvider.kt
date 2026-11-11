package sh.haven.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.repository.StandingPolicyRepository
import sh.haven.core.mcp.McpError
import java.io.ByteArrayOutputStream

/**
 * Self-hosting UI inspection/driving (`capture_haven_ui`, `dump_haven_ui`,
 * `tap_haven_ui`, `swipe_haven_ui`, `restart_app`), consent-queue inspection
 * (`get_pending_consent`), and Tier-3 standing-policy management
 * (`create_standing_policy`, `list_standing_policies`, `revoke_standing_policy`).
 */
internal class HavenUiAndPolicyToolProvider(
    private val context: Context,
    private val havenUiBridge: HavenUiBridge,
    private val standingPolicyRepository: StandingPolicyRepository,
    private val agentConsentManager: AgentConsentManager,
    private val currentClientHint: () -> String?,
    private val knownToolNames: () -> Set<String>,
) : ToolProvider {

    override fun tools(): Map<String, ToolHandler> = toolMapOf(
        "capture_haven_ui" to ToolHandler(
            description = "Capture HAVEN'S OWN rendered screen — the app UI the user is looking at right now (Connections list, terminal tab, a dialog, the file browser, an agent overlay), NOT a remote desktop (capture_desktop_tab) or the terminal text (read_terminal_snapshot). This is the 'perceive' half of the self-hosting loop: after install_apk_from_backend deploys a build, capture_haven_ui lets the agent see the result and diff it. Returns the image plus { width, height, imageWidth, imageHeight, format }. width/height are the FULL window in pixels — pass tap_haven_ui / swipe_haven_ui coordinates in THAT space even when the returned image was downscaled via maxWidth. If Settings → screen security (FLAG_SECURE) is on, returns { secure: true } with no image (capture is intentionally blocked). Errors if Haven is not in the foreground.",
            inputSchema = objectSchema {
                integer("maxWidth", "Downscale so the returned image is at most this many pixels wide (the reported width/height stay full-window). Default 1080 (clamped 160–4096).")
                string("format", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Let the agent see Haven's own screen" },
        ) { args -> captureHavenUi(args) },

        "dump_haven_ui" to ToolHandler(
            description = "Dump Haven's OWN foreground UI as a structured element list — the in-app equivalent of `uiautomator dump`, so you get EXACT control bounds instead of estimating them off a capture_haven_ui image. Returns { width, height, count, nodes:[{text, contentDescription, editableText, role, clickable, disabled, bounds:[left,top,right,bottom], centerX, centerY, window?}] } in the SAME window-pixel space tap_haven_ui / swipe_haven_ui use — read a control's centerX/centerY and tap it directly. Nodes from the activity window have no `window` field; nodes from an overlay that renders in its OWN window (e.g. the consent sheet, `window: consent-sheet`) are labelled with it (#355) — those bounds are that window's own pixel space. capture_haven_ui still photographs the activity window only, and tap_haven_ui refuses entirely while a consent prompt is pending, so an overlay can be observed but never tapped by an agent. Returns `windowsEnumerable`: when false, the platform refused the window-list lookup and only the activity window (plus self-registered overlays) could be walked — a dialog or dropdown may be on screen and missing from `nodes`, so an empty result does NOT mean nothing is open, and tapping blind will hit whatever is behind it. FLAG_SECURE blocks it. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Let the agent read Haven's own screen structure" },
        ) { _ -> dumpHavenUi() },

        "tap_haven_ui" to ToolHandler(
            description = "Inject a tap (or, with holdMs > 0, a press-and-hold) into HAVEN'S OWN UI at window-pixel (x, y) — the same coordinate space capture_haven_ui reports in its width/height. This is the 'drive' half of the self-hosting loop: read a control's position from a capture_haven_ui image, then tap it. A plain tap activates the control under the point via its accessibility OnClick action — so it drives everything from nav tabs and dialog buttons to the keyboard-toolbar keys and toggles nested in an expanded settings panel — and falls back to a raw MotionEvent (for the terminal cursor, custom drag handles) when nothing actionable sits there; holdMs > 0 always sends a real held press. Refused while a consent prompt is showing (so an injected tap can't self-confirm) and when Haven is not foreground. Returns { delivered, reason?, x, y, holdMs }. Verify the effect with a follow-up capture_haven_ui.",
            inputSchema = objectSchema {
                integer("x", "Window-pixel X (0..width from capture_haven_ui).", required = true)
                integer("y", "Window-pixel Y (0..height from capture_haven_ui).", required = true)
                integer("holdMs", "Press-and-hold duration. 0 (default) = a quick tap; >~500 triggers a long-press. Clamped 0–10000.")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val hold = args.optLong("holdMs", 0L)
                val verb = if (hold > 0) "Press-and-hold (${hold}ms)" else "Tap"
                "$verb Haven's own UI at (${args.optInt("x")}, ${args.optInt("y")})?"
            },
        ) { args -> tapHavenUi(args) },

        "swipe_haven_ui" to ToolHandler(
            description = "Inject a swipe/drag into HAVEN'S OWN UI from (fromX, fromY) to (toX, toY) in window pixels (the coordinate space capture_haven_ui reports), over durationMs split into N steps. Drives pager flings (swipe between Connections/Terminal/Files tabs), list scrolls, and bottom-sheet drags. Refused while a consent prompt is showing and when Haven is not foreground. Returns { delivered, reason?, fromX, fromY, toX, toY, durationMs, steps }. Verify with capture_haven_ui.",
            inputSchema = objectSchema {
                integer("fromX", "Start X (window px).", required = true)
                integer("fromY", "Start Y (window px).", required = true)
                integer("toX", "End X (window px).", required = true)
                integer("toY", "End Y (window px).", required = true)
                integer("durationMs", "Total swipe time. Default 200 (clamped 1–10000). Longer = slower drag (less fling momentum).")
                integer("steps", "ACTION_MOVE events between down and up. Default 16 (clamped 1–200).")
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Swipe Haven's own UI (${args.optInt("fromX")}, ${args.optInt("fromY")}) → (${args.optInt("toX")}, ${args.optInt("toY")})?"
            },
        ) { args -> swipeHavenUi(args) },

        "restart_app" to ToolHandler(
            description = "Restart Haven's own process — kill it and relaunch the app. The missing rung of the self-hosting loop: install_apk_from_backend stages a self-update, but the running process is held alive by Haven's persistent foreground service, so the new APK only takes effect on a process restart (previously only a manual Force-stop could do it). Call this right after get_app_info shows lastInstall.ok but the version hasn't changed. The MCP server lives in this process, so the link DROPS on the kill and the call returns just before it — the client must reconnect (/mcp reconnect), then re-check get_app_info for the new version. Also useful to recover a wedged UI. No-op-safe: it only relaunches Haven's own launcher activity. Returns { restarting:true }.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Restart the Haven app? (applies a pending update; the MCP link drops and must reconnect)" },
        ) { _ -> restartApp() },

        "create_standing_policy" to ToolHandler(
            description = "Propose a Tier-3 STANDING POLICY: a scoped, rate-capped, expiring grant that lets THIS client call the listed tools without a per-call consent prompt. The user's tap on this tool's consent sheet IS the installation — the sheet spells out the full scope. Use it when a workflow needs many consented calls in a row (e.g. a tap_haven_ui/swipe_haven_ui drive-and-verify loop) so the user grants the loop once instead of per tap. toolNames must be existing tools; some can never be covered (the policy tools themselves, install_apk_*, unpair_mcp_client). argConstraints (optional) pins arguments: every key given must exactly equal the call's argument (e.g. {\"profileId\":\"<id>\"} scopes the grant to one connection). Covered calls are still written to the audit log; the rate ceiling makes extra calls fall back to normal prompts; the policy expires on its own and can be revoked any time from Haven's Agent activity screen or via revoke_standing_policy. Returns { id, expiresAt }.",
            inputSchema = objectSchema {
                string("description", "Short human label for what this grant is for, shown on the consent sheet and the kill-switch list. E.g. \"Drive the UI to verify build 5.59.54\".", required = true)
                stringArray("toolNames", "Exact tool names the policy covers.", required = true)
                integer("maxCallsPerMinute", "Rate ceiling per rolling minute (default 60, clamped 1–600). Beyond it, calls fall back to per-call prompts.")
                integer("expiresInMinutes", "Lifetime (default 60, clamped 5–1440). After expiry the policy never applies.")
                property(
                    "argConstraints",
                    JSONObject().put("type", "object").put("description", "Optional exact-match argument pins, e.g. {\"profileId\":\"abc\"}."),
                )
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val names = args.optJSONArray("toolNames")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                } ?: "?"
                val rate = args.optInt("maxCallsPerMinute", 60).coerceIn(1, 600)
                val mins = args.optInt("expiresInMinutes", 60).coerceIn(5, 1440)
                val pins = args.optJSONObject("argConstraints")?.toString()?.let { " pinned to $it" } ?: ""
                "Install STANDING POLICY \"${args.optString("description")}\": allow [$names]$pins WITHOUT per-call prompts, up to $rate calls/min, for the next $mins min?"
            },
        ) { args -> createStandingPolicy(args) },

        "list_standing_policies" to ToolHandler(
            description = "List Tier-3 standing policies: id, client, description, covered tools, argConstraints, maxCallsPerMinute, expiresAt, remainingMinutes, enabled, active. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listStandingPolicies() },

        "revoke_standing_policy" to ToolHandler(
            description = "Revoke (delete) a standing policy by id — see list_standing_policies. Pure privilege reduction, so no prompt; the user's kill-switch lives on the Agent activity screen.",
            inputSchema = objectSchema {
                string("id", "Policy id from list_standing_policies.", required = true)
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> revokeStandingPolicy(args) },

        "get_pending_consent" to ToolHandler(
            description = "Return the consent/pairing prompts Haven is currently showing or holding, oldest first, or { pending: false } when none. Each entry: { id, toolName, clientHint, summary, isPairing, offerTimedAllow, requestedAt }. The consent sheet renders in its own window that capture_haven_ui / dump_haven_ui cannot see (#355), so this is the only way an agent can tell \"my call is waiting for the user\" from \"my call was denied\" — a backgrounded call now HOLDS for foreground rather than failing instantly (#337). toolName '_pairing' marks a pairing request. Read-only: this cannot answer a prompt, and no tool can — only the user can, on the device.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> getPendingConsent() },
    )

    private suspend fun captureHavenUi(args: JSONObject): ToolResult {
        val maxWidth = args.optInt("maxWidth", 1080).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"
        return when (val result = havenUiBridge.captureScreen()) {
            is HavenUiBridge.CaptureResult.Ok -> {
                val (b64, iw, ih) = withContext(Dispatchers.Default) {
                    encodeBitmapScaled(result.bitmap, maxWidth, format)
                }
                result.bitmap.recycle()
                ToolResult.Image(
                    base64 = b64,
                    mimeType = if (format == "jpeg") "image/jpeg" else "image/png",
                    structured = JSONObject().apply {
                        put("width", result.width)
                        put("height", result.height)
                        put("imageWidth", iw)
                        put("imageHeight", ih)
                        put("format", format)
                    },
                )
            }
            HavenUiBridge.CaptureResult.Secure -> ToolResult.Structured(JSONObject().apply {
                put("secure", true)
                put(
                    "message",
                    "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be captured. " +
                        "Turn off Settings → screen security to allow it.",
                )
            })
            is HavenUiBridge.CaptureResult.NoForeground -> throw McpError(-32603, result.reason)
            is HavenUiBridge.CaptureResult.Failed -> throw McpError(-32603, result.reason)
        }
    }

    private suspend fun dumpHavenUi(): JSONObject {
        return when (val result = havenUiBridge.dumpUi()) {
            is HavenUiBridge.DumpResult.Ok -> {
                val arr = JSONArray()
                for (n in result.nodes) {
                    arr.put(JSONObject().apply {
                        n.text?.let { put("text", it) }
                        n.contentDescription?.let { put("contentDescription", it) }
                        n.editableText?.let { put("editableText", it) }
                        n.role?.let { put("role", it) }
                        n.window?.let { put("window", it) }
                        put("clickable", n.clickable)
                        if (n.disabled) put("disabled", true)
                        put("bounds", JSONArray().put(n.left).put(n.top).put(n.right).put(n.bottom))
                        put("centerX", (n.left + n.right) / 2)
                        put("centerY", (n.top + n.bottom) / 2)
                    })
                }
                JSONObject().apply {
                    put("width", result.width)
                    put("height", result.height)
                    put("count", result.nodes.size)
                    put("nodes", arr)
                    put("windowsEnumerable", result.windowsEnumerable)
                    if (!result.windowsEnumerable) {
                        put(
                            "coverageWarning",
                            "This platform refused the window-list lookup, so only the activity " +
                                "window was walked. A dialog, dropdown menu or bottom sheet may be " +
                                "on screen and absent from nodes — do not read this as 'no dialog " +
                                "is open', and do not tap blind, because taps land on whatever is " +
                                "behind it. Take a screenshot to check.",
                        )
                    }
                }
            }
            HavenUiBridge.DumpResult.Secure -> JSONObject().apply {
                put("secure", true)
                put("message", "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be dumped.")
            }
            is HavenUiBridge.DumpResult.NoForeground -> throw McpError(-32603, result.reason)
            is HavenUiBridge.DumpResult.Failed -> throw McpError(-32603, result.reason)
        }
    }

    /**
     * Capture Haven's own screen for the `ui://haven/screen` MCP resource —
     * called by `McpTools.readUiScreenResource` / `McpServer.handleResourcesRead`.
     */
    suspend fun readUiScreenResource(): Pair<String, String> {
        return when (val r = havenUiBridge.captureScreen()) {
            is HavenUiBridge.CaptureResult.Ok -> {
                val enc = withContext(Dispatchers.Default) { encodeBitmapScaled(r.bitmap, 1080, "png") }
                r.bitmap.recycle()
                enc.first to "image/png"
            }
            HavenUiBridge.CaptureResult.Secure -> throw McpError(
                -32603,
                "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be captured. " +
                    "Turn off Settings → screen security to allow it.",
            )
            is HavenUiBridge.CaptureResult.NoForeground -> throw McpError(-32603, r.reason)
            is HavenUiBridge.CaptureResult.Failed -> throw McpError(-32603, r.reason)
        }
    }

    private suspend fun tapHavenUi(args: JSONObject): JSONObject {
        val x = requireIntArg(args, "x")
        val y = requireIntArg(args, "y")
        val holdMs = args.optLong("holdMs", 0L).coerceIn(0L, 10_000L)
        return injectResultJson(havenUiBridge.dispatchTap(x, y, holdMs)).apply {
            put("x", x); put("y", y); put("holdMs", holdMs)
        }
    }

    private suspend fun swipeHavenUi(args: JSONObject): JSONObject {
        val fromX = requireIntArg(args, "fromX")
        val fromY = requireIntArg(args, "fromY")
        val toX = requireIntArg(args, "toX")
        val toY = requireIntArg(args, "toY")
        val durationMs = args.optLong("durationMs", 200L).coerceIn(1L, 10_000L)
        val steps = args.optInt("steps", 16).coerceIn(1, 200)
        val result = havenUiBridge.dispatchSwipe(fromX, fromY, toX, toY, durationMs, steps)
        return injectResultJson(result).apply {
            put("fromX", fromX); put("fromY", fromY); put("toX", toX); put("toY", toY)
            put("durationMs", durationMs); put("steps", steps)
        }
    }

    private fun injectResultJson(result: HavenUiBridge.InjectResult): JSONObject = JSONObject().apply {
        when (result) {
            HavenUiBridge.InjectResult.Delivered -> put("delivered", true)
            is HavenUiBridge.InjectResult.Refused -> {
                put("delivered", false)
                put("reason", result.reason)
            }
        }
    }

    private fun restartApp(): JSONObject {
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw McpError(-32603, "No launcher activity to relaunch ${context.packageName}")
        context.startActivity(sh.haven.app.RestartActivity.launchIntent(context))
        return JSONObject().apply {
            put("restarting", true)
            put(
                "message",
                "Haven is restarting (~1s) via a resurrector process. The MCP link drops on the kill — " +
                    "reconnect, then get_app_info to confirm the new version. A staged self-update takes effect on the relaunch.",
            )
        }
    }

    private suspend fun createStandingPolicy(args: JSONObject): JSONObject {
        val client = currentClientHint()?.takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "No client identity on this call — initialize with clientInfo.name first.")
        val description = args.optString("description").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "description is required")
        val namesArr = args.optJSONArray("toolNames")
            ?: throw McpError(-32602, "toolNames is required")
        val toolNames = (0 until namesArr.length()).map { namesArr.optString(it) }.filter { it.isNotBlank() }
        if (toolNames.isEmpty()) throw McpError(-32602, "toolNames must be a non-empty array of tool names")
        val known = knownToolNames()
        toolNames.forEach { t ->
            if (t in StandingPolicyEnforcer.NEVER_COVERABLE_TOOLS) {
                throw McpError(-32602, "'$t' can never be covered by a standing policy (Tier-4 / policy-management tools stay per-action)")
            }
            if (t !in known) {
                throw McpError(-32602, "Unknown tool: '$t' (standing policies cover built-in tools only)")
            }
        }
        val rate = args.optInt("maxCallsPerMinute", 60).coerceIn(1, 600)
        val mins = args.optInt("expiresInMinutes", 60).coerceIn(5, 1440)
        val constraints = args.optJSONObject("argConstraints")?.toString()
        val policy = StandingPolicy(
            clientHint = client,
            description = description,
            toolNamesJson = JSONArray(toolNames).toString(),
            argConstraintsJson = constraints,
            maxCallsPerMinute = rate,
            expiresAt = System.currentTimeMillis() + mins * 60_000L,
        )
        standingPolicyRepository.savePolicy(policy)
        return JSONObject().apply {
            put("id", policy.id)
            put("clientHint", client)
            put("toolNames", JSONArray(toolNames))
            put("maxCallsPerMinute", rate)
            put("expiresInMinutes", mins)
            put("expiresAt", policy.expiresAt)
        }
    }

    private suspend fun listStandingPolicies(): JSONObject {
        standingPolicyRepository.purgeExpired()
        val now = System.currentTimeMillis()
        val policies = standingPolicyRepository.allPolicies()
        return JSONObject().apply {
            put("count", policies.size)
            put("policies", JSONArray().apply {
                policies.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("clientHint", p.clientHint)
                        put("description", p.description)
                        runCatching { put("toolNames", JSONArray(p.toolNamesJson)) }
                        p.argConstraintsJson?.let { c -> runCatching { put("argConstraints", JSONObject(c)) } }
                        put("maxCallsPerMinute", p.maxCallsPerMinute)
                        put("expiresAt", p.expiresAt)
                        put("remainingMinutes", ((p.expiresAt - now) / 60_000L).coerceAtLeast(0))
                        put("enabled", p.enabled)
                        put("active", p.enabled && p.expiresAt > now)
                    })
                }
            })
        }
    }

    private suspend fun revokeStandingPolicy(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val existing = standingPolicyRepository.getPolicy(id)
            ?: throw McpError(-32602, "No standing policy with id '$id' (see list_standing_policies)")
        standingPolicyRepository.deletePolicy(id)
        return JSONObject().apply {
            put("revoked", true)
            put("id", id)
            put("description", existing.description)
        }
    }

    private fun getPendingConsent(): JSONObject {
        val pending = agentConsentManager.pending.value
        if (pending.isEmpty()) return JSONObject().put("pending", false)
        return JSONObject().apply {
            put("pending", true)
            put("count", pending.size)
            put(
                "requests",
                JSONArray().apply {
                    pending.forEach { r ->
                        put(
                            JSONObject().apply {
                                put("id", r.id)
                                put("toolName", r.toolName)
                                put("clientHint", r.clientHint ?: JSONObject.NULL)
                                put("summary", r.summary)
                                put("isPairing", r.toolName == AgentConsentManager.PAIRING_TOOL_NAME)
                                put("offerTimedAllow", r.offerTimedAllow)
                                put("requestedAt", r.requestedAt)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun encodeBitmapScaled(bmp: Bitmap, maxWidth: Int, format: String): Triple<String, Int, Int> {
        val out = if (maxWidth in 1 until bmp.width) {
            val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
        } else {
            bmp
        }
        val baos = ByteArrayOutputStream()
        if (format == "jpeg") out.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        else out.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val result = Triple(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP), out.width, out.height)
        if (out !== bmp) out.recycle()
        return result
    }
}
