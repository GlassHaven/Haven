#!/usr/bin/env python3
"""Backfill missing translations for editor / imagetools / termlib modules.

Run from repo root: python3 scripts/i18n_backfill.py
"""
import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Locales we ship (matches existing values-* directories).
LOCALES = ["zh", "es", "hi", "ar", "pt", "bn", "ru", "ja", "fr", "de"]

# Technical / brand terms preserved as-is across all locales.
KEEP = {"⋮", "Regex"}

# Translation tables. Each entry: { locale: translated_string } for one source key.
# When a locale is omitted for a given key, the English source is used (preserves
# brand names, format strings like "Ln %1$d, Col %2$d", etc.).
EDITOR = {
    "editor_title": {
        "zh": "编辑器", "es": "Editor", "hi": "एडिटर", "ar": "المحرر",
        "pt": "Editor", "bn": "এডিটর", "ru": "Редактор", "ja": "エディター",
        "fr": "Éditeur", "de": "Editor",
    },
    "editor_loading": {
        "zh": "正在加载文件…", "es": "Cargando archivo…", "hi": "फ़ाइल लोड हो रही है…",
        "ar": "جارٍ تحميل الملف…", "pt": "Carregando arquivo…", "bn": "ফাইল লোড হচ্ছে…",
        "ru": "Загрузка файла…", "ja": "ファイルを読み込み中…", "fr": "Chargement du fichier…",
        "de": "Datei wird geladen…",
    },
    "editor_failed": {
        "zh": "加载失败：%s", "es": "Error al cargar: %s", "hi": "लोड करने में विफल: %s",
        "ar": "فشل التحميل: %s", "pt": "Falha ao carregar: %s", "bn": "লোড করতে ব্যর্থ: %s",
        "ru": "Не удалось загрузить: %s", "ja": "読み込みに失敗: %s",
        "fr": "Échec du chargement : %s", "de": "Laden fehlgeschlagen: %s",
    },
    "editor_line": {
        "zh": "第 %1$d 行，第 %2$d 列", "es": "Ln %1$d, Col %2$d", "hi": "पं %1$d, स्तं %2$d",
        "ar": "س %1$d، ع %2$d", "pt": "Lin %1$d, Col %2$d", "bn": "লা %1$d, কলা %2$d",
        "ru": "Стр. %1$d, Стлб. %2$d", "ja": "%1$d 行、%2$d 列",
        "fr": "Lig. %1$d, Col. %2$d", "de": "Z %1$d, Sp %2$d",
    },
    "editor_word_wrap": {
        "zh": "自动换行", "es": "Ajuste de línea", "hi": "वर्ड रैप",
        "ar": "التفاف الكلمات", "pt": "Quebra de linha", "bn": "ওয়ার্ড র‍্যাপ",
        "ru": "Перенос слов", "ja": "折り返し", "fr": "Retour à la ligne",
        "de": "Zeilenumbruch",
    },
    "editor_open_in_editor": {
        "zh": "在编辑器中打开", "es": "Abrir en el editor", "hi": "एडिटर में खोलें",
        "ar": "فتح في المحرر", "pt": "Abrir no editor", "bn": "এডিটরে খুলুন",
        "ru": "Открыть в редакторе", "ja": "エディターで開く",
        "fr": "Ouvrir dans l’éditeur", "de": "Im Editor öffnen",
    },
    "editor_save": {
        "zh": "保存", "es": "Guardar", "hi": "सहेजें", "ar": "حفظ",
        "pt": "Salvar", "bn": "সংরক্ষণ", "ru": "Сохранить", "ja": "保存",
        "fr": "Enregistrer", "de": "Speichern",
    },
    "editor_saved": {
        "zh": "已保存 %s", "es": "Guardado %s", "hi": "%s सहेजा गया",
        "ar": "تم حفظ %s", "pt": "%s salvo", "bn": "%s সংরক্ষিত হয়েছে",
        "ru": "%s сохранено", "ja": "%s を保存しました",
        "fr": "%s enregistré", "de": "%s gespeichert",
    },
    "editor_save_failed": {
        "zh": "保存失败：%s", "es": "Error al guardar: %s", "hi": "सहेजने में विफल: %s",
        "ar": "فشل الحفظ: %s", "pt": "Falha ao salvar: %s", "bn": "সংরক্ষণ ব্যর্থ: %s",
        "ru": "Не удалось сохранить: %s", "ja": "保存に失敗: %s",
        "fr": "Échec de l’enregistrement : %s", "de": "Speichern fehlgeschlagen: %s",
    },
    "editor_undo": {
        "zh": "撤销", "es": "Deshacer", "hi": "पूर्ववत् करें", "ar": "تراجع",
        "pt": "Desfazer", "bn": "পূর্বাবস্থায় ফেরান", "ru": "Отменить", "ja": "元に戻す",
        "fr": "Annuler", "de": "Rückgängig",
    },
    "editor_redo": {
        "zh": "重做", "es": "Rehacer", "hi": "फिर से करें", "ar": "إعادة",
        "pt": "Refazer", "bn": "পুনরায় করুন", "ru": "Повторить", "ja": "やり直し",
        "fr": "Rétablir", "de": "Wiederherstellen",
    },
    "editor_find": {
        "zh": "查找", "es": "Buscar", "hi": "ढूँढें", "ar": "بحث",
        "pt": "Buscar", "bn": "খুঁজুন", "ru": "Найти", "ja": "検索",
        "fr": "Rechercher", "de": "Suchen",
    },
    "editor_find_prev": {
        "zh": "上一个匹配", "es": "Coincidencia anterior", "hi": "पिछला मिलान",
        "ar": "المطابقة السابقة", "pt": "Correspondência anterior", "bn": "পূর্ববর্তী মিল",
        "ru": "Предыдущее совпадение", "ja": "前の一致",
        "fr": "Correspondance précédente", "de": "Vorherige Übereinstimmung",
    },
    "editor_find_next": {
        "zh": "下一个匹配", "es": "Coincidencia siguiente", "hi": "अगला मिलान",
        "ar": "المطابقة التالية", "pt": "Próxima correspondência", "bn": "পরবর্তী মিল",
        "ru": "Следующее совпадение", "ja": "次の一致",
        "fr": "Correspondance suivante", "de": "Nächste Übereinstimmung",
    },
    "editor_close_search": {
        "zh": "关闭搜索", "es": "Cerrar búsqueda", "hi": "खोज बंद करें",
        "ar": "إغلاق البحث", "pt": "Fechar busca", "bn": "অনুসন্ধান বন্ধ করুন",
        "ru": "Закрыть поиск", "ja": "検索を閉じる",
        "fr": "Fermer la recherche", "de": "Suche schließen",
    },
    "editor_regex": {},  # Regex preserved
    "editor_replace": {
        "zh": "替换", "es": "Reemplazar", "hi": "बदलें", "ar": "استبدال",
        "pt": "Substituir", "bn": "প্রতিস্থাপন", "ru": "Заменить", "ja": "置換",
        "fr": "Remplacer", "de": "Ersetzen",
    },
    "editor_replace_one": {
        "zh": "替换", "es": "Reemplazar", "hi": "बदलें", "ar": "استبدال",
        "pt": "Substituir", "bn": "প্রতিস্থাপন", "ru": "Заменить", "ja": "置換",
        "fr": "Remplacer", "de": "Ersetzen",
    },
    "editor_replace_all": {
        "zh": "全部", "es": "Todo", "hi": "सभी", "ar": "الكل",
        "pt": "Tudo", "bn": "সব", "ru": "Все", "ja": "すべて",
        "fr": "Tout", "de": "Alle",
    },
}

