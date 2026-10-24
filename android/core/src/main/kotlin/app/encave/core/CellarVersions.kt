package app.encave.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** An earlier state of the cellar kept on the device: [id] is its file name, [date] when it was saved. */
data class CellarVersion(val id: String, val date: Instant) {
    /** Saved on purpose before the cellar was replaced by another device's (Replace and join). */
    val isBeforeReplacing: Boolean get() = CellarVersionFolder.isBeforeReplacing(id)
}

/** Where earlier versions of the cellar are read from: the folder next to the cellar, or sample ones. */
interface CellarVersionSource {
    /** Newest first. */
    fun versions(): List<CellarVersion>
    fun cellar(version: CellarVersion): Cellar
}

/**
 * Automatic copies of the cellar file, in a folder next to it: when the cellar is saved on a
 * new day, the file as it was (its last state of the day it was saved) is kept first. One
 * copy is kept for each of the last 7 days the cellar changed, then one for each of the 4
 * weeks before; older ones are removed. Counted from the copies, not from today, so the last
 * ones stay after weeks without a change. CaveKit keeps them the same way (`CellarVersionFolder`).
 *
 * Only the cellar file is copied: photos and documents are named after their content and
 * kept while a version refers to them ([fileRefs]).
 *
 * A version is also saved before the cellar is replaced by another device's
 * ([keepBeforeReplacing]): those are kept apart from the daily and weekly ones, the last 3 of
 * them, so the next day's copy never takes their place.
 */
class CellarVersionFolder(val folder: File, private val zone: ZoneId = ZoneId.systemDefault()) : CellarVersionSource {
    /**
     * Called before [cellarFile] is saved over: keeps a copy of it if it was last saved on an
     * earlier day than [now], holds a cellar that is not empty, and is not kept yet. Then
     * removes the versions no longer needed. Errors are ignored: a copy never stops a save.
     */
    fun keepCopy(cellarFile: File, now: Instant = Instant.now()) {
        runCatching {
            if (!cellarFile.isFile) return
            val modified = Instant.ofEpochMilli(cellarFile.lastModified())
            if (!day(modified).isBefore(day(now))) return
            val target = File(folder, name(modified))
            if (target.exists()) return
            val text = cellarFile.readText(Charsets.UTF_8)
            if (CellarJson.decode(text).isEmpty) return
            folder.mkdirs()
            val temp = File(folder, "${target.name}.${UUID.randomUUID()}.tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.delete()
                return
            }
            prune()
        }
    }

    /**
     * Saves [cellar] as a version now, before it is replaced by another device's cellar
     * (Replace and join), so it can be restored from Previous versions. Nothing is saved for an
     * empty cellar or one that holds only the sample. Returns the version saved.
     */
    fun keepBeforeReplacing(cellar: Cellar, now: Instant = Instant.now()): CellarVersion? = runCatching<CellarVersion?> {
        if (cellar.isEmpty || cellar.isOnlySample) return null
        val target = File(folder, beforeReplacingName(now))
        folder.mkdirs()
        val temp = File(folder, "${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(CellarJson.encode(cellar), Charsets.UTF_8)
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return null
        }
        prune()
        date(target.name)?.let { CellarVersion(target.name, it) }
    }.getOrNull()

    /**
     * Before a Replace (restoring a backup, Replace and join, Erase all data that keeps the
     * versions): saves [cellar] as a version. True when it was saved or there was nothing to keep
     * (an empty cellar, or only the sample); false when it could not be saved, and then the
     * cellar must not be replaced.
     */
    fun keepBeforeReplacingIfNeeded(cellar: Cellar, now: Instant = Instant.now()): Boolean =
        cellar.isEmpty || cellar.isOnlySample || keepBeforeReplacing(cellar, now) != null

    /** Erase all data: removes every version. */
    fun removeAll() {
        folder.deleteRecursively()
    }

    override fun versions(): List<CellarVersion> =
        folder.listFiles().orEmpty().mapNotNull { file -> date(file.name)?.let { CellarVersion(file.name, it) } }
            .sortedByDescending { it.date }

    override fun cellar(version: CellarVersion): Cellar {
        require(date(version.id) != null) { "not a version" }
        return CellarJson.decode(File(folder, version.id).readText(Charsets.UTF_8))
    }

