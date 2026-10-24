package app.encave.ui.settings

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.statistics
import app.encave.ui.components.displayLabel
import java.io.OutputStream
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import java.util.UUID

/**
 * A printable inventory of the cellar (PDF, A4): every wine with its bottles, where they
 * are and what they cost, the same columns as on Apple. For insurance, a move, or the
 * family. Made on the device from what is in the cellar; nothing is looked up.
 */
object InventoryPdf {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 40f
    const val ROWS_PER_PAGE = 26

    /** The first page also has the title, the date and the value: fewer rows fit above the footer. */
    const val ROWS_ON_FIRST_PAGE = 24
    private const val ROW_HEIGHT = 26f

    /**
     * Rows split into pages by their [height]: room for [ROWS_ON_FIRST_PAGE] rows of the usual
     * height on the first, [ROWS_PER_PAGE] on the others. A taller row (many places) takes more.
     */
    fun <T> paged(rows: List<T>, height: (T) -> Float = { ROW_HEIGHT }): List<List<T>> {
        val pages = mutableListOf(mutableListOf<T>())
        var used = 0f
        for (row in rows) {
            val available = (if (pages.size == 1) ROWS_ON_FIRST_PAGE else ROWS_PER_PAGE) * ROW_HEIGHT
            val needed = height(row)
            if (used + needed > available + 0.5f && pages.last().isNotEmpty()) {
                pages += mutableListOf<T>()
                used = 0f
            }
            pages.last() += row
            used += needed
        }
        return pages
    }

    /**
     * The bottles' places, one line per storage in the order of the storages ("Main rack: A1, A2, A3"),
     * the same place once with its count ("Bin A1 ×2"); then places noted by hand, with their count.
     */
    fun places(cellar: Cellar, bottles: List<Bottle>, resources: Resources): List<String> {
        fun counted(labels: List<String>) = labels.groupingBy { it }.eachCount().map { (label, count) -> if (count > 1) "$label ×$count" else label }
        val lines = cellar.racks.mapNotNull { rack ->
            val slots = bottles.mapNotNull { it.slot }.filter { it.rackId == rack.id }
                .sortedWith(compareBy({ it.row }, { it.column }, { it.depth }))
            if (slots.isEmpty()) return@mapNotNull null
            val labels = counted(slots.map { rack.displayLabel(it, resources) })
            // A fridge's places have commas of their own.
            val separator = if (labels.any { ',' in it }) "; " else ", "
            resources.getString(R.string.pdf_rack_places, rack.name, labels.joinToString(separator))
        }
        val elsewhere = bottles.filter { bottle -> bottle.slot?.let { cellar.rack(it.rackId) } == null }
            .mapNotNull { it.storageNote?.trim()?.takeIf(String::isNotEmpty) }
        return lines + counted(elsewhere).sorted()
    }

    data class Row(
        val id: UUID,
        val wine: String,
        val producer: String,
        val region: String,
        val bottles: Int,
        val places: String,
        val value: String,
    )

