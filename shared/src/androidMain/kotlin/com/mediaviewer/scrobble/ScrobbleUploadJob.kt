// This file is adapted from Rocksky (https://github.com/tsirysndr/rocksky),
// apps/app/modules/rocksky-scrobbler — ScrobbleUploadJob.kt — Copyright (c)
// 2025 Tsiry Sandratraina, used under the Mozilla Public License 2.0
// (https://mozilla.org/MPL/2.0/). This file stays available under those terms.
package com.mediaviewer.scrobble

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.mediaviewer.util.RockskyScrobbler
import com.mediaviewer.util.ScrobbleTrack
import com.mediaviewer.util.ScrobbleUploadOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the saved listens, one at a time, oldest first — whenever there's
 * a connection, whether or not Stellar is open. Android runs it (it's a
 * "job" that needs a network), puts it back after the phone restarts, and
 * spaces out the retries by itself when sending fails.
 *
 * Where Rocksky's app posts each listen to Rocksky's server, this writes
 * the records straight into your repo — see [RockskyScrobbler].
 */
class ScrobbleUploadJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellations = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false)
        cancellations[params.jobId] = stop
        executor.execute {
            var retry = false
            try {
                // At most a hundred a run, so a long offline history doesn't
                // hold the job open for minutes.
                var submitted = 0
                while (!stop.get() && submitted < 100) {
                    val settings = ScrobbleStore.settings(this)
                    if (!settings.enabled) break
                    val db = ScrobbleStore.db(this)
                    val row = db.rawQuery(
                        "SELECT id,payload FROM queue WHERE did=? AND failed=0 ORDER BY created LIMIT 1", arrayOf(settings.did)
                    ).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: break
                    val j = JSONObject(row.second)
                    val track = ScrobbleTrack(
                        title = j.optString("title"), artist = j.optString("artist"),
                        album = j.optString("album"), albumArtist = j.optString("albumArtist"),
                        durationMs = j.optLong("duration"), timestampSeconds = j.optLong("timestamp")
                    )
                    val outcome = runBlocking { RockskyScrobbler.upload(applicationContext, settings.did, track) }
                    val prefs = ScrobbleStore.prefs(this)
                    when (outcome) {
                        ScrobbleUploadOutcome.DONE -> {
                            db.delete("queue", "id=?", arrayOf(row.first))
                            prefs.edit().putLong("lastUpload", System.currentTimeMillis()).remove("uploadError").remove("authError").apply()
                            submitted++
                        }
                        ScrobbleUploadOutcome.AUTH -> {
                            prefs.edit().putString("authError", "Sign in to Bluesky again to send your saved listens.").apply()
                            break
                        }
                        ScrobbleUploadOutcome.REJECTED -> {
                            db.execSQL("UPDATE queue SET failed=1 WHERE id=?", arrayOf(row.first))
                            submitted++
                        }
                        ScrobbleUploadOutcome.RETRY -> {
                            prefs.edit().putString("uploadError", "Waiting for a connection. Your listens are saved and will be sent.").apply()
                            retry = true
                            break
                        }
                    }
                }
                if (submitted == 100) retry = true
            } catch (_: Exception) {
                retry = true
            } finally {
                if (cancellations[params.jobId] === stop) cancellations.remove(params.jobId)
                if (!stop.get()) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancellations.remove(params.jobId)?.set(true)
        return true
    }

    override fun onDestroy() {
        cancellations.values.forEach { it.set(true) }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** ("SLR" + a digit: this job's own numbers.) */
        const val JOB_ID = 0x534c5231

        fun schedule(c: Context) {
            if (!ScrobbleStore.settings(c).enabled) return
            val scheduler = c.getSystemService(JobScheduler::class.java) ?: return
            // A check every fifteen minutes catches anything a finished job
            // missed (a listen saved just as it ended, a killed process).
            if (scheduler.getPendingJob(JOB_ID + 1) == null) {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID + 1, ComponentName(c, ScrobbleUploadJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                        .setPeriodic(15 * 60 * 1000L).build()
                )
            }
            // (A job already waiting or running isn't replaced each time a song counts.)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, ScrobbleUploadJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            )
        }

        fun cancel(c: Context) {
            val scheduler = c.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID)
            scheduler.cancel(JOB_ID + 1)
        }
    }
}

/** After the phone restarts or Stellar is updated: Android is asked to
 *  connect the listener again, and the sending job is put back. */
class ScrobbleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            if (!ScrobbleStore.settings(context).enabled) return
            NotificationListenerService.requestRebind(ComponentName(context, StellarScrobbleListener::class.java))
            ScrobbleUploadJob.schedule(context)
        } catch (_: Exception) {
        }
    }
}
