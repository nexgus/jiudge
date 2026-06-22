package io.github.nexgus.jiudge

import android.app.Application
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class JiudgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // mapsforge needs its Android graphic factory initialized once before any MapView is built.
        AndroidGraphicFactory.createInstance(this)
    }
}