    private fun money(amount: BigDecimal, code: String, locale: Locale): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = Currency.getInstance(code) }
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }.format(amount)

    private fun total(byCurrency: Map<String, BigDecimal>, locale: Locale) =
        byCurrency.keys.sorted().joinToString(" + ") { money(byCurrency.getValue(it), it, locale) }

    fun rows(cellar: Cellar, resources: Resources, locale: Locale = Locale.getDefault()): List<Row> =
        cellar.groups().map { group ->
            val wine = group.wine
            val places = places(cellar, group.bottles, resources)
            val totals = mutableMapOf<String, BigDecimal>()
            group.bottles.forEach { bottle ->
                val price = bottle.purchasePrice ?: return@forEach
                val code = bottle.currency ?: cellar.settings.currency
                totals[code] = (totals[code] ?: BigDecimal.ZERO) + price
            }
            Row(
                id = wine.id, wine = wine.displayName, producer = wine.producer.orEmpty(),
                region = listOfNotNull(wine.appellation ?: wine.region, wine.country).joinToString(", "),
                bottles = group.count, places = places.joinToString("\n"), value = total(totals, locale),
            )
        }

    /** Writes the PDF to [out]; returns the number of pages. */
    fun write(cellar: Cellar, resources: Resources, year: Int, out: OutputStream, today: LocalDate = LocalDate.now()): Int {
        val pages = Pages(cellar, resources, year, today)
        val document = PdfDocument()
        try {
            for (index in 0 until pages.count) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, index + 1).create())
                pages.draw(page.canvas, index)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return pages.count
    }

    /** Draws page [index] (from 0) on a canvas of 595 × 842 points, as in the PDF. */
    fun drawPage(cellar: Cellar, resources: Resources, year: Int, index: Int, canvas: android.graphics.Canvas, today: LocalDate = LocalDate.now()) =
        Pages(cellar, resources, year, today).draw(canvas, index)

    private class Pages(cellar: Cellar, private val resources: Resources, year: Int, private val today: LocalDate) {
        private val locale = Locale.getDefault()
        private val rows = rows(cellar, resources, locale)
        private val stats = cellar.statistics(year)
        private val value = total(stats.valueByCurrency, locale)

        // Printed: black text on the white page, whatever the app's theme.
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 8.5f }
        private val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        private val secondary = TextPaint(text).apply { color = Color.DKGRAY; textSize = 8f }
        private val title = TextPaint(text).apply { textSize = 22f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        private val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        private val rule = Paint().apply { color = Color.BLACK; strokeWidth = 1f }

        // Columns: wine, region, bottles, location (the rest), purchase value.
        private val wineX = MARGIN
        private val regionX = wineX + 170 + 8
        private val bottlesX = regionX + 110 + 8
        private val placesX = bottlesX + 40 + 8
        private val valueRight = WIDTH - MARGIN
        private val placesWidth = valueRight - 70 - 8 - placesX

        // A row grows with its places, one line per storage.
        private fun height(row: Row) = maxOf(ROW_HEIGHT, layout(row.places, text, placesWidth, PLACES_MAX_LINES).height + 6f)
        private val pages = paged(rows, ::height)
        val count: Int get() = pages.size

        fun draw(canvas: android.graphics.Canvas, index: Int) {
            canvas.drawColor(Color.WHITE)
            var y = MARGIN
            if (index == 0) {
                canvas.drawText(resources.getString(R.string.pdf_title), MARGIN, y + 20, title)
                y += 34
                val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(today)
                canvas.drawText(resources.getQuantityString(R.plurals.pdf_subtitle, stats.bottleCount, date, stats.bottleCount), MARGIN, y + 10, text.withSize(11f))
                y += 16
                if (value.isNotEmpty()) {
                    canvas.drawText(resources.getString(R.string.pdf_value, value), MARGIN, y + 10, text.withSize(11f))
                    y += 16
                }
                y += 14
            }
            // Header row.
            y += 6
            canvas.drawText(resources.getString(R.string.pdf_wine), wineX, y + 9, bold)
            canvas.drawText(resources.getString(R.string.field_region), regionX, y + 9, bold)
            drawRight(canvas, resources.getString(R.string.pdf_bottles), bottlesX + 40, y + 9, bold)
            canvas.drawText(resources.getString(R.string.field_location), placesX, y + 9, bold)
            drawRight(canvas, resources.getString(R.string.pdf_purchase_value), valueRight, y + 9, bold)
            y += 15
            canvas.drawLine(MARGIN, y, valueRight, y, rule)
            pages[index].forEach { row ->
                val top = y + 3
                canvas.drawText(ellipsize(row.wine, bold, 170f), wineX, top + 9, bold)
                if (row.producer.isNotEmpty()) canvas.drawText(ellipsize(row.producer, secondary, 170f), wineX, top + 19, secondary)
                drawBlock(canvas, row.region, text, regionX, top, 110f)
                drawRight(canvas, row.bottles.toString(), bottlesX + 40, top + 9, text)
                drawBlock(canvas, row.places, text, placesX, top, placesWidth, PLACES_MAX_LINES)
                drawRight(canvas, row.value, valueRight, top + 9, text)
                y += height(row)
                canvas.drawLine(MARGIN, y, valueRight, y, line)
            }
            val footer = HEIGHT - MARGIN
            canvas.drawText(resources.getString(R.string.pdf_made_with), MARGIN, footer, secondary)
            drawRight(canvas, resources.getString(R.string.pdf_page, index + 1, pages.size), valueRight, footer, secondary)
        }
    }

    private fun TextPaint.withSize(size: Float) = TextPaint(this).apply { textSize = size }

    private fun ellipsize(value: String, paint: TextPaint, width: Float) =
        TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString()

    private fun drawRight(canvas: android.graphics.Canvas, value: String, right: Float, baseline: Float, paint: TextPaint) {
        canvas.drawText(value, right - paint.measureText(value), baseline, paint)
    }

    /** A row's places take at most this many lines, so a row always fits on a page. */
    private const val PLACES_MAX_LINES = 20

    private fun layout(value: String, paint: TextPaint, width: Float, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(value, 0, value.length, paint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    /** Up to [maxLines] lines, ellipsized. */
    private fun drawBlock(canvas: android.graphics.Canvas, value: String, paint: TextPaint, x: Float, top: Float, width: Float, maxLines: Int = 2) {
        if (value.isEmpty()) return
        val layout = layout(value, paint, width, maxLines)
        canvas.save()
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restore()
    }
}
