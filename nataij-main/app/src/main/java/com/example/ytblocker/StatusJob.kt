package com.example.ytblocker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

// نبض كل 15 دقيقة حتى لو إمكانية الوصول مطفية، عشان أمي تعرف إنها مطفية
class StatusJob : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        DeviceSync.maybeHeartbeat(this, force = true)
        Handler(Looper.getMainLooper()).postDelayed({ jobFinished(p, false) }, 8000)
        return true
    }
    override fun onStopJob(p: JobParameters?) = false

    companion object {
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (js.allPendingJobs.any { it.id == 4401 }) return
                js.schedule(JobInfo.Builder(4401, ComponentName(ctx, StatusJob::class.java))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
            } catch (e: Exception) { }
        }
    }
}
