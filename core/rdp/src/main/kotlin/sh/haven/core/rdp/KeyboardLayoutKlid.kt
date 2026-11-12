package sh.haven.core.rdp

import java.util.Locale

/**
 * #504: map the device locale to the Windows keyboard-layout identifier
 * (KLID) announced in the RDP connect. Servers that build the session's
 * input layout from the announcement — Windows, xrdp, KRDP — otherwise
 * hand every non-US user a US layout; the reporter's guest composed
 * Polish on the console (raw scancodes, guest keymap) but not in
 * layout-honouring contexts. VirtualBox-style servers ignore this.
 *
 * The table covers layouts where the language's dominant physical
 * keyboard differs meaningfully from US. Anything unlisted announces
 * US English (0x0409) — the pre-#504 behaviour for everyone.
 */
fun keyboardLayoutKlid(locale: Locale = Locale.getDefault()): UInt {
    val lang = locale.language.lowercase(Locale.ROOT)
    val country = locale.country.uppercase(Locale.ROOT)
    return when (lang) {
        "en" -> if (country == "GB") 0x0809u else 0x0409u
        "pl" -> 0x0415u
        "de" -> if (country == "CH") 0x0807u else 0x0407u
        "fr" -> when (country) {
            "CH" -> 0x100Cu
            "BE" -> 0x080Cu
            "CA" -> 0x0C0Cu
            else -> 0x040Cu
        }
        "es" -> 0x040Au
        "it" -> 0x0410u
        "pt" -> if (country == "BR") 0x0416u else 0x0816u
        "ru" -> 0x0419u
        "cs" -> 0x0405u
        "sk" -> 0x041Bu
        "sv" -> 0x041Du
        "nb", "nn", "no" -> 0x0414u
        "da" -> 0x0406u
        "fi" -> 0x040Bu
        "nl" -> if (country == "BE") 0x0813u else 0x0413u
        "tr" -> 0x041Fu
        "hu" -> 0x040Eu
        "uk" -> 0x0422u
        "el" -> 0x0408u
        "he" -> 0x040Du
        "ar" -> 0x0401u
        "ja" -> 0x0411u
        "ko" -> 0x0412u
        "zh" -> 0x0804u
        else -> 0x0409u
    }
}

/**
 * #606: resolve a per-connection `keyboardLayout` tag (stored on
 * `ConnectionProfile`, e.g. `"es-ES"`, `"en-US"`, `"de-DE"`, `"fr-FR"`) to the
 * corresponding Windows KLID. Null, blank, or `"auto"` defers to
 * [keyboardLayoutKlid] on [fallbackLocale].
 */
fun keyboardLayoutKlidForTag(
    layoutTag: String?,
    fallbackLocale: Locale = Locale.getDefault(),
): UInt {
    val normalized = layoutTag?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT).orEmpty()
    if (normalized.isEmpty() || normalized == "auto") {
        return keyboardLayoutKlid(fallbackLocale)
    }
    return when (normalized) {
        "en-us", "us" -> 0x0409u
        "en-gb", "uk", "gb" -> 0x0809u
        "de-de", "de" -> 0x0407u
        "de-ch" -> 0x0807u
        "fr-fr", "fr" -> 0x040Cu
        "fr-be", "be" -> 0x080Cu
        "fr-ch" -> 0x100Cu
        "es-es", "es" -> 0x040Au
        "es-419", "es-mx", "latam" -> 0x080Au
        "it-it", "it" -> 0x0410u
        "pt-pt", "pt" -> 0x0816u
        "pt-br", "br" -> 0x0416u
        "sv-se", "sv" -> 0x041Du
        "fi-fi", "fi" -> 0x040Bu
        "pl-pl", "pl" -> 0x0415u
        else -> keyboardLayoutKlid(Locale.forLanguageTag(layoutTag!!.trim().replace('_', '-')))
    }
}
