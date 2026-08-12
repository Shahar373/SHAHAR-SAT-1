package com.shahar.shaharsat

import android.app.Application
import com.shahar.shaharsat.di.AppContainer

class SatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
