package com.tingben.app

import android.app.Application
import com.tingben.app.diag.EventLog

class TingbenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
