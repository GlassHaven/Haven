package sh.haven.feature.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage as SavedChatMessage
import sh.haven.core.data.repository.ChatRepository
import sh.haven.core.openai.ModelInfo
import sh.haven.core.openai.OpenAiSessionManager

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHelpersTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `flavour toggle adds switches and removes suffixes while preserving Ollama tags`() {
        val model = "deepseek/deepseek-chat"
        val free = ChatModelFlavours.toggleFlavour(model, ":free")
        assertEquals("deepseek/deepseek-chat:free", free)
        assertEquals(":free", ChatModelFlavours.activeFlavour(free))

        val batch = ChatModelFlavours.toggleFlavour(free, ":batch")
        assertEquals("deepseek/deepseek-chat:batch", batch)
        assertEquals(":batch", ChatModelFlavours.activeFlavour(batch))

        val stripped = ChatModelFlavours.toggleFlavour(batch, ":batch")
        assertEquals("deepseek/deepseek-chat", stripped)
        assertNull(ChatModelFlavours.activeFlavour(stripped))

        // Local Ollama tag like :7b must not be stripped as a routing flavour
        val ollama = "llama3.2:7b"
        assertNull(ChatModelFlavours.activeFlavour(ollama))
        val ollamaFree = ChatModelFlavours.toggleFlavour(ollama, ":free")
        assertEquals("llama3.2:7b:free", ollamaFree)
        assertEquals("llama3.2:7b", ChatModelFlavours.toggleFlavour(ollamaFree, ":free"))
    }

    @Test
    fun `filterModels matches substring case-insensitively and strips flavour suffix from query`() {
        val models = listOf(
            ModelInfo("openai/gpt-4o-mini"),
            ModelInfo("deepseek/deepseek-r1"),
            ModelInfo("anthropic/claude-3.7-sonnet"),
        )
        assertEquals(3, ChatModelFlavours.filterModels(models, "  ").size)
        assertEquals(
            listOf("deepseek/deepseek-r1"),
            ChatModelFlavours.filterModels(models, "DEEPSEEK").map { it.id },
        )
        assertEquals(
            listOf("deepseek/deepseek-r1"),
            ChatModelFlavours.filterModels(models, "deepseek:free").map { it.id },
        )
    }

    @Test
    fun `slash commands suggest and parse composer commands`() {
        assertEquals(5, ChatSlashCommands.suggestionsFor("/").size)
        assertEquals(
            listOf("/model"),
            ChatSlashCommands.suggestionsFor("/mo").map { it.command },
        )
        assertTrue(ChatSlashCommands.suggestionsFor("hello /model").isEmpty())

        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.NewSession,
            ChatSlashCommands.parse("/new"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.ClearTranscript,
            ChatSlashCommands.parse("/clear"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.ToggleSave,
            ChatSlashCommands.parse("/save"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.OpenHistory,
            ChatSlashCommands.parse("/history"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.SelectModel(null),
            ChatSlashCommands.parse("/model"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.SelectModel(":free"),
            ChatSlashCommands.parse("/model :free"),
        )
        assertEquals(
            ChatSlashCommands.ParsedSlashCommand.SelectModel("deepseek/deepseek-r1:free"),
            ChatSlashCommands.parse("/model deepseek/deepseek-r1:free"),
        )
        assertNull(ChatSlashCommands.parse("/unknown"))
    }

    @Test
    fun `loadConversation and newConversation manage saved session state`() {
        val sessionManager = mockk<OpenAiSessionManager>()
        every { sessionManager.getSessionsForProfile("p1") } returns listOf(
            OpenAiSessionManager.SessionState(
                sessionId = "s1",
                profileId = "p1",
                label = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                status = OpenAiSessionManager.SessionState.Status.CONNECTED,
                models = listOf(ModelInfo("openai/gpt-4o-mini")),
            ),
        )
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val conv = ChatConversation(id = "c1", profileId = "p1", title = "Saved session")
        every { chatRepo.observeAll() } returns flowOf(listOf(conv))
        coEvery { chatRepo.getConversation("c1") } returns conv
        coEvery { chatRepo.getMessages("c1") } returns listOf(
            SavedChatMessage(
                id = "m1",
                conversationId = "c1",
                role = "user",
                content = "ping",
            ),
            SavedChatMessage(
                id = "m2",
                conversationId = "c1",
                role = "assistant",
                content = "pong",
                model = "deepseek/deepseek-chat:free",
            ),
        )

        val vm = ChatViewModel(
            openAiSessionManager = sessionManager,
            connectionRepository = mockk(relaxed = true),
            chatRepository = chatRepo,
            openAiClient = mockk(relaxed = true),
            tunnelResolver = mockk(relaxed = true),
            chatAttachBroker = sh.haven.core.data.attach.ChatAttachBroker(),
            chatRemoteReader = mockk(relaxed = true),
            context = mockk(relaxed = true),
        )

        vm.attach("p1")
        assertEquals(1, vm.ui.value.savedConversations.size)
        assertEquals("openai/gpt-4o-mini", vm.ui.value.selectedModel)

        vm.toggleModelFlavour(":free")
        assertEquals("openai/gpt-4o-mini:free", vm.ui.value.selectedModel)

        vm.loadConversation("c1")
        assertTrue(vm.ui.value.saveEnabled)
        assertEquals("c1", vm.ui.value.activeConversationId)
        assertEquals(2, vm.ui.value.messages.size)
        assertEquals("deepseek/deepseek-chat:free", vm.ui.value.selectedModel)

        vm.deleteSavedConversation("c1")
        coVerify { chatRepo.deleteConversation("c1") }
        assertFalse(vm.ui.value.saveEnabled)
        assertNull(vm.ui.value.activeConversationId)

        vm.newConversation()
        assertTrue(vm.ui.value.messages.isEmpty())
    }
}
