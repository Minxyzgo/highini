import com.github.minxyzgo.highini.parse.*
import com.github.minxyzgo.highini.type.*

fun main() {
    object : Parser() {
        init {
            parseBoolean(
                "5<10&&(78==100||45>23)",
                ConfigTree(""),
                0
            )
        }
    }
}