IMAGETOOLS = {
    "imagetools_title": {
        "zh": "图像工具", "es": "Herramientas de imagen", "hi": "इमेज टूल्स",
        "ar": "أدوات الصور", "pt": "Ferramentas de imagem", "bn": "ইমেজ টুলস",
        "ru": "Инструменты изображения", "ja": "画像ツール",
        "fr": "Outils d’image", "de": "Bildwerkzeuge",
    },
    "imagetools_perspective": {
        "zh": "透视校正", "es": "Perspectiva", "hi": "परिप्रेक्ष्य",
        "ar": "المنظور", "pt": "Perspectiva", "bn": "পরিপ্রেক্ষিত",
        "ru": "Перспектива", "ja": "遠近補正", "fr": "Perspective", "de": "Perspektive",
    },
    "imagetools_crop": {
        "zh": "裁剪", "es": "Recortar", "hi": "क्रॉप", "ar": "قص",
        "pt": "Recortar", "bn": "ক্রপ", "ru": "Обрезать", "ja": "切り抜き",
        "fr": "Recadrer", "de": "Zuschneiden",
    },
    "imagetools_rotate": {
        "zh": "旋转", "es": "Rotar", "hi": "घुमाएँ", "ar": "تدوير",
        "pt": "Girar", "bn": "ঘোরান", "ru": "Повернуть", "ja": "回転",
        "fr": "Pivoter", "de": "Drehen",
    },
    "imagetools_apply": {
        "zh": "应用", "es": "Aplicar", "hi": "लागू करें", "ar": "تطبيق",
        "pt": "Aplicar", "bn": "প্রয়োগ করুন", "ru": "Применить", "ja": "適用",
        "fr": "Appliquer", "de": "Anwenden",
    },
    "imagetools_save": {
        "zh": "保存", "es": "Guardar", "hi": "सहेजें", "ar": "حفظ",
        "pt": "Salvar", "bn": "সংরক্ষণ", "ru": "Сохранить", "ja": "保存",
        "fr": "Enregistrer", "de": "Speichern",
    },
    "imagetools_reset": {
        "zh": "重置", "es": "Restablecer", "hi": "रीसेट", "ar": "إعادة تعيين",
        "pt": "Redefinir", "bn": "রিসেট", "ru": "Сбросить", "ja": "リセット",
        "fr": "Réinitialiser", "de": "Zurücksetzen",
    },
    "imagetools_original": {
        "zh": "原图", "es": "Original", "hi": "मूल", "ar": "الأصل",
        "pt": "Original", "bn": "মূল", "ru": "Оригинал", "ja": "元の画像",
        "fr": "Original", "de": "Original",
    },
    "imagetools_result": {
        "zh": "结果", "es": "Resultado", "hi": "परिणाम", "ar": "النتيجة",
        "pt": "Resultado", "bn": "ফলাফল", "ru": "Результат", "ja": "結果",
        "fr": "Résultat", "de": "Ergebnis",
    },
    "imagetools_processing": {
        "zh": "正在处理…", "es": "Procesando…", "hi": "प्रसंस्करण…",
        "ar": "جارٍ المعالجة…", "pt": "Processando…", "bn": "প্রসেস হচ্ছে…",
        "ru": "Обработка…", "ja": "処理中…", "fr": "Traitement…", "de": "Wird verarbeitet…",
    },
    "imagetools_failed": {
        "zh": "失败：%s", "es": "Error: %s", "hi": "विफल: %s",
        "ar": "فشل: %s", "pt": "Falha: %s", "bn": "ব্যর্থ: %s",
        "ru": "Ошибка: %s", "ja": "失敗: %s", "fr": "Échec : %s", "de": "Fehler: %s",
    },
    "imagetools_saved": {
        "zh": "已保存 %s", "es": "Guardado %s", "hi": "%s सहेजा गया",
        "ar": "تم حفظ %s", "pt": "%s salvo", "bn": "%s সংরক্ষিত হয়েছে",
        "ru": "%s сохранено", "ja": "%s を保存しました",
        "fr": "%s enregistré", "de": "%s gespeichert",
    },
    "imagetools_open": {
        "zh": "图像工具", "es": "Herramientas de imagen", "hi": "इमेज टूल्स",
        "ar": "أدوات الصور", "pt": "Ferramentas de imagem", "bn": "ইমেজ টুলস",
        "ru": "Инструменты изображения", "ja": "画像ツール",
        "fr": "Outils d’image", "de": "Bildwerkzeuge",
    },
    "imagetools_view": {
        "zh": "查看", "es": "Ver", "hi": "देखें", "ar": "عرض",
        "pt": "Ver", "bn": "দেখুন", "ru": "Просмотр", "ja": "表示",
        "fr": "Voir", "de": "Ansehen",
    },
    "imagetools_loading": {
        "zh": "正在加载图像…", "es": "Cargando imagen…", "hi": "इमेज लोड हो रही है…",
        "ar": "جارٍ تحميل الصورة…", "pt": "Carregando imagem…", "bn": "ইমেজ লোড হচ্ছে…",
        "ru": "Загрузка изображения…", "ja": "画像を読み込み中…",
        "fr": "Chargement de l’image…", "de": "Bild wird geladen…",
    },
}

