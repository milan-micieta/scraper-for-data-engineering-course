package core.server.stream;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

/**
 * RabbitMQ (AMQP) connection over TLS (port 5671) to CloudAMQP.
 * Only active when amqp.theatre.enabled=true.
 *
 * Credentials come from env / application-prod.properties:
 *   amqp.host, amqp.port (5671), amqp.username, amqp.password, amqp.vhost
 */
@Configuration
@ConditionalOnProperty(name = "amqp.theatre.enabled", havingValue = "true")
public class CloudAmqpConfig {

    @Value("${amqp.host:swallow.lmq.cloudamqp.com}")
    private String host;
    @Value("${amqp.port:5671}")
    private int port;
    @Value("${amqp.username:}")
    private String username;
    @Value("${amqp.password:}")
    private String password;
    @Value("${amqp.vhost:}")
    private String vhost;

    @Bean
    public ConnectionFactory rabbitConnectionFactory() throws Exception {
        // CloudAMQP uses standard CA certs – trust default Java trust store.
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);

        com.rabbitmq.client.ConnectionFactory rabbitFactory = new com.rabbitmq.client.ConnectionFactory();
        rabbitFactory.setHost(host);
        rabbitFactory.setPort(port);
        rabbitFactory.setUsername(username);
        rabbitFactory.setPassword(password);
        rabbitFactory.setVirtualHost(vhost);
        rabbitFactory.useSslProtocol(sslContext);
        rabbitFactory.enableHostnameVerification();

        return new CachingConnectionFactory(rabbitFactory);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        // Send as raw String (JSON) – no converter surprises.
        template.setMessageConverter(new org.springframework.amqp.support.converter.SimpleMessageConverter());
        return template;
    }
}
