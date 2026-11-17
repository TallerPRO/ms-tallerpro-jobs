package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.AlterTopicConfigsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConsumerGroupDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.CreateTopicRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetOffsetsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetStrategy;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicDetail;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ciclo completo del administrador contra un broker Kafka real (embebido, KRaft):
 * topicos + configuraciones y consumer groups con lag y reseteo de offsets.
 */
class KafkaAdminServiceIntegrationTest {

    private static EmbeddedKafkaKraftBroker broker;
    private static KafkaAdminService service;

    @BeforeAll
    static void levantarKafka() {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("kafkaAdmin", new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString())));
        service = new KafkaAdminService(beans.getBeanProvider(KafkaAdmin.class), Set.of("jobs.events"), 15000);
    }

    @AfterAll
    static void detenerKafka() {
        service.cerrar();
        broker.destroy();
    }

    @Test
    void cicloDeVidaDeUnTopico() {
        TopicDetail creado = service.crearTopico(new CreateTopicRequest("it.pagos", 2, 1,
                Map.of("retention.ms", "604800000", "cleanup.policy", "delete")));

        assertThat(creado.partitions()).hasSize(2);
        assertThat(creado.configs().get("retention.ms").value()).isEqualTo("604800000");
        assertThat(creado.configs().get("retention.ms").source()).isEqualTo("DYNAMIC_TOPIC_CONFIG");
        assertThat(service.listarTopicos(false)).anyMatch(t -> t.name().equals("it.pagos") && t.partitions() == 2);

        // Crear dos veces -> 409
        assertThatThrownBy(() -> service.crearTopico(new CreateTopicRequest("it.pagos", 1, 1, null)))
                .satisfies(e -> assertThat(((KafkaAdminException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // Modificar y eliminar configuraciones
        TopicDetail modificado = service.modificarConfigs("it.pagos",
                new AlterTopicConfigsRequest(Map.of("cleanup.policy", "compact"), List.of("retention.ms")));
        assertThat(modificado.configs().get("cleanup.policy").value()).isEqualTo("compact");
        assertThat(modificado.configs().get("retention.ms").source()).isNotEqualTo("DYNAMIC_TOPIC_CONFIG");

        // Valor invalido -> lo rechaza el broker -> 400
        assertThatThrownBy(() -> service.modificarConfigs("it.pagos",
                new AlterTopicConfigsRequest(Map.of("cleanup.policy", "borrar-todo"), List.of())))
                .satisfies(e -> assertThat(((KafkaAdminException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // Aumentar particiones
        assertThat(service.aumentarParticiones("it.pagos", 4).partitions()).hasSize(4);

        // Eliminar
        service.eliminarTopico("it.pagos", false);
        assertThatThrownBy(() -> service.describirTopico("it.pagos"))
                .satisfies(e -> assertThat(((KafkaAdminException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void consumerGroupConLagYReseteoDeOffsets() {
        service.crearTopico(new CreateTopicRequest("it.eventos", 1, 1, null));
        producir("it.eventos", 5);

        // Un consumidor lee 5 mensajes y confirma, luego se producen 3 mas sin leer -> lag 3
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-grupo",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("it.eventos"));
            int leidos = 0;
            long limite = System.currentTimeMillis() + 20000;
            while (leidos < 5 && System.currentTimeMillis() < limite) {
                leidos += consumer.poll(Duration.ofMillis(500)).count();
            }
            assertThat(leidos).isEqualTo(5);
            consumer.commitSync();
        }
        producir("it.eventos", 3);

        assertThat(service.listarConsumerGroups()).anyMatch(g -> g.groupId().equals("it-grupo"));

        ConsumerGroupDetail detalle = service.describirConsumerGroup("it-grupo");
        assertThat(detalle.state()).isEqualTo("EMPTY");
        assertThat(detalle.totalLag()).isEqualTo(3);

        // Reprocesar todo: EARLIEST -> lag 8
        ConsumerGroupDetail reseteado = service.resetearOffsets("it-grupo",
                new ResetOffsetsRequest("it.eventos", ResetStrategy.EARLIEST, null));
        assertThat(reseteado.totalLag()).isEqualTo(8);

        // Saltar al final: LATEST -> lag 0
        assertThat(service.resetearOffsets("it-grupo",
                new ResetOffsetsRequest("it.eventos", ResetStrategy.LATEST, null)).totalLag()).isZero();

        service.eliminarConsumerGroup("it-grupo");
        assertThatThrownBy(() -> service.describirConsumerGroup("it-grupo"))
                .satisfies(e -> assertThat(((KafkaAdminException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void describeElCluster() {
        var cluster = service.describirCluster();
        assertThat(cluster.clusterId()).isNotBlank();
        assertThat(cluster.brokers()).hasSize(1);
    }

    private static void producir(String topico, int cantidad) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (int i = 0; i < cantidad; i++) {
                producer.send(new ProducerRecord<>(topico, "k" + i, "v" + i));
            }
            producer.flush();
        }
    }
}
