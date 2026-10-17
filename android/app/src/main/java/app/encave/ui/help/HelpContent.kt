package app.encave.ui.help

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Hive
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import app.encave.R

/**
 * Topics of the in-app help, the same as on Apple. The keyboard-shortcuts topic is
 * left out: the Android app has no keyboard shortcuts.
 */
enum class HelpTopic { Start, Adding, Scanning, Racks, Tonight, Drinking, Discoveries, Ai, Data, Privacy }

/** A paragraph of help with an optional heading. Text can use **bold** and `code`. */
data class HelpSection(@StringRes val body: Int, @StringRes val title: Int? = null, val icon: ImageVector? = null)

data class HelpArticle(
    val topic: HelpTopic,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector,
    val sections: List<HelpSection>,
) {
    companion object {
        val all: List<HelpArticle> = listOf(
            HelpArticle(
                HelpTopic.Start, R.string.help_start_title, R.string.help_start_summary, Icons.Outlined.AutoAwesome,
                listOf(
                    HelpSection(R.string.help_start_cellar, R.string.nav_cellar, Icons.Outlined.WineBar),
                    HelpSection(R.string.help_start_racks, R.string.nav_racks, Icons.Outlined.GridOn),
                    HelpSection(R.string.help_start_tonight, R.string.nav_tonight, Icons.Outlined.Bedtime),
                    HelpSection(R.string.help_start_journal, R.string.nav_journal, Icons.Outlined.AutoStories),
                    HelpSection(R.string.help_start_try, R.string.help_start_try_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Adding, R.string.help_adding_title, R.string.help_adding_summary, Icons.Outlined.AddCircleOutline,
                listOf(
                    HelpSection(R.string.help_adding_existing, R.string.help_adding_existing_title),
                    HelpSection(R.string.help_adding_wine, R.string.add_section_wine),
                    HelpSection(R.string.help_adding_style, R.string.help_adding_style_title),
                    HelpSection(R.string.help_adding_window, R.string.detail_window),
                    HelpSection(R.string.help_adding_quantity, R.string.help_adding_quantity_title),
                    HelpSection(R.string.help_adding_purchase, R.string.help_adding_purchase_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Scanning, R.string.help_scanning_title, R.string.help_scanning_summary, Icons.Outlined.CameraAlt,
                listOf(
                    HelpSection(R.string.help_scanning_device, R.string.help_scanning_device_title),
                    HelpSection(R.string.help_scanning_ai, R.string.help_scanning_ai_title),
                    HelpSection(R.string.help_scanning_typed, R.string.help_scanning_typed_title),
                    HelpSection(R.string.help_scanning_photo, R.string.help_scanning_photo_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Racks, R.string.help_racks_title, R.string.help_racks_summary, Icons.Outlined.GridOn,
                listOf(
                    HelpSection(R.string.help_racks_grid, R.string.kind_grid, Icons.Outlined.GridOn),
                    HelpSection(R.string.help_racks_staggered, R.string.kind_staggered, Icons.Outlined.Hive),
                    HelpSection(R.string.help_racks_shelves, R.string.kind_shelves, Icons.Outlined.Kitchen),
                    HelpSection(R.string.help_racks_bins, R.string.kind_bins, Icons.Outlined.Diamond),
                    HelpSection(R.string.help_racks_places, R.string.help_racks_places_title),
                    HelpSection(R.string.help_racks_finding, R.string.help_racks_finding_title, Icons.Outlined.Search),
                    HelpSection(R.string.help_racks_zooming, R.string.help_racks_zooming_title, Icons.Outlined.ZoomIn),
                    HelpSection(R.string.help_racks_moving, R.string.help_racks_moving_title),
                    HelpSection(R.string.help_racks_irregular, R.string.help_racks_irregular_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Tonight, R.string.help_tonight_title, R.string.help_tonight_summary, Icons.Outlined.Bedtime,
                listOf(
                    HelpSection(R.string.help_tonight_maturity, R.string.help_tonight_maturity_title),
                    HelpSection(R.string.help_tonight_suggestions, R.string.help_tonight_suggestions_title),
                    HelpSection(R.string.help_tonight_serving, R.string.detail_serving),
                ),
            ),
            HelpArticle(
                HelpTopic.Drinking, R.string.help_drinking_title, R.string.help_drinking_summary, Icons.Outlined.LocalBar,
                listOf(
                    HelpSection(R.string.help_drinking_drink, R.string.action_drink),
                    HelpSection(R.string.help_drinking_note, R.string.help_drinking_note_title),
                    HelpSection(R.string.help_drinking_gone, R.string.help_drinking_gone_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Discoveries, R.string.help_discoveries_title, R.string.help_discoveries_summary, Icons.Outlined.ShoppingCart,
                listOf(
                    HelpSection(R.string.help_discoveries_remember, R.string.discovery_new),
                    HelpSection(R.string.help_discoveries_tags, R.string.discovery_tags),
                    HelpSection(R.string.help_discoveries_list, R.string.discovery_shopping_list),
                    HelpSection(R.string.help_discoveries_not_cellar, R.string.help_discoveries_not_cellar_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Ai, R.string.help_ai_title, R.string.help_ai_summary, Icons.Outlined.Memory,
                listOf(
                    HelpSection(R.string.help_ai_provider, R.string.help_ai_provider_title),
                    HelpSection(R.string.help_ai_key, R.string.help_ai_key_title),
                    HelpSection(R.string.help_ai_check, R.string.help_ai_check_title),
                    HelpSection(R.string.help_ai_local, R.string.help_ai_local_title),
                    HelpSection(R.string.help_ai_url, R.string.help_ai_url_title),
                    HelpSection(R.string.help_ai_sent, R.string.help_ai_sent_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Data, R.string.help_data_title, R.string.help_data_summary, Icons.Outlined.SdStorage,
                listOf(
                    HelpSection(R.string.help_data_backup, R.string.help_data_backup_title),
                    HelpSection(R.string.help_data_csv, R.string.help_data_csv_title),
                    HelpSection(R.string.help_data_photos, R.string.help_data_photos_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Privacy, R.string.help_privacy_title, R.string.help_privacy_summary, Icons.Outlined.Lock,
                listOf(
                    HelpSection(R.string.help_privacy_1),
                    HelpSection(R.string.help_privacy_2),
                    HelpSection(R.string.help_privacy_3),
                    HelpSection(R.string.help_privacy_4),
                ),
            ),
        )

        fun of(topic: HelpTopic): HelpArticle = all.first { it.topic == topic }
    }
}
