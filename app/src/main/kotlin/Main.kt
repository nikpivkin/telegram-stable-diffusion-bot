import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.network.ResponseError
import com.github.kotlintelegrambot.network.fold
import com.rabbitmq.client.ConnectionFactory
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


val dotenv = dotenv()

val BOT_TOKEN = dotenv["BOT_TOKEN"] ?: throw Exception("BOT_TOKEN not found")
val AMPS_URL = dotenv["AMPS_URL"] ?: throw Exception("AMPS_URL not found")


// TODO: localize this
val startMsg = """
    Привет! Грамотно составь текстовый запрос и отправь в бота. 

    Пример:
        monkey doctor, minimalistic yellow background
    Запросы можно отправлять на разных языках, но лучше всего бот распознает английский.
    """.trimIndent()

val addedToQueue = """
    Вы добавлены в очередь. Когда будет готово, мы отправим результат.
    *Это может занять ~1-5 минут, в зависимости от нагрузки.
""".trimIndent()

@Serializable
data class Txt2ImgEvent(
    val prompt: String,
    val chatId: Long,
    val messageId: Long
)

@Serializable
data class ImgGeneratedEvent(
    val key: String,
    val chatId: Long,
    val messageId: Long
)

const val DEFAULT_EXCHANGE = "tgsd"

fun main() {

    val connectionFactory = ConnectionFactory()

    with (connectionFactory) {
        setUri(AMPS_URL)
        useSslProtocol()
    }

    val rabbit = Rabbit(
        exchangeName = DEFAULT_EXCHANGE,
        connectionFactory = connectionFactory,
    )

    with (rabbit) {
        defaultExchangeAndQueue()
    }

    val bot = bot {
        token = BOT_TOKEN
        dispatch {
            startMessage()
            promptMessage(rabbit)
        }
    }

    rabbit.listenToQueue(Rabbit.Queue.IMG) {
        val event = Json.decodeFromString<ImgGeneratedEvent>(it)
        bot.sendPhoto(
            ChatId.fromId(event.chatId),
            TelegramFile.ByUrl(event.key),
            replyToMessageId = event.messageId
        ).fold { err -> println("Error: ${err.string()}") }
    }

    bot.startPolling()
}

fun Dispatcher.startMessage() {
    val startMsgFilter = Filter.Custom { text?.startsWith("/start") ?: false }

    message(startMsgFilter) {
        bot.sendMessage(ChatId.fromId(message.chat.id), text = startMsg)
            .fold { err -> println("Error: ${err.string()}") }
    }
}

fun Dispatcher.promptMessage(rabbit: Rabbit) {
    val promptMsgFilter = Filter.Text

    message(promptMsgFilter) {

        message.replyToMessage?.let { return@message }

        val event = Txt2ImgEvent(
            message.text!!,
            message.chat.id,
            message.messageId
        )

        rabbit.sendToQueue(Rabbit.Queue.TXT2IMG, Json.encodeToString(event))
        bot.sendMessage(ChatId.fromId(message.chat.id), text = addedToQueue)
            .fold { err -> println("Error: ${err.string()}") }
    }
}

fun ResponseError.string(): String? {
    return errorBody?.string()
}