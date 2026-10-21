package app.encave.core

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** An earlier state of the cellar kept on the device: [id] is its file name, [date] when it was saved. */
data class CellarVersion(val id: String, val date: Instant)

/** Where earlier versions of the cellar are read from: the folder next to the cellar, or sample ones. */
interface CellarVersionSource {
    /** Newest first. */
    fun versions(): List<CellarVersion>
    fun cellar(version: CellarVersion): Cellar
}

/**
 * Automatic copies of the cellar file, in a folder next to it: when the cellar is saved on a
 * new day, the file as it was (its last state of the day it was saved) is kept first. One
 * copy is kept for each of the last 7 days the cellar changed, and one for each week of the
 * last 4 weeks; older ones are removed. CaveKit keeps them the same way (`CellarVersionFolder`).
 *
 * Only the cellar file is copied: photos and documents are named after their content and
 * kept while a version refers to them ([fileRefs]).
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
            prune(now)
        }
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
    fun prune(now: Instant = Instant.now()) {
        val all = versions()
        val kept = kept(all.map { it.date }, now, zone)
        all.filter { it.date !in kept }.forEach { File(folder, it.id).delete() }
    }

    private fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    companion object {
        const val DAILY_COUNT = 7
        const val WEEKLY_DAYS = 28
        private const val PREFIX = "cellar-"
        private const val SUFFIX = ".json"
        private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /**
         * The dates kept: the newest of each of the last 7 days, and the newest of each ISO week
         * of the last 28 days. Versions dated later than [now] (a clock set back) count as today's.
         */
        fun kept(dates: List<Instant>, now: Instant, zone: ZoneId): Set<Instant> {
            val today = now.atZone(zone).toLocalDate()
            val days = HashMap<LocalDate, Instant>()
            val weeks = HashMap<Pair<Int, Int>, Instant>()
            for (date in dates) {
                val day = date.atZone(zone).toLocalDate()
                val age = maxOf(0L, ChronoUnit.DAYS.between(day, today))
                if (age < DAILY_COUNT) days[day] = maxOf(days[day] ?: date, date)
                if (age < WEEKLY_DAYS) {
                    val week = day.get(WeekFields.ISO.weekBasedYear()) to day.get(WeekFields.ISO.weekOfWeekBasedYear())
                    weeks[week] = maxOf(weeks[week] ?: date, date)
                }
            }
            return days.values.toSet() + weeks.values
        }

        /** "cellar-20261020-221430.json": the date in UTC, to the second. */
        internal fun name(date: Instant): String = PREFIX + formatter.format(LocalDateTime.ofInstant(date, ZoneOffset.UTC)) + SUFFIX

        internal fun date(name: String): Instant? {
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
            return runCatching {
                LocalDateTime.parse(name.removePrefix(PREFIX).removeSuffix(SUFFIX), formatter).toInstant(ZoneOffset.UTC)
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
    /** Adds the wines, bottles, racks and Journal entries that are not in the cellar (by ID); nothing already there changes. */
    AddMissing,

    /**
     * The cellar becomes the backup: what is not in it is deleted, what differs is put back as
     * it was, settings included. Applied as a change made on this device, so sync takes it to
     * the other devices (docs/SYNC.md).
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
            compare(backup.wines, current.wines)
            compare(backup.bottles, current.bottles)
            compare(backup.racks.associateBy { it.id }, current.racks.associateBy { it.id })
            compare(backup.discoveries, current.discoveries)
            return RestorePreview(
                date = date,
                wines = backup.wines.size,
                bottles = backup.bottles.values.count { it.status == Bottle.Status.InCellar },
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
    RestoreMode.Replace -> backup.copy()
}
