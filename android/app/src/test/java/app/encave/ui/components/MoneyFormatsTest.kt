package app.encave.ui.components

import java.math.BigDecimal
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Prices, counts and decimals as French and English readers expect them. */
class MoneyFormatsTest {
    private lateinit var saved: Locale

    @Before
    fun save() {
        saved = Locale.getDefault()
    }

    @After
    fun restore() {
        Locale.setDefault(saved)
    }

    /** No-break and narrow no-break spaces read as spaces. */
    private fun plain(text: String) = text.replace(' ', ' ').replace(' ', ' ')

    private fun money(language: String, amount: String, code: String?): String {
        Locale.setDefault(Locale.forLanguageTag(language))
        return plain(Formats.money(BigDecimal(amount), code))
    }

    @Test
    fun aCodeTypedInLowerCaseIsStillItsCurrency() {
        // A CSV import keeps "eur" as typed: it is still euros, not the device's currency.
        assertEquals("€12.50", money("en-US", "12.5", "eur"))
        assertEquals("€12.50", money("en-US", "12.5", " Eur "))
    }

    @Test
    fun pricesInEnglish() {
        assertEquals("€12.50", money("en-US", "12.5", "EUR"))
        assertEquals("$1,234.50", money("en-US", "1234.5", "USD"))
        // The code before the amount; the space between depends on the platform's locale data.
        assertEquals("CHF45.90", money("en-US", "45.9", "CHF").replace(" ", ""))
        assertEquals("£8.00", money("en-GB", "8", "GBP"))
    }

    @Test
    fun pricesInFrench() {
        assertEquals("12,50 €", money("fr-FR", "12.5", "EUR"))
        assertEquals("1 234,50 €", money("fr-FR", "1234.5", "EUR"))
        assertEquals("45,90 CHF", money("fr-FR", "45.9", "CHF"))
        assertEquals("1 234,50 \$US", money("fr-FR", "1234.5", "USD"))
    }

    @Test
    fun withoutACodeTheLocalesCurrency() {
        assertEquals("€12.50", money("en-IE", "12.5", null))
        assertEquals("12,50 €", money("fr-FR", "12.5", null))
    }

    @Test
    fun countsAndDecimals() {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        assertEquals("1,234", plain(Formats.integer(1234)))
        assertEquals("4.5", plain(Formats.decimal(4.5)))
        assertEquals("4.50", plain(Formats.decimal(4.5, digits = 2)))
        Locale.setDefault(Locale.forLanguageTag("fr-FR"))
        assertEquals("1 234", plain(Formats.integer(1234)))
        assertEquals("4,5", plain(Formats.decimal(4.5)))
        assertEquals("0,3", plain(Formats.decimal(0.26)))
    }
}
