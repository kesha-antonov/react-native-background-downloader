package com.eko

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The job service under its name before v5.0.0. A download that v4.x scheduled
 * with the JobScheduler names this class, and the system runs it here after the
 * app is updated; without it that job has no service to run in and the download
 * is lost. New jobs use [com.kesha_antonov.UIDTDownloadJobService].
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UIDTDownloadJobService : com.kesha_antonov.UIDTDownloadJobService()
