import com.github.minxyzgo.highini.*
import com.github.minxyzgo.highini.parse.*
import com.github.minxyzgo.highini.type.*

fun main() {
    try {
        val tree = ConfigFactory.parseResources(
            Thread.currentThread().contextClassLoader.getResource("test3.hini")!!,
            Parser().apply {
                this.tags.add(
                    ConfigTag(
                        "test",
                        TestTag::class.java
                    )
                )

                this.tags.add(
                    ConfigTag(
                        "core",
                        isSingle = true
                    )
                )

                this.tags.add(
                    ConfigTag(
                        "action",
                        isSingle = true
                    )
                )
            }
        )

        tree.mutableMap.forEach { (_, section) ->
            section.mutableMap.forEach { (k, v) ->
                println("key: $k, value: $v")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}