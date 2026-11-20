package org.matrix.vector.manager.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.stackTraceOf

/**
 * Keeps up to five manager crash traces for log export when Basic or Detailed is enabled.
 * Off skips new records. Always delegates to Android's previous crash handler.
 */
object CrashRecorder {

    private const val DIR_NAME = "crash"

    /** How many crashes are kept. Older ones are the least likely to still be true. */
    private const val MAX_FILES = 5

    @Volatile private var installed = false

    /**
     * Takes over the default handler, once per process, and discards what another build left.
     *
     * Called from [org.matrix.vector.manager.di.ServiceLocator.attach], early in the activity's
     * `onCreate` and before anything that could fail, so the handler is in place before any screen
     * exists.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val application = context.applicationContext ?: context
        runCatching { discardOtherBuilds(application) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(application, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The recorded crashes, newest first, or null when there have been none. */
    fun read(context: Context): String? =
        runCatching {
                files(context)
                    .joinToString("\n") { it.readText().trimEnd() }
                    .ifBlank { null }
            }
            .getOrNull()

    /**
     * The newest crash, parsed — what the card summarises and the trace screen lists.
     *
     * Only the newest: the question the status screen answers is "what just happened", and the
     * older records are still on file, still in [read]'s output, and still in the log export.
     */
    fun newest(context: Context): CrashReport? =
        runCatching { files(context).firstOrNull()?.readText()?.let(::parseCrashReport) }
            .getOrNull()

    fun clear(context: Context) {
        runCatching { files(context).forEach { it.delete() } }
    }

    /**
     * Drops the records that some other build of the manager wrote.
     *
     * A record outlives the build that made it — the directory is this process's cache, which an
     * update does not clear — so the status card goes on showing a crash that the running build
     * has already fixed, until somebody thinks to press clear. That is not a stale line on a
     * screen. It is what a reporter copies into the issue to show the fix did not work: #799 was
     * answered with five traces from the build before the one that fixed them.
     *
     * Only at [install], never at [record]: this is about what a *new* build inherits, and a crash
     * loop within one build must keep every record it makes.
     *
     * A file whose second line cannot be read is kept. Losing a trace is the worse of the two
     * failures, and a record this class cannot parse is exactly the one worth still having.
     */
    private fun discardOtherBuilds(context: Context) {
        val current = BUILD
        files(context).forEach { file ->
            val theirs = runCatching { file.useLines { it.drop(1).firstOrNull() } }.getOrNull()
            if (theirs != null && !theirs.startsWith(current)) runCatching { file.delete() }
        }
    }

    /** Where the log export collects them from, as named in this class's own documentation. */
    private fun directory(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** Newest first, which is the order both the card and the clipboard want. */
    private fun files(context: Context): List<File> =
        directory(context)
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name.removeSuffix(SUFFIX).toLongOrNull() ?: 0L }
            .orEmpty()

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        if (org.matrix.vector.logging.LogPolicy.getLevel() == org.matrix.vector.logging.LogPolicy.OFF) return
        val now = System.currentTimeMillis()
        val dir = directory(context)
        dir.mkdirs()
        val text = buildString {
            append(header(context, thread, now))
            append('\n')
            append(stackTraceOf(throwable))
            append('\n')
        }
        runCatching { File(dir, "$now$SUFFIX").writeText(text) }
        // After writing, so a failure to prune never costs us the record we just made.
        runCatching { files(context).drop(MAX_FILES).forEach { it.delete() } }
    }

    /**
     * The context a stack trace alone does not carry.
     *
     * Which build, which of the two ways the manager can be running, which thread, and which
     * platform — the four things that are always asked first and are never in the trace. Written
     * in [Locale.ROOT] on purpose: this text exists to be pasted into an issue, and a crash report
     * whose date is formatted for the reporter's locale is a crash report the reader has to parse.
     *
     * Split across two lines by what a reader can find elsewhere rather than by subject. When and
     * on which thread is the first line because nothing else on the status screen answers it; the
     * build and the platform are the second because everything there is repeated a few rows down.
     * [parseCrashReport] reads the split back, so the two lines are a format, not a layout.
     */
    private fun header(context: Context, thread: Thread, at: Long): String {
        val parasitic = context.packageName == BuildConfig.INJECTED_PACKAGE_NAME
        val host = if (parasitic) "parasitic in ${context.packageName}" else "standalone"
        return "${TIMESTAMP.format(Date(at))} · thread ${thread.name}\n" +
            "$BUILD · $host · " +
            "android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})"
    }

    /**
     * How a record says which build wrote it, and so what [discardOtherBuilds] matches on.
     *
     * The whole line rather than the hash alone: a build from a dirty tree carries the hash of the
     * commit it was built from, so two of them can share it while differing by everything that was
     * uncommitted at the time.
     */
    private val BUILD: String
        get() =
            "manager ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                BuildConfig.VERSION_HASH

    private const val SUFFIX = ".log"

    private val TIMESTAMP
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
}
