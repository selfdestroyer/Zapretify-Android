package io.github.selfdestroyer.zapretify

import android.app.Application
import io.github.selfdestroyer.zapretify.engine.EngineController
import io.github.selfdestroyer.zapretify.profiles.NetworkWatcher
import io.github.selfdestroyer.zapretify.profiles.ProfileController
import io.github.selfdestroyer.zapretify.proxy.ProxyController
import io.github.selfdestroyer.zapretify.selection.SelectionController

class ZapretifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
        EngineController.init(this)
        SelectionController.init(this)
        NetworkWatcher.init(this)
        ProfileController.init(this)
    }
}