    /** Photos and documents the versions refer to, kept on the device while they are. */
    fun fileRefs(): Set<FileRef> = versions().flatMap { runCatching { cellar(it).fileRefs }.getOrDefault(emptySet()) }.toSet()

    /** Removes the versions no longer kept. */
    fun prune() {
        val all = versions()
        val automatic = all.filter { !it.isBeforeReplacing }
        val keptDates = kept(automatic.map { it.date }, zone)
        val kept = automatic.filter { it.date in keptDates }.map { it.id }.toSet() +
            all.filter { it.isBeforeReplacing }.take(BEFORE_REPLACING_COUNT).map { it.id }
        all.filter { it.id !in kept }.forEach { File(folder, it.id).delete() }
    }

    private fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    companion object {
        const val DAILY_COUNT = 7
        const val WEEKLY_COUNT = 4

        /** Versions saved before replacing the cellar that are kept. */
        const val BEFORE_REPLACING_COUNT = 3
        private const val PREFIX = "cellar-"
        private const val SUFFIX = ".json"

        /** "cellar-20261020-221430-before-replace.json" */
        private const val BEFORE_REPLACING_MARK = "-before-replace"
        private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /**
         * The dates kept: the newest of each of the 7 latest days that have a version, then the
         * newest of each of the 4 latest ISO weeks before those days. Counted from the versions
         * themselves, not from today, so a long pause never removes them all.
         */
        fun kept(dates: List<Instant>, zone: ZoneId): Set<Instant> {
            val days = HashMap<LocalDate, Instant>()
            for (date in dates) {
                val day = date.atZone(zone).toLocalDate()
                days[day] = maxOf(days[day] ?: date, date)
            }
            val daily = days.keys.sortedDescending().take(DAILY_COUNT)
            val oldestDaily = daily.lastOrNull()?.atStartOfDay(zone)?.toInstant() ?: return emptySet()
            val weeks = HashMap<Pair<Int, Int>, Instant>()
            for (date in dates) {
                if (!date.isBefore(oldestDaily)) continue
                val day = date.atZone(zone).toLocalDate()
                val week = day.get(WeekFields.ISO.weekBasedYear()) to day.get(WeekFields.ISO.weekOfWeekBasedYear())
                weeks[week] = maxOf(weeks[week] ?: date, date)
            }
            val weekly = weeks.values.sortedDescending().take(WEEKLY_COUNT)
            return daily.mapNotNull { days[it] }.toSet() + weekly
        }

        /** "cellar-20261020-221430.json": the date in UTC, to the second. */
        internal fun name(date: Instant): String = PREFIX + formatter.format(LocalDateTime.ofInstant(date, ZoneOffset.UTC)) + SUFFIX

        internal fun beforeReplacingName(date: Instant): String = name(date).removeSuffix(SUFFIX) + BEFORE_REPLACING_MARK + SUFFIX

        internal fun isBeforeReplacing(name: String): Boolean = name.endsWith(BEFORE_REPLACING_MARK + SUFFIX) && date(name) != null

        internal fun date(name: String): Instant? {
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
            return runCatching {
                LocalDateTime.parse(name.removePrefix(PREFIX).removeSuffix(SUFFIX).removeSuffix(BEFORE_REPLACING_MARK), formatter)
                    .toInstant(ZoneOffset.UTC)
            }.getOrNull()
        }
    }
}

/** Sample versions, for the screen tour and tests. */
class InMemoryCellarVersions(items: List<Pair<Instant, Cellar>>) : CellarVersionSource {
    private val items = items.map { (date, cellar) -> CellarVersion(CellarVersionFolder.name(date), date) to cellar.copy() }
        .sortedByDescending { it.first.date }

    override fun versions(): List<CellarVersion> = items.map { it.first }

    override fun cellar(version: CellarVersion): Cellar =
        requireNotNull(items.firstOrNull { it.first == version }?.second) { "unknown version" }.copy()
}

/** How a backup or an earlier version is put back. */
enum class RestoreMode {
    /**
     * Adds the wines, bottles, racks and Journal entries that are not in the cellar (by ID), and
     * the saved searches whose name is not there (ignoring case); nothing already there changes,
     * the currency included.
     */
    AddMissing,

    /**
     * The cellar becomes the backup, its settings (currency, saved searches) included: what is
     * not in it is deleted, what differs is put back as it was. Applied as a change made on this
     * device, so sync takes it to the other devices (docs/SYNC.md).
     */
    Replace,
}

