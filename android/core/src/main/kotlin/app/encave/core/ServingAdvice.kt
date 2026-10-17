package app.encave.core

import java.util.Locale

/** How to serve a wine: temperature range (°C) and decanting. */
data class ServingAdvice(val minTemperature: Int, val maxTemperature: Int, val decantMinutes: Int? = null) {
    companion object {
        fun advice(wine: Wine, year: Int): ServingAdvice? {
            val color = wine.color ?: return null
            val text = (listOfNotNull(wine.region, wine.appellation) + wine.grapes)
                .joinToString(" ") { it.lowercase(Locale.ROOT) }
            fun has(vararg words: String) = words.any { text.contains(it) }
            val age = wine.vintage?.let { year - it }

            return when (color) {
                WineColor.Red -> {
                    val light = has("pinot", "gamay", "beaujolais", "bourgogne", "burgundy", "loire", "zweigelt", "frappato")
                    val full = has("cabernet", "syrah", "shiraz", "malbec", "nebbiolo", "tannat", "mourvèdre", "bordeaux", "rhône", "rhone", "barolo")
                    val (low, high) = if (light) 14 to 16 else if (full) 16 to 18 else 15 to 17
                    val decant = if (full && age != null) (if (age < 8) 60 else if (age < 15) 30 else null) else null
                    ServingAdvice(low, high, decant)
                }
                WineColor.White -> {
                    val rich = has("chardonnay", "meursault", "bourgogne", "burgundy", "viognier", "marsanne", "roussanne", "savagnin", "jura")
                    ServingAdvice(if (rich) 10 else 8, if (rich) 13 else 10)
                }
                WineColor.Rose -> ServingAdvice(8, 10)
                WineColor.Sparkling -> {
                    val vintageSparkling = wine.vintage != null
                    ServingAdvice(if (vintageSparkling) 8 else 6, if (vintageSparkling) 10 else 8)
                }
                WineColor.Dessert -> ServingAdvice(8, 11)
                WineColor.Fortified -> {
                    val port = has("porto", "port", "douro", "madeira", "banyuls", "maury")
                    ServingAdvice(if (port) 16 else 10, if (port) 18 else 14)
                }
                WineColor.Orange -> ServingAdvice(12, 14, 30)
            }
        }
    }
}
