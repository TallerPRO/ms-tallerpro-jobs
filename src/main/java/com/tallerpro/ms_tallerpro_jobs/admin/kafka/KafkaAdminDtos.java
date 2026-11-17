package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/** DTO del administrador de Kafka (EP5): entrada validada y salida tipada, sin exponer clases del cliente Kafka. */
public final class KafkaAdminDtos {

    /** Nombres validos de topico en Kafka: letras, digitos, '.', '_' y '-', maximo 249 caracteres. */
    public static final String TOPIC_NAME_REGEX = "[a-zA-Z0-9._-]{1,249}";

    private KafkaAdminDtos() {
    }

    // ---------- Cluster ----------

    public record BrokerInfo(int id, String host, int port, String rack) {}

    public record ClusterInfo(String clusterId, BrokerInfo controller, List<BrokerInfo> brokers) {}

    // ---------- Topicos ----------

    public record TopicSummary(String name, int partitions, int replicationFactor, boolean internal) {}

    public record PartitionInfo(int partition, Integer leader, List<Integer> replicas, List<Integer> isr) {}

    /** source indica de donde sale el valor (DEFAULT_CONFIG, DYNAMIC_TOPIC_CONFIG, ...); los sensibles se enmascaran. */
    public record ConfigEntryInfo(String value, String source, boolean readOnly, boolean sensitive) {}

    public record TopicDetail(String name, boolean internal, int replicationFactor,
                              List<PartitionInfo> partitions, Map<String, ConfigEntryInfo> configs) {}

    public record CreateTopicRequest(
            @NotBlank @Pattern(regexp = TOPIC_NAME_REGEX, message = "nombre de topico invalido") String name,
            @Min(1) @Max(100) int partitions,
            @Min(1) @Max(5) int replicationFactor,
            Map<String, String> configs) {}

    /** set: configuraciones a fijar/modificar · delete: configuraciones a eliminar (vuelven al default del broker). */
    public record AlterTopicConfigsRequest(Map<String, String> set, List<String> delete) {}

    /** Kafka solo permite aumentar particiones; ojo: cambia el reparto por clave de los mensajes nuevos. */
    public record IncreasePartitionsRequest(@Min(1) @Max(100) int totalPartitions) {}

    // ---------- Consumer groups ----------

    public record ConsumerGroupSummary(String groupId, String state, String type) {}

    public record MemberInfo(String consumerId, String clientId, String host, List<String> assignment) {}

    /** committedOffset null = el grupo aun no confirma offsets en esa particion. */
    public record PartitionLag(String topic, int partition, Long committedOffset, long endOffset, Long lag) {}

    public record ConsumerGroupDetail(String groupId, String state, String type, Integer coordinator,
                                      List<MemberInfo> members, List<PartitionLag> offsets, long totalLag) {}

    public enum ResetStrategy { EARLIEST, LATEST, OFFSET }

    /** offset solo se usa con strategy=OFFSET (se aplica a todas las particiones del topico). */
    public record ResetOffsetsRequest(
            @NotBlank @Pattern(regexp = TOPIC_NAME_REGEX, message = "nombre de topico invalido") String topic,
            @NotNull ResetStrategy strategy,
            @Min(0) Long offset) {}
}
