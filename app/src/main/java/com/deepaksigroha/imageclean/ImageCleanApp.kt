package com.deepaksigroha.imageclean

import android.app.Application
import com.deepaksigroha.imageclean.data.AppContainer

class ImageCleanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
