package com.sniffsub.sniff_sub_player

import android.app.Application

class SniffSubPlayerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
