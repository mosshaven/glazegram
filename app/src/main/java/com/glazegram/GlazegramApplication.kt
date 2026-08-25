package com.glazegram

import android.app.Application
import com.glazegram.tdlib.TdLibRuntime

class GlazegramApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TdLibRuntime.initialize(this)
    }
}
