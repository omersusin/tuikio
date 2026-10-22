package dev.tuikio.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.tuikio.app.data.CatalogRepo
import dev.tuikio.app.data.Installer
import dev.tuikio.app.data.Library
import dev.tuikio.app.data.Prefs
import dev.tuikio.app.data.Sources
import dev.tuikio.app.ui.Motion
import dev.tuikio.app.ui.Theme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TuikioApp : Application() {
    lateinit var catalog: CatalogRepo
        private set
    lateinit var library: Library
        private set
    lateinit var installer: Installer
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var sources: Sources
        private set

    /** One small pool for disk and network work; the UI thread never blocks on I/O. */
    val io: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "tuikio-io").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        Theme.init(this)
        Motion.init(this)
        prefs = Prefs(this)
        catalog = CatalogRepo(this)
        library = Library(this)
        installer = Installer(this, catalog, library)
        sources = Sources(this)
    }

    /** Runs [work] on the I/O pool and delivers its result (or error) on the main thread. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    companion object {
        lateinit var instance: TuikioApp
            private set
    }
}
