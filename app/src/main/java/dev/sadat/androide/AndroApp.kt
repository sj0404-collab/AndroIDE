package dev.sadat.androide

import android.app.Application
import dev.sadat.androide.session.SessionStore
import dev.sadat.androide.workspace.KeyStore
import dev.sadat.androide.workspace.Workspace

class AndroApp : Application() {
    lateinit var keys: KeyStore
        private set
    lateinit var workspace: Workspace
        private set
    lateinit var sessions: SessionStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                getFileStreamPath("crash.txt").writeText(e.stackTraceToString())
            } catch (_: Exception) { }
        }
        keys = KeyStore(this)
        workspace = Workspace(this)
        sessions = SessionStore(this)
    }

    companion object {
        lateinit var instance: AndroApp
            private set
    }
}