TERMLIB = {
    "terminal_selection_copy": {
        "zh": "复制", "es": "Copiar", "hi": "कॉपी करें", "ar": "نسخ",
        "pt": "Copiar", "bn": "কপি করুন", "ru": "Копировать", "ja": "コピー",
        "fr": "Copier", "de": "Kopieren",
    },
    "terminal_selection_more_options": {},  # ⋮ preserved
    "terminal_selection_more_options_description": {
        "zh": "更多选项", "es": "Más opciones", "hi": "अधिक विकल्प",
        "ar": "خيارات إضافية", "pt": "Mais opções", "bn": "আরও বিকল্প",
        "ru": "Ещё", "ja": "その他のオプション", "fr": "Plus d’options",
        "de": "Weitere Optionen",
    },
    "terminal_selection_select_all": {
        "zh": "全选", "es": "Seleccionar todo", "hi": "सभी चुनें",
        "ar": "تحديد الكل", "pt": "Selecionar tudo", "bn": "সব নির্বাচন করুন",
        "ru": "Выбрать всё", "ja": "すべて選択", "fr": "Tout sélectionner",
        "de": "Alles auswählen",
    },
    "terminal_selection_share": {
        "zh": "分享", "es": "Compartir", "hi": "साझा करें", "ar": "مشاركة",
        "pt": "Compartilhar", "bn": "শেয়ার করুন", "ru": "Поделиться",
        "ja": "共有", "fr": "Partager", "de": "Teilen",
    },
    "terminal_selection_paste": {
        "zh": "粘贴", "es": "Pegar", "hi": "पेस्ट करें", "ar": "لصق",
        "pt": "Colar", "bn": "পেস্ট করুন", "ru": "Вставить", "ja": "貼り付け",
        "fr": "Coller", "de": "Einfügen",
    },
    "terminal_selection_mode_character": {
        "zh": "模式：字符", "es": "Modo: Carácter", "hi": "मोड: वर्ण",
        "ar": "الوضع: حرف", "pt": "Modo: Caractere", "bn": "মোড: অক্ষর",
        "ru": "Режим: Символ", "ja": "モード：文字",
        "fr": "Mode : caractère", "de": "Modus: Zeichen",
    },
    "terminal_selection_mode_word": {
        "zh": "模式：单词", "es": "Modo: Palabra", "hi": "मोड: शब्द",
        "ar": "الوضع: كلمة", "pt": "Modo: Palavra", "bn": "মোড: শব্দ",
        "ru": "Режим: Слово", "ja": "モード：単語",
        "fr": "Mode : mot", "de": "Modus: Wort",
    },
    "terminal_selection_mode_line": {
        "zh": "模式：行", "es": "Modo: Línea", "hi": "मोड: पंक्ति",
        "ar": "الوضع: سطر", "pt": "Modo: Linha", "bn": "মোড: লাইন",
        "ru": "Режим: Строка", "ja": "モード：行",
        "fr": "Mode : ligne", "de": "Modus: Zeile",
    },
}


