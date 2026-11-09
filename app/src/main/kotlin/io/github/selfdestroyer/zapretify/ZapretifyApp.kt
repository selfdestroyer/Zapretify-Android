package io.github.selfdestroyer.zapretify

import android.app.Application
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.proxy.ProxyController

class ZapretifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
        EngineController.init(this)
    }
}
