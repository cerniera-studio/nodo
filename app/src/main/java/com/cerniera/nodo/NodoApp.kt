package com.cerniera.nodo

import android.app.Application
import com.cerniera.kit.Crash

class NodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this, "Nodo")
        Reminders.channel(this)
    }
}
