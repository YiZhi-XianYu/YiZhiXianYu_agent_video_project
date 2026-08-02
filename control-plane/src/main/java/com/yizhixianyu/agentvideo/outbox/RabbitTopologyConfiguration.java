package com.yizhixianyu.agentvideo.outbox;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@ConditionalOnProperty(name = "app.messaging.rabbit.enabled", havingValue = "true")
public class RabbitTopologyConfiguration {
    @Bean TopicExchange taskExchange(@Value("${app.messaging.rabbit.task-exchange:avp.task.v1}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
    @Bean Queue lightQueue() { return workerQueue("avp.task.light.v1"); }
    @Bean TopicExchange deadExchange() { return ExchangeBuilder.topicExchange("avp.dead.v1").durable(true).build(); }
    @Bean Queue deadQueue() { return QueueBuilder.durable("avp.task.dead.v1").build(); }
    @Bean Binding deadBinding(@Qualifier("deadExchange") TopicExchange deadExchange, @Qualifier("deadQueue") Queue deadQueue) { return BindingBuilder.bind(deadQueue).to(deadExchange).with("#"); }
    private Queue workerQueue(String name) { return QueueBuilder.durable(name).withArgument("x-dead-letter-exchange", "avp.dead.v1").build(); }
    @Bean Queue mediaQueue() { return workerQueue("avp.task.media.v1"); }
    @Bean Queue modelQueue() { return workerQueue("avp.task.model.v1"); }
    @Bean Queue renderQueue() { return workerQueue("avp.task.render.v1"); }
    @Bean Binding lightBinding(@Qualifier("taskExchange") TopicExchange exchange, @Qualifier("lightQueue") Queue queue) { return BindingBuilder.bind(queue).to(exchange).with("task.light.requested"); }
    @Bean Binding mediaBinding(@Qualifier("taskExchange") TopicExchange exchange, @Qualifier("mediaQueue") Queue queue) { return BindingBuilder.bind(queue).to(exchange).with("task.media.requested"); }
    @Bean Binding modelBinding(@Qualifier("taskExchange") TopicExchange exchange, @Qualifier("modelQueue") Queue queue) { return BindingBuilder.bind(queue).to(exchange).with("task.model.requested"); }
    @Bean Binding renderBinding(@Qualifier("taskExchange") TopicExchange exchange, @Qualifier("renderQueue") Queue queue) { return BindingBuilder.bind(queue).to(exchange).with("task.render.requested"); }
}
