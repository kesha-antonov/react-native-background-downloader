package com.eko.uidt

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The notification "Cancel" receiver under its name before v5.0.0. A progress
 * notification posted by v4.x can still be in the shade after the app is
 * updated, and its Cancel button targets this class.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CancelDownloadReceiver : com.kesha_antonov.uidt.CancelDownloadReceiver()
