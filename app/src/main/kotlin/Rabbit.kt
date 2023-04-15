import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.ConnectionFactory

class Rabbit(
    private val exchangeName: String,
    private val connectionFactory: ConnectionFactory
) {
    enum class Queue(val queueName: String) {
        TXT2IMG("txt2img"),
        IMG("img")
    }

    fun defaultExchangeAndQueue(): Rabbit {
        val connection = connectionFactory.newConnection()
        val channel = connection.createChannel()

        channel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC, true)

        channel.queueDeclare(Queue.TXT2IMG.queueName, true, false, false, emptyMap())
        channel.queueBind(Queue.TXT2IMG.queueName, exchangeName, Queue.TXT2IMG.queueName)

        channel.queueDeclare(Queue.IMG.queueName, true, false, false, emptyMap())
        channel.queueBind(Queue.IMG.queueName, exchangeName, Queue.IMG.queueName)

        channel.close()
        connection.close()

        return this
    }

    fun sendToQueue(queue: Queue, message: String) {
        val connection = connectionFactory.newConnection()
        val channel = connection.createChannel()

        channel.basicPublish(exchangeName, queue.queueName, null, message.toByteArray())

        channel.close()
        connection.close()
    }

    fun listenToQueue(queue: Queue, callback: (String) -> Unit) : Rabbit {
        val connection = connectionFactory.newConnection()
        val channel = connection.createChannel()

        channel.basicConsume(
            queue.queueName,
            true,
            { _, message -> callback(String(message.body)) },
            { _ -> }
        )

        return this
    }
}