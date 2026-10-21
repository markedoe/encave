package app.encave.ui.help

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Hive
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Science
import androidx.compose.ui.graphics.vector.ImageVector
import app.encave.R
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FilterList

/**
 * Topics of the in-app help, the same as on Apple. The keyboard-shortcuts topic is
 * left out: the Android app has no keyboard shortcuts.
 */
enum class HelpTopic { Start, Adding, Scanning, Racks, Tonight, Drinking, Journal, Devices, Sync, Ai, WebSearch, Data, Privacy }

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
                    HelpSection(R.string.help_start_beta, R.string.help_start_beta_title, Icons.Outlined.Science),
                    HelpSection(R.string.help_start_cellar, R.string.nav_cellar, Icons.Outlined.WineBar),
                    HelpSection(R.string.help_start_racks, R.string.nav_racks, Icons.Outlined.GridOn),
                    HelpSection(R.string.help_start_tonight, R.string.nav_tonight, Icons.Outlined.Bedtime),
                    HelpSection(R.string.help_start_journal, R.string.nav_journal, Icons.Outlined.AutoStories),
                    HelpSection(R.string.help_start_wine, R.string.help_start_wine_title, Icons.Outlined.TouchApp),
                    HelpSection(R.string.help_start_table, R.string.help_start_table_title, Icons.Outlined.TableChart),
                    HelpSection(R.string.help_start_saved, R.string.help_start_saved_title, Icons.Outlined.BookmarkBorder),
                    HelpSection(R.string.help_start_shortcuts, R.string.help_start_shortcuts_title),
                    HelpSection(R.string.help_start_widgets, R.string.help_start_widgets_title, Icons.Outlined.Widgets),
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
                    HelpSection(R.string.help_adding_tags, R.string.field_tags, Icons.Outlined.Tag),
                    HelpSection(R.string.help_adding_orders, R.string.help_adding_orders_title, Icons.Outlined.LocalShipping),
                    HelpSection(R.string.help_adding_documents, R.string.help_adding_documents_title, Icons.Outlined.AttachFile),
                ),
            ),
            HelpArticle(
                HelpTopic.Scanning, R.string.help_scanning_title, R.string.help_scanning_summary, Icons.Outlined.CameraAlt,
                listOf(
                    HelpSection(R.string.help_scanning_device, R.string.help_scanning_device_title),
                    HelpSection(R.string.help_scanning_ai, R.string.help_scanning_ai_title),
                    HelpSection(R.string.help_scanning_typed, R.string.help_scanning_typed_title),
                    HelpSection(R.string.help_scanning_photo, R.string.help_scanning_photo_title),
                    HelpSection(R.string.help_scanning_barcodes, R.string.help_scanning_barcodes_title, Icons.Outlined.QrCodeScanner),
                ),
            ),
            HelpArticle(
                HelpTopic.Racks, R.string.help_racks_title, R.string.help_racks_summary, Icons.Outlined.GridOn,
                listOf(
                    HelpSection(R.string.help_racks_grid, R.string.kind_grid, Icons.Outlined.GridOn),
                    HelpSection(R.string.help_racks_staggered, R.string.kind_staggered, Icons.Outlined.Hive),
                    HelpSection(R.string.help_racks_layers, R.string.help_racks_layers_title, Icons.Outlined.Hive),
                    HelpSection(R.string.help_racks_shelves, R.string.kind_shelves, Icons.Outlined.Kitchen),
                    HelpSection(R.string.help_racks_bins, R.string.kind_bins, Icons.Outlined.Diamond),
                    HelpSection(R.string.help_racks_places, R.string.help_racks_places_title),
                    HelpSection(R.string.help_racks_finding, R.string.help_racks_finding_title, Icons.Outlined.Search),
                    HelpSection(R.string.help_racks_zooming, R.string.help_racks_zooming_title, Icons.Outlined.ZoomIn),
                    HelpSection(R.string.help_racks_moving, R.string.help_racks_moving_title),
                    HelpSection(R.string.help_racks_drag, R.string.help_racks_drag_title),
                    HelpSection(R.string.help_racks_several, R.string.help_racks_several_title),
                    HelpSection(R.string.help_racks_check, R.string.help_racks_check_title, Icons.Outlined.Checklist),
                    HelpSection(R.string.help_racks_irregular, R.string.help_racks_irregular_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Tonight, R.string.help_tonight_title, R.string.help_tonight_summary, Icons.Outlined.Bedtime,
                listOf(
                    HelpSection(R.string.help_tonight_why, R.string.help_tonight_why_title),
                    HelpSection(R.string.help_tonight_occasion, R.string.help_tonight_occasion_title, Icons.Outlined.CardGiftcard),
                    HelpSection(R.string.help_tonight_maturity, R.string.help_tonight_maturity_title),
                    HelpSection(R.string.help_tonight_suggestions, R.string.help_tonight_suggestions_title),
                    HelpSection(R.string.help_tonight_serving, R.string.detail_serving),
                    HelpSection(R.string.help_tonight_reminders, R.string.reminders_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Drinking, R.string.help_drinking_title, R.string.help_drinking_summary, Icons.Outlined.LocalBar,
                listOf(
                    HelpSection(R.string.help_drinking_drink, R.string.drink_title),
                    HelpSection(R.string.help_drinking_note, R.string.help_drinking_note_title),
                    HelpSection(R.string.help_drinking_gone, R.string.help_drinking_gone_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Journal, R.string.help_journal_title, R.string.help_journal_summary, Icons.Outlined.AutoStories,
                listOf(
                    HelpSection(R.string.help_journal_diary, R.string.help_journal_diary_title),
                    HelpSection(R.string.help_journal_add, R.string.journal_add, Icons.Filled.Add),
                    HelpSection(R.string.help_journal_filters, R.string.help_journal_filters_title, Icons.Outlined.FilterList),
                    HelpSection(R.string.help_journal_tags, R.string.discovery_tags),
                    HelpSection(R.string.help_journal_list, R.string.discovery_shopping_list, Icons.Outlined.ShoppingCart),
                    HelpSection(R.string.help_journal_not_cellar, R.string.help_journal_not_cellar_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Devices, R.string.help_devices_title, R.string.help_devices_summary, Icons.Outlined.Devices,
                listOf(
                    HelpSection(R.string.help_devices_add, R.string.help_devices_add_title),
                    HelpSection(R.string.help_devices_compare, R.string.help_devices_compare_title),
                    HelpSection(R.string.help_devices_sync, R.string.help_devices_sync_title),
                    HelpSection(R.string.help_devices_bottles, R.string.help_devices_bottles_title),
                    HelpSection(R.string.help_devices_remove, R.string.help_devices_remove_title),
                    HelpSection(R.string.help_devices_away, R.string.help_devices_away_title),
                    HelpSection(R.string.help_devices_webdav, R.string.help_devices_webdav_title),
                    HelpSection(R.string.help_devices_privacy, R.string.help_devices_privacy_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Sync, R.string.help_sync_title, R.string.help_sync_summary, Icons.Outlined.Sync,
                listOf(
                    HelpSection(R.string.help_sync_steps, R.string.help_sync_steps_title),
                    HelpSection(R.string.help_sync_home, R.string.help_sync_home_title),
                    HelpSection(R.string.help_sync_away, R.string.help_sync_away_title),
                    HelpSection(R.string.help_sync_now, R.string.help_sync_now_title),
                    HelpSection(R.string.help_sync_frequency, R.string.help_sync_frequency_title),
                    HelpSection(R.string.help_sync_background, R.string.help_sync_background_title),
                    HelpSection(R.string.help_sync_notify, R.string.help_sync_notify_title),
                    HelpSection(R.string.help_sync_merge, R.string.help_sync_merge_title),
                    HelpSection(R.string.help_sync_remove, R.string.help_sync_remove_title),
                    HelpSection(R.string.help_sync_local, R.string.help_sync_local_title),
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
                HelpTopic.WebSearch, R.string.help_web_title, R.string.help_web_summary, Icons.Outlined.Public,
                listOf(
                    HelpSection(R.string.help_web_off, R.string.help_web_off_title),
                    HelpSection(R.string.help_web_service, R.string.help_web_service_title),
                    HelpSection(R.string.help_web_search, R.string.help_web_search_title, Icons.Outlined.Public),
                    HelpSection(R.string.help_web_price, R.string.help_web_price_title, Icons.Outlined.Tag),
                    HelpSection(R.string.help_web_pictures, R.string.help_web_pictures_title),
                    HelpSection(R.string.help_web_sent, R.string.help_web_sent_title),
                ),
            ),
            HelpArticle(
                HelpTopic.Data, R.string.help_data_title, R.string.help_data_summary, Icons.Outlined.SdStorage,
                listOf(
                    HelpSection(R.string.help_data_backup, R.string.help_data_backup_title),
                    HelpSection(R.string.help_data_csv, R.string.help_data_csv_title),
                    HelpSection(R.string.help_data_pdf, R.string.help_data_pdf_title),
                    HelpSection(R.string.help_data_other, R.string.help_data_other_title),
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
