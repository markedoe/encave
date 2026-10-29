package app.encave.ui.cellar

import android.content.res.Resources
import app.encave.R
import app.encave.core.Vocabulary
import app.encave.core.Wine
import app.encave.ui.components.VocabularyLabels
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** "13.5%" or "13,5 %": a share or the alcohol, as written in [locale]. */
internal fun percentText(value: BigDecimal, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
    }.format(value.movePointLeft(2))

/**
 * The details under "More details" that the wine has, in the form's order, as a label and
 * its value in the person's language: alcohol, classification, lieu-dit, dosage or
 * sweetness, grape shares, ageing, farming (and certified) and closure.
 */
internal fun wineDetailRows(wine: Wine, resources: Resources, locale: Locale = Locale.getDefault()): List<Pair<Int, String>> {
    fun label(kind: Vocabulary.Kind, id: String) = VocabularyLabels.label(resources, kind, id)
    val shares = wine.grapes.mapNotNull { grape ->
        wine.share(grape)?.let { resources.getString(R.string.detail_grape_share, grape, percentText(BigDecimal(it), locale)) }
    }
    return listOfNotNull(
        wine.abv?.let { R.string.field_abv to percentText(it, locale) },
        wine.classification?.takeIf { it.isNotBlank() }?.let { R.string.field_classification to it },
        wine.lieuDit?.takeIf { it.isNotBlank() }?.let { R.string.field_lieu_dit to it },
        wine.dosage?.let { R.string.field_dosage to label(Vocabulary.Kind.Dosage, it) },
        wine.sweetness?.let { R.string.field_sweetness to label(Vocabulary.Kind.Sweetness, it) },
        shares.takeIf { it.isNotEmpty() }?.let { R.string.field_grape_shares to it.joinToString(", ") },
        wine.ageing?.takeIf { it.isNotEmpty() }?.let { ids -> R.string.field_ageing to ids.joinToString(", ") { label(Vocabulary.Kind.Ageing, it) } },
        wine.farming?.let { id ->
            val name = label(Vocabulary.Kind.Farming, id)
            R.string.field_farming to if (wine.farmingCertified == true) resources.getString(R.string.detail_farming_certified, name) else name
        },
        wine.closure?.let { R.string.field_closure to label(Vocabulary.Kind.Closure, it) },
    )
}
