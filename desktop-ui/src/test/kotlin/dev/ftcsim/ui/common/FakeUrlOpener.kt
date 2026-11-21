package dev.ftcsim.ui.common

/** Opens nothing, writes down what it was asked to open, and answers with the problem a test put in it. */
class FakeUrlOpener : UrlOpener {
    val opened = ArrayList<String>()
    var problem: String? = null

    override suspend fun open(url: String): String? {
        opened.add(url)
        return problem
    }
}