def read_source(module_path: str):
    """Return ordered list of (name, value) from the source values/strings.xml."""
    import re
    src = (REPO / module_path / "src/main/res/values/strings.xml").read_text()
    return re.findall(r'<string\s+name="([^"]+)"\s*>(.*?)</string>', src, re.DOTALL)


def xml_escape(value: str) -> str:
    # Strings already use \n / %s / etc. Don't double-escape & if it's already &amp;.
    return value


def write_locale(module_path: str, locale: str, table, source_entries):
    out = REPO / module_path / "src/main/res" / f"values-{locale}/strings.xml"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, en_value in source_entries:
        translations = table.get(name, {})
        if name in {"editor_regex", "terminal_selection_more_options"}:
            value = en_value
        else:
            value = translations.get(locale, en_value)
        lines.append(f'    <string name="{name}">{value}</string>')
    lines.append("</resources>")
    lines.append("")
    out.write_text("\n".join(lines))


def main():
    targets = [
        ("feature/editor", EDITOR),
        ("feature/imagetools", IMAGETOOLS),
        ("termlib/lib", TERMLIB),
    ]
    for module, table in targets:
        source_entries = read_source(module)
        for locale in LOCALES:
            write_locale(module, locale, table, source_entries)
            print(f"  wrote {module} values-{locale}/strings.xml ({len(source_entries)} strings)")


if __name__ == "__main__":
    main()
