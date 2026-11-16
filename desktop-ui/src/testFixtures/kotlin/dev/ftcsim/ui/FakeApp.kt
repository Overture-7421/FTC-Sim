package dev.ftcsim.ui

import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.link.FakeSimulatorLink
import dev.ftcsim.ui.nav.Destination

/**
 * Opens the window on a [FakeAppHost] (a fake link and a fake preparer, no robot code) so that the interface can be
 * developed without a worker: `:desktop-ui:runFake`. It takes the options of [AppOptions], and `--practice` also opens
 * the default fake session and shows it.
 */
object FakeApp {
    @JvmStatic
    fun main(args: Array<String>) {
        val openPractice = "--practice" in args
        val options = AppOptions.parse(args.filter { it != "--practice" }.toTypedArray())
        App.run(FakeAppHost(), options) { services, ui ->
            if (openPractice) {
                services.openSession(FakeSimulatorLink.defaultRequest())
                ui.navigator.go(Destination.PRACTICE)
            }
        }
    }
}
