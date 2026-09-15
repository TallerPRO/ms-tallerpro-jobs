package com.tallerpro.ms_tallerpro_jobs.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T-02.10: colas de comando (q.cmd.email, q.cmd.bay, q.cmd.quote) sobre el exchange cmd.direct,
 * cada una con su Dead Letter Queue asociada via cmd.dead.dlx (ADR-06).
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public DirectExchange cmdExchange(JobsProperties properties) {
        return new DirectExchange(properties.rabbitmq().exchangeCmd(), true, false);
    }

    @Bean
    public DirectExchange cmdDeadLetterExchange(JobsProperties properties) {
        return new DirectExchange(properties.rabbitmq().exchangeDlx(), true, false);
    }

    @Bean
    public Queue emailQueue(JobsProperties properties) {
        return buildQueue(properties.rabbitmq().queueEmail(), properties.rabbitmq().exchangeDlx());
    }

    @Bean
    public Queue bayQueue(JobsProperties properties) {
        return buildQueue(properties.rabbitmq().queueBay(), properties.rabbitmq().exchangeDlx());
    }

    @Bean
    public Queue quoteQueue(JobsProperties properties) {
        return buildQueue(properties.rabbitmq().queueQuote(), properties.rabbitmq().exchangeDlx());
    }

    @Bean
    public Queue emailDeadLetterQueue(JobsProperties properties) {
        return QueueBuilder.durable(properties.rabbitmq().queueEmail() + ".dlq").build();
    }

    @Bean
    public Queue bayDeadLetterQueue(JobsProperties properties) {
        return QueueBuilder.durable(properties.rabbitmq().queueBay() + ".dlq").build();
    }

    @Bean
    public Queue quoteDeadLetterQueue(JobsProperties properties) {
        return QueueBuilder.durable(properties.rabbitmq().queueQuote() + ".dlq").build();
    }

    @Bean
    public Binding bindEmailQueue(Queue emailQueue, DirectExchange cmdExchange, JobsProperties properties) {
        return BindingBuilder.bind(emailQueue).to(cmdExchange).with(properties.rabbitmq().queueEmail());
    }

    @Bean
    public Binding bindBayQueue(Queue bayQueue, DirectExchange cmdExchange, JobsProperties properties) {
        return BindingBuilder.bind(bayQueue).to(cmdExchange).with(properties.rabbitmq().queueBay());
    }

    @Bean
    public Binding bindQuoteQueue(Queue quoteQueue, DirectExchange cmdExchange, JobsProperties properties) {
        return BindingBuilder.bind(quoteQueue).to(cmdExchange).with(properties.rabbitmq().queueQuote());
    }

    @Bean
    public Binding bindEmailDlq(Queue emailDeadLetterQueue, DirectExchange cmdDeadLetterExchange, JobsProperties properties) {
        return BindingBuilder.bind(emailDeadLetterQueue).to(cmdDeadLetterExchange).with(properties.rabbitmq().queueEmail());
    }

    @Bean
    public Binding bindBayDlq(Queue bayDeadLetterQueue, DirectExchange cmdDeadLetterExchange, JobsProperties properties) {
        return BindingBuilder.bind(bayDeadLetterQueue).to(cmdDeadLetterExchange).with(properties.rabbitmq().queueBay());
    }

    @Bean
    public Binding bindQuoteDlq(Queue quoteDeadLetterQueue, DirectExchange cmdDeadLetterExchange, JobsProperties properties) {
        return BindingBuilder.bind(quoteDeadLetterQueue).to(cmdDeadLetterExchange).with(properties.rabbitmq().queueQuote());
    }

    private Queue buildQueue(String name, String dlx) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", dlx)
                .withArgument("x-dead-letter-routing-key", name)
                .build();
    }
}
