package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.AlterTopicConfigsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.BrokerInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ClusterInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConfigEntryInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConsumerGroupDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConsumerGroupSummary;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.CreateTopicRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.MemberInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.PartitionInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.PartitionLag;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetOffsetsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicSummary;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Administrador de Kafka (EP5): crear, listar, modificar y eliminar topicos y sus configuraciones,
 * e inspeccionar/gestionar consumer groups (lag, reseteo de offsets, eliminacion).
 *
 * Usa el AdminClient de Kafka construido a partir de la misma configuracion (spring.kafka.*) que
 * el productor de este microservicio, de modo que apunta a los mismos brokers.
 * Cada operacion que modifica el cluster queda registrada en el log con el usuario que la ejecuto.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);

    /**
     * Configuraciones de topico que se permite fijar desde la API. Lista cerrada a proposito:
     * cubre retencion, compactacion, durabilidad y tamanos, y evita exponer cualquier otra
     * configuracion del broker.
     */
    static final Set<String> CONFIGS_PERMITIDAS = Set.of(
            "retention.ms", "retention.bytes", "cleanup.policy", "min.insync.replicas",
            "delete.retention.ms", "min.compaction.lag.ms", "max.compaction.lag.ms",
            "min.cleanable.dirty.ratio", "segment.ms", "segment.bytes",
            "max.message.bytes", "compression.type", "message.timestamp.type");

    private final ObjectProvider<KafkaAdmin> kafkaAdminProvider;
    private final Set<String> topicosProtegidos;
    private final long timeoutMs;

    private volatile Admin admin;

    public KafkaAdminService(ObjectProvider<KafkaAdmin> kafkaAdminProvider,
                             @Value("${tallerpro.kafka-admin.protected-topics:jobs.events,jobs.events.report.DLT,jobs.events.audit.DLT,audit.timeline,audit.timeline.DLT}")
                             Set<String> topicosProtegidos,
                             @Value("${tallerpro.kafka-admin.timeout-ms:10000}") long timeoutMs) {
        this.kafkaAdminProvider = kafkaAdminProvider;
        this.topicosProtegidos = Set.copyOf(topicosProtegidos);
        this.timeoutMs = timeoutMs;
    }

    // =====================================================================
    // Cluster
    // =====================================================================

    public ClusterInfo describirCluster() {
        var result = admin().describeCluster();
        List<BrokerInfo> brokers = esperar(result.nodes()).stream()
                .sorted(Comparator.comparingInt(Node::id))
                .map(KafkaAdminService::broker)
                .toList();
        return new ClusterInfo(esperar(result.clusterId()), broker(esperar(result.controller())), brokers);
    }

    // =====================================================================
    // Topicos
    // =====================================================================

    public List<TopicSummary> listarTopicos(boolean incluirInternos) {
        Set<String> nombres = esperar(admin().listTopics(new ListTopicsOptions().listInternal(incluirInternos)).names());
        if (nombres.isEmpty()) {
            return List.of();
        }
        return esperar(admin().describeTopics(nombres).allTopicNames()).values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(t -> new TopicSummary(t.name(), t.partitions().size(), factorReplicacion(t), t.isInternal()))
                .toList();
    }

    public TopicDetail describirTopico(String nombre) {
        TopicDescription descripcion = esperar(admin().describeTopics(List.of(nombre)).allTopicNames()).get(nombre);
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        Config config = esperar(admin().describeConfigs(List.of(recurso)).all()).get(recurso);

        Map<String, ConfigEntryInfo> configs = new TreeMap<>();
        for (ConfigEntry entry : config.entries()) {
            configs.put(entry.name(), new ConfigEntryInfo(
                    entry.isSensitive() ? "******" : entry.value(),
                    entry.source().name(), entry.isReadOnly(), entry.isSensitive()));
        }
        List<PartitionInfo> particiones = descripcion.partitions().stream()
                .map(p -> new PartitionInfo(p.partition(),
                        p.leader() != null ? p.leader().id() : null,
                        p.replicas().stream().map(Node::id).toList(),
                        p.isr().stream().map(Node::id).toList()))
                .toList();
        return new TopicDetail(descripcion.name(), descripcion.isInternal(), factorReplicacion(descripcion), particiones, configs);
    }

    public TopicDetail crearTopico(CreateTopicRequest request) {
        Map<String, String> configs = request.configs() == null ? Map.of() : request.configs();
        validarConfigs(configs.keySet());

        NewTopic topico = new NewTopic(request.name(), request.partitions(), (short) request.replicationFactor())
                .configs(configs);
        esperar(admin().createTopics(List.of(topico)).all());
        log.info("[KAFKA-ADMIN] {} creo el topico '{}' (particiones={}, replicas={}, configs={})",
                actor(), request.name(), request.partitions(), request.replicationFactor(), configs);
        return describirTopico(request.name());
    }

    public TopicDetail modificarConfigs(String nombre, AlterTopicConfigsRequest request) {
        Map<String, String> fijar = request.set() == null ? Map.of() : request.set();
        List<String> eliminar = request.delete() == null ? List.of() : request.delete();
        if (fijar.isEmpty() && eliminar.isEmpty()) {
            throw new KafkaAdminException(HttpStatus.BAD_REQUEST, "Debes indicar al menos una configuracion en 'set' o 'delete'");
        }
        validarConfigs(fijar.keySet());
        validarConfigs(eliminar);

        List<AlterConfigOp> operaciones = new ArrayList<>();
        fijar.forEach((k, v) -> operaciones.add(new AlterConfigOp(new ConfigEntry(k, v), AlterConfigOp.OpType.SET)));
        eliminar.forEach(k -> operaciones.add(new AlterConfigOp(new ConfigEntry(k, null), AlterConfigOp.OpType.DELETE)));

        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        esperar(admin().incrementalAlterConfigs(Map.of(recurso, operaciones)).all());
        log.info("[KAFKA-ADMIN] {} modifico configs del topico '{}': set={} delete={}", actor(), nombre, fijar, eliminar);
        return describirTopico(nombre);
    }

    public TopicDetail aumentarParticiones(String nombre, int totalParticiones) {
        esperar(admin().createPartitions(Map.of(nombre, NewPartitions.increaseTo(totalParticiones))).all());
        log.info("[KAFKA-ADMIN] {} aumento las particiones del topico '{}' a {}", actor(), nombre, totalParticiones);
        return describirTopico(nombre);
    }

    /**
     * Los topicos internos de Kafka (prefijo "__") nunca se eliminan. Los topicos del proyecto
     * (jobs.events, audit.timeline y los DLT por consumidor) exigen force=true para evitar borrarlos por error.
     */
    public void eliminarTopico(String nombre, boolean force) {
        if (nombre.startsWith("__")) {
            throw new KafkaAdminException(HttpStatus.BAD_REQUEST, "No se permite eliminar topicos internos de Kafka: " + nombre);
        }
        if (topicosProtegidos.contains(nombre) && !force) {
            throw new KafkaAdminException(HttpStatus.CONFLICT,
                    "El topico '" + nombre + "' es parte de la arquitectura de TallerPro; usa force=true para eliminarlo");
        }
        esperar(admin().deleteTopics(List.of(nombre)).all());
        log.warn("[KAFKA-ADMIN] {} elimino el topico '{}' (force={})", actor(), nombre, force);
    }

    // =====================================================================
    // Consumer groups
    // =====================================================================

    public List<ConsumerGroupSummary> listarConsumerGroups() {
        return esperar(admin().listGroups(ListGroupsOptions.forConsumerGroups()).all()).stream()
                .map(g -> new ConsumerGroupSummary(g.groupId(),
                        g.groupState().map(Enum::name).orElse("UNKNOWN"),
                        g.type().map(Enum::name).orElse("UNKNOWN")))
                .sorted(Comparator.comparing(ConsumerGroupSummary::groupId))
                .toList();
    }

    /** Estado, miembros con sus particiones asignadas y lag (end offset - offset confirmado) por particion. */
    public ConsumerGroupDetail describirConsumerGroup(String groupId) {
        ConsumerGroupDescription grupo = buscarGrupo(groupId);

        Map<TopicPartition, OffsetAndMetadata> confirmados =
                esperar(admin().listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata());

        // Particiones a reportar: las que tienen offset confirmado + las asignadas a miembros activos.
        Set<TopicPartition> particiones = new java.util.HashSet<>(confirmados.keySet());
        grupo.members().forEach(m -> particiones.addAll(m.assignment().topicPartitions()));

        Map<TopicPartition, ListOffsetsResultInfo> finales = finalesDe(particiones);

        List<PartitionLag> lags = particiones.stream()
                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                .map(tp -> {
                    OffsetAndMetadata oam = confirmados.get(tp);
                    long fin = finales.containsKey(tp) ? finales.get(tp).offset() : 0L;
                    Long confirmado = oam != null ? oam.offset() : null;
                    Long lag = confirmado != null ? Math.max(0, fin - confirmado) : null;
                    return new PartitionLag(tp.topic(), tp.partition(), confirmado, fin, lag);
                })
                .toList();
        long lagTotal = lags.stream().map(PartitionLag::lag).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();

        List<MemberInfo> miembros = grupo.members().stream()
                .map(m -> new MemberInfo(m.consumerId(), m.clientId(), m.host(),
                        m.assignment().topicPartitions().stream()
                                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                                .map(TopicPartition::toString)
                                .toList()))
                .toList();

        return new ConsumerGroupDetail(grupo.groupId(), grupo.groupState().name(), grupo.type().name(),
                grupo.coordinator() != null ? grupo.coordinator().id() : null, miembros, lags, lagTotal);
    }

    /**
     * Reposiciona los offsets del grupo en todas las particiones de un topico (p. ej. EARLIEST para
     * reprocesar el historial de jobs.events en report). Kafka exige que el grupo no tenga consumidores
     * activos, por eso se valida antes y se responde 409 con una explicacion clara.
     */
    public ConsumerGroupDetail resetearOffsets(String groupId, ResetOffsetsRequest request) {
        if (request.strategy() == KafkaAdminDtos.ResetStrategy.OFFSET && request.offset() == null) {
            throw new KafkaAdminException(HttpStatus.BAD_REQUEST, "strategy=OFFSET requiere el campo 'offset'");
        }
        ConsumerGroupDescription grupo = buscarGrupo(groupId);
        if (grupo.groupState() != GroupState.EMPTY) {
            throw new KafkaAdminException(HttpStatus.CONFLICT,
                    "El grupo '" + groupId + "' esta " + grupo.groupState()
                            + "; deten sus consumidores antes de resetear offsets");
        }

        TopicDescription topico = esperar(admin().describeTopics(List.of(request.topic())).allTopicNames()).get(request.topic());
        List<TopicPartition> particiones = topico.partitions().stream()
                .map(TopicPartitionInfo::partition)
                .map(p -> new TopicPartition(request.topic(), p))
                .toList();

        Map<TopicPartition, OffsetAndMetadata> nuevos = new HashMap<>();
        switch (request.strategy()) {
            case OFFSET -> particiones.forEach(tp -> nuevos.put(tp, new OffsetAndMetadata(request.offset())));
            case EARLIEST, LATEST -> {
                OffsetSpec spec = request.strategy() == KafkaAdminDtos.ResetStrategy.EARLIEST ? OffsetSpec.earliest() : OffsetSpec.latest();
                Map<TopicPartition, OffsetSpec> consulta = new HashMap<>();
                particiones.forEach(tp -> consulta.put(tp, spec));
                esperar(admin().listOffsets(consulta).all())
                        .forEach((tp, info) -> nuevos.put(tp, new OffsetAndMetadata(info.offset())));
            }
        }

        esperar(admin().alterConsumerGroupOffsets(groupId, nuevos).all());
        log.warn("[KAFKA-ADMIN] {} reseteo offsets del grupo '{}' en '{}' a {}{}", actor(), groupId, request.topic(),
                request.strategy(), request.strategy() == KafkaAdminDtos.ResetStrategy.OFFSET ? " (" + request.offset() + ")" : "");
        return describirConsumerGroup(groupId);
    }

    public void eliminarConsumerGroup(String groupId) {
        esperar(admin().deleteConsumerGroups(List.of(groupId)).all());
        log.warn("[KAFKA-ADMIN] {} elimino el consumer group '{}'", actor(), groupId);
    }

    // =====================================================================
    // Internos
    // =====================================================================

    private ConsumerGroupDescription buscarGrupo(String groupId) {
        ConsumerGroupDescription grupo = esperar(admin().describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        if (grupo == null || grupo.groupState() == GroupState.DEAD) {
            throw new KafkaAdminException(HttpStatus.NOT_FOUND, "Consumer group no encontrado: " + groupId);
        }
        return grupo;
    }

    private Map<TopicPartition, ListOffsetsResultInfo> finalesDe(Collection<TopicPartition> particiones) {
        if (particiones.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> consulta = new LinkedHashMap<>();
        particiones.forEach(tp -> consulta.put(tp, OffsetSpec.latest()));
        return esperar(admin().listOffsets(consulta).all());
    }

    private static void validarConfigs(Collection<String> claves) {
        List<String> noPermitidas = claves.stream().filter(k -> !CONFIGS_PERMITIDAS.contains(k)).sorted().toList();
        if (!noPermitidas.isEmpty()) {
            throw new KafkaAdminException(HttpStatus.BAD_REQUEST,
                    "Configuraciones no permitidas: " + noPermitidas + ". Permitidas: " + new java.util.TreeSet<>(CONFIGS_PERMITIDAS));
        }
    }

    private static int factorReplicacion(TopicDescription t) {
        return t.partitions().isEmpty() ? 0 : t.partitions().getFirst().replicas().size();
    }

    private static BrokerInfo broker(Node n) {
        return n == null ? null : new BrokerInfo(n.id(), n.host(), n.port(), n.rack());
    }

    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "desconocido";
    }

    /** AdminClient perezoso: no se conecta a Kafka hasta la primera llamada de administracion. */
    private Admin admin() {
        Admin actual = admin;
        if (actual == null) {
            synchronized (this) {
                if (admin == null) {
                    KafkaAdmin kafkaAdmin = kafkaAdminProvider.getIfAvailable();
                    if (kafkaAdmin == null) {
                        throw new KafkaAdminException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka no esta configurado en este entorno");
                    }
                    admin = Admin.create(kafkaAdmin.getConfigurationProperties());
                }
                actual = admin;
            }
        }
        return actual;
    }

    /** Espera el resultado con timeout y traduce los errores de Kafka a codigos HTTP. */
    private <T> T esperar(KafkaFuture<T> future) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaAdminException(HttpStatus.SERVICE_UNAVAILABLE, "Operacion interrumpida", e);
        } catch (TimeoutException e) {
            throw new KafkaAdminException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka no respondio en " + timeoutMs + " ms; revisa que los brokers esten disponibles", e);
        } catch (ExecutionException e) {
            throw traducir(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static KafkaAdminException traducir(Throwable causa) {
        String mensaje = causa.getMessage();
        if (causa instanceof UnknownTopicOrPartitionException || causa instanceof GroupIdNotFoundException) {
            return new KafkaAdminException(HttpStatus.NOT_FOUND, mensaje, causa);
        }
        if (causa instanceof TopicExistsException || causa instanceof GroupNotEmptyException) {
            return new KafkaAdminException(HttpStatus.CONFLICT, mensaje, causa);
        }
        if (causa instanceof RetriableException) {
            // Incluye org.apache.kafka.common.errors.TimeoutException: broker caido o sin lider.
            return new KafkaAdminException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka no disponible: " + mensaje, causa);
        }
        if (causa instanceof ApiException) {
            // InvalidTopic, InvalidPartitions, InvalidReplicationFactor, InvalidConfiguration, PolicyViolation...
            return new KafkaAdminException(HttpStatus.BAD_REQUEST, mensaje, causa);
        }
        log.error("[KAFKA-ADMIN] Error inesperado del AdminClient", causa);
        return new KafkaAdminException(HttpStatus.BAD_GATEWAY, "Error inesperado de Kafka: " + mensaje, causa);
    }

    @PreDestroy
    void cerrar() {
        if (admin != null) {
            admin.close(java.time.Duration.ofSeconds(5));
        }
    }
}
