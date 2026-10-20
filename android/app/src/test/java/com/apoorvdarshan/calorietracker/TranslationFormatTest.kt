package com.apoorvdarshan.calorietracker

import com.apoorvdarshan.calorietracker.services.voice.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Translated strings must keep the English format arguments, and text that is shown
 * without formatting must not escape its percent signs (#507).
 */
class TranslationFormatTest {
    private val res = RepoFiles.find("src/main/res")

    @Test
    fun translationsKeepTheEnglishFormatArguments() {
        val base = load(File(res, "values"))
        val baseArguments = mutableMapOf<String, MutableSet<Pair<Int, Char>>>()
        for (entry in base) baseArguments.getOrPut(entry.name) { mutableSetOf() } += strictArguments(entry.text)

        val problems = mutableListOf<String>()
        var checked = 0
        for (locale in localeDirectories()) {
            for (entry in load(locale)) {
                val expected = baseArguments[entry.name] ?: continue
                checked++
                val where = "${locale.name}/${entry.file} ${entry.name}${entry.quantity?.let { "[$it]" } ?: ""}"
                if (expected.isEmpty()) {
                    // getString(id) without arguments returns the text as is, so %% shows literally.
                    if ("%%" in entry.text) problems += "$where shows %%: ${entry.text}"
                    continue
                }
                // A stray "% d" or a missing argument would crash or drop the value when formatted.
                val actual = anyArguments(entry.text)
                val ok = when (entry.quantity) {
                    null -> actual == expected
                    // A one, zero or two form may spell the number out instead.
                    "one", "zero", "two" -> expected.containsAll(actual)
                    else -> actual == expected
                }
                if (!ok) problems += "$where has $actual instead of $expected: ${entry.text}"
            }
        }
        assertTrue("only $checked translated strings found", checked > 20_000)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun disclaimerShowsASinglePercentSign() {
        for (directory in listOf(File(res, "values")) + localeDirectories()) {
            val disclaimer = load(directory).single { it.name == "settings_not_medical_body" }.text
            assertTrue("${directory.name}: $disclaimer", "%%" !in disclaimer && ("10%" in disclaimer || "%10" in disclaimer))
        }
    }

    @Test
    fun appNameMatchesCalorieTrackingInEveryLocale() {
        val englishName = load(File(res, "values")).single { it.name == "app_name" }.text
        assertEquals("Calorie Tracking", englishName)

        for (directory in localeDirectories()) {
            val expected = if (directory.name == "values-ar") "تتبّع السعرات الحرارية" else englishName
            val localizedName = load(directory).single { it.name == "app_name" }.text
            assertEquals(directory.name, expected, localizedName)
        }
    }

    private data class Entry(val file: String, val name: String, val quantity: String?, val text: String)

    private fun localeDirectories(): List<File> =
        res.listFiles { file -> file.isDirectory && LOCALE.matches(file.name) }.orEmpty().sortedBy { it.name }
            .also { assertEquals(18, it.size) }

    private fun load(directory: File): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (file in directory.listFiles { file -> file.extension == "xml" }.orEmpty()) {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val children = root.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> entries += Entry(file.name, name, null, element.textContent)
                    "plurals" -> {
                        val items = element.getElementsByTagName("item")
                        for (item in 0 until items.length) {
                            val quantity = (items.item(item) as Element).getAttribute("quantity")
                            entries += Entry(file.name, name, quantity, items.item(item).textContent)
                        }
                    }
                }
            }
        }
        return entries
    }

    private companion object {
        val LOCALE = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")

        /** Format arguments as English writes them: %d, %1\$s, %,d, %.1f. */
        val STRICT = Regex("""%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.\d+)?([sdfxXoc])""")

        /** Anything String.format would read as an argument, including "% d" and "%-n". */
        val ANY = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")

        fun strictArguments(text: String) = arguments(STRICT, text.replace("%%", ""))

        fun anyArguments(text: String) = arguments(ANY, text)

        fun arguments(regex: Regex, text: String): Set<Pair<Int, Char>> {
            var next = 0
            return regex.findAll(text).mapNotNull { match ->
                val conversion = match.groupValues[2].single()
                if (conversion == '%') return@mapNotNull null
                next++
                (match.groupValues[1].toIntOrNull() ?: next) to conversion
            }.toSet()
        }
    }
}
