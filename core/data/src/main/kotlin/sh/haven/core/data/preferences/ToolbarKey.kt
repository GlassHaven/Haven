package sh.haven.core.data.preferences

/**
 * Every key that can appear on the keyboard toolbar.
 * [id] is persisted in preferences; [label] is shown in the config dialog and on buttons.
 * [isModifier] keys render as toggle buttons; [isAction] keys are functional (Esc, Tab, Keyboard).
 */
enum class ToolbarKey(val id: String, val label: String, val isModifier: Boolean = false, val isAction: Boolean = false) {
    KEYBOARD("keyboard", "Keyboard", isAction = true),
    ESC_KEY("esc", "Esc", isAction = true),
    TAB_KEY("tab", "Tab", isAction = true),
    SHIFT("shift", "Shift", isModifier = true),
    CTRL("ctrl", "Ctrl", isModifier = true),
    ALT("alt", "Alt", isModifier = true),
    ALTGR("altgr", "AltGr", isModifier = true),
    ARROW_LEFT("arrow_left", "Left", isAction = true),
    ARROW_UP("arrow_up", "Up", isAction = true),
    ARROW_DOWN("arrow_down", "Down", isAction = true),
    ARROW_RIGHT("arrow_right", "Right", isAction = true),
    HOME("home", "Home", isAction = true),
    END("end", "End", isAction = true),
    PGUP("pgup", "PgUp", isAction = true),
    PGDN("pgdn", "PgDn", isAction = true),
    F1("f1", "F1", isAction = true),
    F2("f2", "F2", isAction = true),
    F3("f3", "F3", isAction = true),
    F4("f4", "F4", isAction = true),
    F5("f5", "F5", isAction = true),
    F6("f6", "F6", isAction = true),
    F7("f7", "F7", isAction = true),
    F8("f8", "F8", isAction = true),
    F9("f9", "F9", isAction = true),
    F10("f10", "F10", isAction = true),
    F11("f11", "F11", isAction = true),
    F12("f12", "F12", isAction = true),
    SYM_PIPE("sym_pipe", "|"),
    SYM_TILDE("sym_tilde", "~"),
    SYM_SLASH("sym_slash", "/"),
    SYM_DASH("sym_dash", "-"),
    SYM_UNDERSCORE("sym_underscore", "_"),
    SYM_EQUALS("sym_equals", "="),
    SYM_PLUS("sym_plus", "+"),
    SYM_BACKSLASH("sym_backslash", "\\"),
    SYM_SQUOTE("sym_squote", "'"),
    SYM_DQUOTE("sym_dquote", "\""),
    SYM_SEMICOLON("sym_semicolon", ";"),
    SYM_COLON("sym_colon", ":"),
    SYM_BANG("sym_bang", "!"),
    SYM_QUESTION("sym_question", "?"),
    SYM_AT("sym_at", "@"),
    SYM_HASH("sym_hash", "#"),
    SYM_DOLLAR("sym_dollar", "$"),
    SYM_PERCENT("sym_percent", "%"),
    SYM_CARET("sym_caret", "^"),
    SYM_AMP("sym_amp", "&"),
    SYM_STAR("sym_star", "*"),
    SYM_LPAREN("sym_lparen", "("),
    SYM_RPAREN("sym_rparen", ")"),
    SYM_LBRACKET("sym_lbracket", "["),
    SYM_RBRACKET("sym_rbracket", "]"),
    SYM_LBRACE("sym_lbrace", "{"),
    SYM_RBRACE("sym_rbrace", "}"),
    SYM_LT("sym_lt", "<"),
    SYM_GT("sym_gt", ">"),
    SYM_BACKTICK("sym_backtick", "`");

    /** The character this key sends (only for symbol keys). */
    val char: Char? get() = when (this) {
        SYM_PIPE -> '|'; SYM_TILDE -> '~'; SYM_SLASH -> '/'
        SYM_DASH -> '-'; SYM_UNDERSCORE -> '_'; SYM_EQUALS -> '='
        SYM_PLUS -> '+'; SYM_BACKSLASH -> '\\'; SYM_SQUOTE -> '\''
        SYM_DQUOTE -> '"'; SYM_SEMICOLON -> ';'; SYM_COLON -> ':'
        SYM_BANG -> '!'; SYM_QUESTION -> '?'; SYM_AT -> '@'
        SYM_HASH -> '#'; SYM_DOLLAR -> '$'; SYM_PERCENT -> '%'
        SYM_CARET -> '^'; SYM_AMP -> '&'; SYM_STAR -> '*'
        SYM_LPAREN -> '('; SYM_RPAREN -> ')'; SYM_LBRACKET -> '['
        SYM_RBRACKET -> ']'; SYM_LBRACE -> '{'; SYM_RBRACE -> '}'
        SYM_LT -> '<'; SYM_GT -> '>'; SYM_BACKTICK -> '`'
        else -> null
    }

    companion object {
        fun fromId(id: String): ToolbarKey? = entries.find { it.id == id }

        /** Default row 1: keyboard toggle, function keys, nav block top. */
        val DEFAULT_ROW1 = listOf(
            KEYBOARD, ESC_KEY, TAB_KEY, SYM_SLASH, HOME, ARROW_UP, END, PGUP,
        )

        /** Default row 2: modifiers, nav block bottom, symbols. */
        val DEFAULT_ROW2 = listOf(
            SHIFT, CTRL, ALT, ARROW_LEFT, ARROW_DOWN, ARROW_RIGHT, PGDN,
            SYM_PIPE, SYM_TILDE, SYM_SLASH, SYM_BACKSLASH, SYM_BACKTICK,
        )

        /** Serialize to comma-separated IDs for DataStore. */
        fun toIdString(keys: List<ToolbarKey>): String = keys.joinToString(",") { it.id }

        /** Deserialize from comma-separated IDs. */
        fun fromIdString(value: String): List<ToolbarKey> {
            if (value.isBlank()) return emptyList()
            return value.split(",").mapNotNull { fromId(it.trim()) }
        }
    }
}
