package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.AlterTopicConfigsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ClusterInfo;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConsumerGroupDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ConsumerGroupSummary;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.CreateTopicRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.IncreasePartitionsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetOffsetsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicDetail;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TopicSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.TOPIC_NAME_REGEX;

/**
 * Microservicio administrador de Kafka (EP5), alojado en ms-tallerpro-jobs por ser el dueno
 * del topico jobs.events. Expuesto por el gateway en /api/v1/admin/kafka/** y restringido al
 * rol Admin: es una capa de gestion de la plataforma, no de negocio.
 */
@RestController
@RequestMapping("/api/v1/admin/kafka")
@PreAuthorize("hasRole('Admin')")
@Tag(name = "Administrador Kafka", description = "Topicos, configuraciones y consumer groups (solo Admin)")
public class KafkaAdminController {

    private final KafkaAdminService service;

    public KafkaAdminController(KafkaAdminService service) {
        this.service = service;
    }

    // ---------- Cluster ----------

    @GetMapping("/cluster")
    @Operation(summary = "Describe el cluster: id, controller y brokers")
    public ClusterInfo cluster() {
        return service.describirCluster();
    }

    // ---------- Topicos ----------

    @GetMapping("/topics")
    @Operation(summary = "Lista topicos con particiones y factor de replicacion")
    public List<TopicSummary> listarTopicos(@RequestParam(defaultValue = "false") boolean includeInternal) {
        return service.listarTopicos(includeInternal);
    }

    @GetMapping("/topics/{name}")
    @Operation(summary = "Detalle de un topico: lider/replicas/ISR por particion y configuraciones")
    public TopicDetail describirTopico(@PathVariable @Pattern(regexp = TOPIC_NAME_REGEX) String name) {
        return service.describirTopico(name);
    }

    @PostMapping("/topics")
    @Operation(summary = "Crea un topico con particiones, replicas y configuraciones (retencion, cleanup.policy, ...)")
    public ResponseEntity<TopicDetail> crearTopico(@Valid @RequestBody CreateTopicRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearTopico(request));
    }

    @PatchMapping("/topics/{name}/configs")
    @Operation(summary = "Modifica (set) o elimina (delete) configuraciones de un topico")
    public TopicDetail modificarConfigs(@PathVariable @Pattern(regexp = TOPIC_NAME_REGEX) String name,
                                        @RequestBody AlterTopicConfigsRequest request) {
        return service.modificarConfigs(name, request);
    }

    @PostMapping("/topics/{name}/partitions")
    @Operation(summary = "Aumenta el numero de particiones de un topico (Kafka no permite reducirlas)")
    public TopicDetail aumentarParticiones(@PathVariable @Pattern(regexp = TOPIC_NAME_REGEX) String name,
                                           @Valid @RequestBody IncreasePartitionsRequest request) {
        return service.aumentarParticiones(name, request.totalPartitions());
    }

    @DeleteMapping("/topics/{name}")
    @Operation(summary = "Elimina un topico; los del proyecto requieren force=true y los internos (__*) no se eliminan")
    public ResponseEntity<Void> eliminarTopico(@PathVariable @Pattern(regexp = TOPIC_NAME_REGEX) String name,
                                               @RequestParam(defaultValue = "false") boolean force) {
        service.eliminarTopico(name, force);
        return ResponseEntity.noContent().build();
    }

    // ---------- Consumer groups ----------

    @GetMapping("/consumer-groups")
    @Operation(summary = "Lista consumer groups y su estado")
    public List<ConsumerGroupSummary> listarConsumerGroups() {
        return service.listarConsumerGroups();
    }

    @GetMapping("/consumer-groups/{groupId}")
    @Operation(summary = "Detalle de un consumer group: miembros, particiones asignadas y lag por particion")
    public ConsumerGroupDetail describirConsumerGroup(@PathVariable String groupId) {
        return service.describirConsumerGroup(groupId);
    }

    @PostMapping("/consumer-groups/{groupId}/reset-offsets")
    @Operation(summary = "Resetea offsets del grupo en un topico (EARLIEST, LATEST u OFFSET); el grupo debe estar sin consumidores")
    public ConsumerGroupDetail resetearOffsets(@PathVariable String groupId,
                                               @Valid @RequestBody ResetOffsetsRequest request) {
        return service.resetearOffsets(groupId, request);
    }

    @DeleteMapping("/consumer-groups/{groupId}")
    @Operation(summary = "Elimina un consumer group sin consumidores activos")
    public ResponseEntity<Void> eliminarConsumerGroup(@PathVariable String groupId) {
        service.eliminarConsumerGroup(groupId);
        return ResponseEntity.noContent().build();
    }
}
