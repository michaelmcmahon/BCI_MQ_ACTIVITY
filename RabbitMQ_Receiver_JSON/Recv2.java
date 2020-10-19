//We need to import some classes
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

//Set up the class and name the queue
public class Recv2 
{
    private final static String QUEUE_NAME = "activity";

//then we can create a connection to the server
    public static void main(String[] argv) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("YOUR AWS EC2 IP ADDRESS"); 
        factory.setUsername("YOUR AWS EC2 RABBITMQ USERNAME");
        factory.setPassword("YOUR AWS EC2 RABBITMQ PASSWORD");
        factory.setVirtualHost("/");
        factory.setPort(5672);
        Connection connection = factory.newConnection();

//we open a channel, and declare the queue from which we're going to consume        
	Channel channel = connection.createChannel();
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

//tell server to deliver messages from queue async and provide callback to buffer messages 
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println(" [x] Received '" + message + "'");
        };
        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> { });
    }
}