/**
 * What a backup or an earlier version holds, and how it differs from the cellar, shown before
 * restoring it. Counted as CaveKit's `RestorePreview` counts.
 */
data class RestorePreview(
    /** When the backup was made, or when the version was saved. */
    val date: Instant,
    /** Wines with bottles in the cellar ([CellarCounts.winesWithBottles]). */
    val wines: Int,
    /** Bottles in the cellar (not opened, given away, or on order). */
    val bottles: Int,
    val racks: Int,
    /** Bottles opened and wines tasted elsewhere. */
    val journalEntries: Int,
    val photos: Int,
    val documents: Int,
    /** In the backup and not in the cellar (what Add what is missing adds). */
    val added: Int,
    /** In the cellar and not in the backup (what Replace deletes). */
    val removed: Int,
    /** In both, but different (what Replace puts back as it was). */
    val changed: Int,
) {
    /** The backup holds the same cellar. */
    val isSame: Boolean get() = added == 0 && removed == 0 && changed == 0

    companion object {
        fun of(backup: Cellar, date: Instant, current: Cellar): RestorePreview {
            // A record that only differs by what a backup does not keep is the same.
            val compared = current.matching(backup)
            var added = 0
            var removed = 0
            var changed = 0
            fun <T> compare(a: Map<UUID, T>, b: Map<UUID, T>) {
                for ((id, value) in a) {
                    val other = b[id]
                    if (other == null) added++ else if (other != value) changed++
                }
                removed += b.keys.count { it !in a }
            }
            compare(compared.wines, current.wines)
            compare(compared.bottles, current.bottles)
            compare(compared.racks.associateBy { it.id }, current.racks.associateBy { it.id })
            compare(compared.discoveries, current.discoveries)
            return RestorePreview(
                date = date,
                wines = backup.winesWithBottlesCount,
                bottles = backup.bottlesInCellarCount,
                racks = backup.racks.size,
                journalEntries = backup.bottles.values.count { it.status == Bottle.Status.Consumed } + backup.discoveries.size,
                photos = backup.photoIds.size,
                documents = backup.attachmentIds.size,
                added = added,
                removed = removed,
                changed = changed,
            )
        }
    }
}

/** The cellar after restoring [backup] (a new instance; this one is not changed). */
fun Cellar.restoring(backup: Cellar, mode: RestoreMode): Cellar = when (mode) {
    RestoreMode.AddMissing -> copy().also { it.merge(backup) }
    RestoreMode.Replace -> matching(backup)
}

/**
 * [backup] (a new instance), with each record that differs from this cellar's only by what a
 * JSON backup does not keep (fractions of a second in dates) taken from this cellar instead,
 * as CaveKit's `Cellar.matching` does. A backup made a minute ago then reads as the same
 * cellar, and Replace leaves those records as they are: not stamped, not sent again.
 */
fun Cellar.matching(backup: Cellar): Cellar {
    // Only the records that differ are written out and read back, as a backup would be.
    fun <T> differing(own: Map<UUID, T>, other: Map<UUID, T>): List<T> =
        own.filter { (id, value) -> other[id].let { it != null && it != value } }.values.toList()
    val backupRacks = backup.racks.associateBy { it.id }
    val written = Cellar(
        differing(wines, backup.wines), differing(bottles, backup.bottles),
        racks.filter { rack -> backupRacks[rack.id].let { it != null && it != rack } },
        settings, differing(discoveries, backup.discoveries),
    )
    val read = runCatching { CellarJson.backupFrom(CellarJson.backup(written)).cellar }.getOrNull()
        ?: return backup.copy()
    fun <T> pick(other: Map<UUID, T>, readBack: Map<UUID, T>, own: Map<UUID, T>): List<T> =
        other.map { (id, value) -> if (readBack[id] == value) own.getValue(id) else value }
    val readRacks = read.racks.associateBy { it.id }
    val ownRacks = racks.associateBy { it.id }
    return Cellar(
        pick(backup.wines, read.wines, wines),
        pick(backup.bottles, read.bottles, bottles),
        backup.racks.map { rack -> if (readRacks[rack.id] == rack) ownRacks.getValue(rack.id) else rack },
        if (read.settings == backup.settings) settings else backup.settings,
        pick(backup.discoveries, read.discoveries, discoveries),
    ).also { it.schemaVersion = backup.schemaVersion }
}
