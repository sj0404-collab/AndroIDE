package dev.sadat.androide

import android.app.Application
import dev.sadat.androide.workspace.KeyStore
import dev.sadat.androide.workspace.Workspace

class AndroApp : Application() {
    lateinit var keys: KeyStore
        private set
    lateinit var workspace: Workspace
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        keys = KeyStore(this)
        workspace = Workspace(this)
    }

    companion object {
        lateinit var instance: AndroApp
            private set
    }
}
