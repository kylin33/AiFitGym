package io.github.kylin33.aifitgym

import android.app.Application
import io.github.kylin33.aifitgym.di.AppContainer

class FitGymApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 首启导入是异步的，越早触发越好；这里不阻塞主线程
        container.bootstrap()
    }
}
