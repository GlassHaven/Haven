package sh.haven.app.agent.mailrules

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.MailRuleDao
import sh.haven.core.data.db.MailRuleFiringDao
import sh.haven.core.data.db.MailRulePendingActionDao
import sh.haven.core.data.db.MailWatermarkDao
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.db.entities.MailWatermark
import sh.haven.core.data.mailrule.ImapFilterOp
import sh.haven.core.data.mailrule.MailCondition
import sh.haven.core.data.mailrule.MailCriteria
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson
import sh.haven.core.data.mailrule.MatchableMessage
import sh.haven.core.data.mailrule.StringOp
import sh.haven.core.data.mailrule.staticDestructive
import sh.haven.core.data.repository.MailRuleRepository

class MailRuleEngineTest {

    // ---- in-memory DAOs (enough to back a real MailRuleRepository) ----

    private class FakeRuleDao(val rules: MutableList<MailRule> = mutableListOf()) : MailRuleDao {
        override fun observeAll(): Flow<List<MailRule>> = emptyFlow()
        override suspend fun getAll(): List<MailRule> =
            rules.sortedWith(compareBy({ it.orderIndex }, { it.createdAt }))
        override suspend fun enabledForAccount(profileId: String) =
            rules.filter { it.enabled && (it.accountProfileId == null || it.accountProfileId == profileId) }
                .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }))
        override suspend fun allEnabled() = rules.filter { it.enabled }
        override suspend fun getById(id: String) = rules.firstOrNull { it.id == id }
        override suspend fun enabledCount() = rules.count { it.enabled }
        override suspend fun upsert(rule: MailRule) { rules.removeAll { it.id == rule.id }; rules += rule }
        override suspend fun touchLastFired(id: String, timestamp: Long) {
            rules.replaceAll { if (it.id == id) it.copy(lastFiredAt = timestamp) else it }
        }
        override suspend fun deleteById(id: String) { rules.removeAll { it.id == id } }
    }

    private class FakeWatermarkDao(val items: MutableMap<Pair<String, String>, MailWatermark> = mutableMapOf()) : MailWatermarkDao {
        override suspend fun get(profileId: String, folderId: String) = items[profileId to folderId]
        override suspend fun upsert(watermark: MailWatermark) { items[watermark.profileId to watermark.folderId] = watermark }
        override suspend fun deleteForProfile(profileId: String) { items.keys.filter { it.first == profileId }.forEach { items.remove(it) } }
    }

    private class FakeFiringDao(val rows: MutableList<MailRuleFiring> = mutableListOf()) : MailRuleFiringDao {
        private var seq = 0L
        override suspend fun insert(firing: MailRuleFiring): Long { val id = ++seq; rows += firing.copy(id = id); return id }
        override suspend fun update(firing: MailRuleFiring) { rows.replaceAll { if (it.id == firing.id) firing else it } }
        override suspend fun getFiring(ruleId: String, profileId: String, folderId: String, uid: Long) =
            rows.firstOrNull { it.ruleId == ruleId && it.profileId == profileId && it.folderId == folderId && it.uid == uid }
        override fun observeRecent(limit: Int): Flow<List<MailRuleFiring>> = emptyFlow()
        override suspend fun recent(limit: Int) = rows.takeLast(limit)
        override suspend fun trimTo(keep: Int) {}
    }

    private class FakePendingDao(val rows: MutableList<MailRulePendingAction> = mutableListOf()) : MailRulePendingActionDao {
        override suspend fun insert(action: MailRulePendingAction) { rows += action }
        override fun observeAll(): Flow<List<MailRulePendingAction>> = emptyFlow()
        override suspend fun all() = rows.toList()
        override suspend fun deleteById(id: String) { rows.removeAll { it.id == id } }
    }

    private class FakePoller(
        var state: FolderUidState?,
        var newMessages: List<PolledMessage> = emptyList(),
        var content: MessageContent? = null,
    ) : MailRulePoller {
        override suspend fun folderState(profileId: String, folderId: String) = state
        override suspend fun fetchNew(profileId: String, folderId: String, sinceUid: Long, max: Int) =
            newMessages.filter { it.uid > sinceUid }.sortedBy { it.uid }.take(max)
        override suspend fun fetchContent(profileId: String, messageId: String) = content
    }

    private class RecordingRunner(
        /** Action indices (by call order) that should throw to simulate a hard failure. */
        val throwOn: Set<Int> = emptySet(),
        val queueDestructive: Boolean = false,
    ) : MailRuleActionRunner {
        val ran = mutableListOf<MailRuleAction>()
        val notified = mutableListOf<String>()
        private var n = 0
        override suspend fun run(action: MailRuleAction, ctx: ActionContext, foreground: Boolean): ActionOutcome {
            val i = n++
            ran += action
            if (i in throwOn) throw RuntimeException("boom@$i")
            val queued = queueDestructive && (action.staticDestructive() == true)
            return ActionOutcome(ok = true, summary = action::class.simpleName.orEmpty(), queued = queued)
        }
        override suspend fun notifyRuleFired(ruleName: String, subject: String) { notified += "$ruleName/$subject" }
    }

    private fun rule(
        id: String = "r1",
        criteria: MailCriteria,
        actions: List<MailRuleAction>,
        enabled: Boolean = true,
        order: Int = 0,
        folderId: String = "INBOX",
        stopOnMatch: Boolean = false,
        notifyOnFire: Boolean = false,
        account: String? = null,
    ) = MailRule(
        id = id, name = id, enabled = enabled, orderIndex = order, accountProfileId = account,
        folderId = folderId, criteriaJson = MailRuleJson.criteriaToJson(criteria),
        actionsJson = MailRuleJson.actionsToJson(actions), stopOnMatch = stopOnMatch,
        notifyOnFire = notifyOnFire, createdAt = 0L,
    )

    private fun env(subject: String = "HAVEN-RULE-TEST hi", from: String = "a@x") =
        MatchableMessage(from, "A", listOf("me@x"), subject, unread = true)

    private fun msg(uid: Long, subject: String = "HAVEN-RULE-TEST hi") =
        PolledMessage("INBOX $uid", uid, env(subject))

    private fun engine(repo: MailRuleRepository, poller: FakePoller, runner: RecordingRunner, foreground: Boolean = true) =
        MailRuleEngine(repo, poller, runner, now = { 1000L }, foreground = { foreground })

    private fun repo(rules: List<MailRule>, fwm: FakeWatermarkDao = FakeWatermarkDao(), ffd: FakeFiringDao = FakeFiringDao(), fpd: FakePendingDao = FakePendingDao()) =
        Triple(MailRuleRepository(FakeRuleDao(rules.toMutableList()), fwm, ffd, fpd), fwm, ffd)

    private val subjectRule = MailCriteria(conditions = listOf(MailCondition.Subject(StringOp.CONTAINS, "HAVEN-RULE-TEST")))

    @Test
    fun firstArmSeedsWatermarkAndRunsNothing() = runBlocking {
        val (r, wm, _) = repo(listOf(rule(criteria = subjectRule, actions = listOf(MailRuleAction.Notify("t", "b")))))
        val poller = FakePoller(FolderUidState(uidValidity = 1, uidNext = 51, maxUid = 50), newMessages = listOf(msg(50)))
        val runner = RecordingRunner()
        engine(r, poller, runner).pollOnce("p1", "INBOX")
        assertEquals(50L, wm.items["p1" to "INBOX"]!!.lastSeenUid)
        assertTrue("existing mail must not fire on first arm", runner.ran.isEmpty())
    }

    @Test
    fun uidValidityChangeSkipsWithoutReplay() = runBlocking {
        val fwm = FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", uidValidity = 1, lastSeenUid = 10)))
        val ffd = FakeFiringDao()
        val r = MailRuleRepository(FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = listOf(MailRuleAction.ImapFilter(ImapFilterOp.DELETE))))), fwm, ffd, FakePendingDao())
        // server renumbered: uidValidity now 2, plenty of "new" messages
        val poller = FakePoller(FolderUidState(uidValidity = 2, uidNext = 101, maxUid = 100), newMessages = (11L..100L).map { msg(it) })
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner, now = { 1L }, foreground = { true }).pollOnce("p1", "INBOX")
        assertTrue("must NOT replay destructive actions over the mailbox", runner.ran.isEmpty())
        assertEquals(100L, fwm.items["p1" to "INBOX"]!!.lastSeenUid)
        assertEquals(2L, fwm.items["p1" to "INBOX"]!!.uidValidity)
        assertTrue(ffd.rows.any { it.kind == MailRuleFiring.KIND_UIDVALIDITY_RESET })
    }

    @Test
    fun pollSkippedWhenDisconnectedLeavesStateUntouched() = runBlocking {
        val fwm = FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10)))
        val ffd = FakeFiringDao()
        val r = MailRuleRepository(FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = listOf(MailRuleAction.Notify("t", "b"))))), fwm, ffd, FakePendingDao())
        val poller = FakePoller(state = null) // can't connect
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner).pollOnce("p1", "INBOX")
        assertEquals(10L, fwm.items["p1" to "INBOX"]!!.lastSeenUid) // unchanged
        assertTrue(runner.ran.isEmpty())
        assertTrue(ffd.rows.any { it.kind == MailRuleFiring.KIND_POLL_SKIPPED })
    }

    @Test
    fun matchFiresActionsAndAdvancesWatermark() = runBlocking {
        val fwm = FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10)))
        val ffd = FakeFiringDao()
        val actions = listOf(MailRuleAction.Notify("t", "b"), MailRuleAction.SendToAgent("hi"))
        val r = MailRuleRepository(FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = actions, notifyOnFire = true))), fwm, ffd, FakePendingDao())
        val poller = FakePoller(FolderUidState(1, 13, 12), newMessages = listOf(msg(11), msg(12, subject = "unrelated")))
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner, now = { 5L }, foreground = { true }).pollOnce("p1", "INBOX")
        // only uid 11 matched (12's subject doesn't contain the marker)
        assertEquals(listOf("Notify", "SendToAgent"), runner.ran.map { it::class.simpleName })
        assertEquals(12L, fwm.items["p1" to "INBOX"]!!.lastSeenUid)
        val firing = ffd.rows.first { it.ruleId == "r1" }
        assertEquals(0b11L, firing.actionsCompletedMask) // both actions completed
        assertEquals(listOf("r1/HAVEN-RULE-TEST hi"), runner.notified)
    }

    @Test
    fun failureIsolationOneActionFailsOthersStillRun() = runBlocking {
        val ffd = FakeFiringDao()
        val actions = listOf(MailRuleAction.Notify("t", "b"), MailRuleAction.SendToAgent("hi"), MailRuleAction.Notify("t2", "b2"))
        val r = MailRuleRepository(
            FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = actions))),
            FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10))), ffd, FakePendingDao(),
        )
        val poller = FakePoller(FolderUidState(1, 12, 11), newMessages = listOf(msg(11)))
        val runner = RecordingRunner(throwOn = setOf(1)) // 2nd action throws
        MailRuleEngine(r, poller, runner, now = { 1L }, foreground = { true }).pollOnce("p1", "INBOX")
        assertEquals("all three attempted despite the middle failure", 3, runner.ran.size)
        val firing = ffd.rows.first { it.ruleId == "r1" }
        assertEquals("bits 0 and 2 set, bit 1 (failed) clear", 0b101L, firing.actionsCompletedMask)
    }

    @Test
    fun completionMaskSkipsAlreadyDoneActionsOnReplay() = runBlocking {
        val ffd = FakeFiringDao()
        // Pre-seed a partial firing for (r1, uid 11): action index 0 already done.
        ffd.rows += MailRuleFiring(id = 1, ruleId = "r1", profileId = "p1", folderId = "INBOX", uid = 11, actionsCompletedMask = 0b01L)
        val actions = listOf(MailRuleAction.Notify("t", "b"), MailRuleAction.SendToAgent("hi"))
        // watermark still BEFORE uid 11 (simulating a crash before it advanced)
        val r = MailRuleRepository(
            FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = actions))),
            FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10))), ffd, FakePendingDao(),
        )
        val poller = FakePoller(FolderUidState(1, 12, 11), newMessages = listOf(msg(11)))
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner, now = { 1L }, foreground = { true }).pollOnce("p1", "INBOX")
        assertEquals("only the not-yet-done action re-runs", listOf("SendToAgent"), runner.ran.map { it::class.simpleName })
    }

    @Test
    fun stopOnMatchShortCircuitsLaterRules() = runBlocking {
        val ffd = FakeFiringDao()
        val r1 = rule(id = "r1", order = 0, criteria = subjectRule, actions = listOf(MailRuleAction.Notify("a", "b")), stopOnMatch = true)
        val r2 = rule(id = "r2", order = 1, criteria = subjectRule, actions = listOf(MailRuleAction.SendToAgent("x")))
        val r = MailRuleRepository(
            FakeRuleDao(mutableListOf(r1, r2)),
            FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10))), ffd, FakePendingDao(),
        )
        val poller = FakePoller(FolderUidState(1, 12, 11), newMessages = listOf(msg(11)))
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner, now = { 1L }, foreground = { true }).pollOnce("p1", "INBOX")
        assertEquals(listOf("Notify"), runner.ran.map { it::class.simpleName })
        assertNull("r2 must not have fired", ffd.rows.firstOrNull { it.ruleId == "r2" })
    }

    @Test
    fun destructiveImapOpsRunAfterOtherActions() = runBlocking {
        val ffd = FakeFiringDao()
        // authored order: delete first, then notify — engine must reorder delete to last
        val actions = listOf(MailRuleAction.ImapFilter(ImapFilterOp.DELETE), MailRuleAction.Notify("t", "b"), MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ))
        val r = MailRuleRepository(
            FakeRuleDao(mutableListOf(rule(criteria = subjectRule, actions = actions))),
            FakeWatermarkDao(mutableMapOf(("p1" to "INBOX") to MailWatermark("p1", "INBOX", 1, 10))), ffd, FakePendingDao(),
        )
        val poller = FakePoller(FolderUidState(1, 12, 11), newMessages = listOf(msg(11)))
        val runner = RecordingRunner()
        MailRuleEngine(r, poller, runner, now = { 1L }, foreground = { true }).pollOnce("p1", "INBOX")
        val kinds = runner.ran.map {
            when (it) {
                is MailRuleAction.ImapFilter -> "imap:${it.op}"
                is MailRuleAction.Notify -> "notify"
                else -> "other"
            }
        }
        assertEquals(listOf("notify", "imap:MARK_READ", "imap:DELETE"), kinds)
    }
}
