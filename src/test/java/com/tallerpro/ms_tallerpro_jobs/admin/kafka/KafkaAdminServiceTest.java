package com.tallerpro.ms_tallerpro_jobs.admin.kafka;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.AlterTopicConfigsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.CreateTopicRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetOffsetsRequest;
import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminDtos.ResetStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas del administrador de Kafka que se validan ANTES de llegar al broker
 * (sin Kafka levantado: el provider de KafkaAdmin esta vacio).
 */
class KafkaAdminServiceTest {

    private final KafkaAdminService service = new KafkaAdminService(
            new StaticListableBeanFactory().getBeanProvider(KafkaAdmin.class),
            Set.of("jobs.events", "audit.timeline"), 1000);

    private static HttpStatus statusDe(Throwable t) {
        return ((KafkaAdminException) t).getStatus();
    }

    @Test
    void rechazaConfiguracionesFueraDeLaListaPermitida() {
        var request = new CreateTopicRequest("nuevo", 3, 1, Map.of("retention.ms", "1000", "unclean.leader.election.enable", "true"));
        assertThatThrownBy(() -> service.crearTopico(request))
                .isInstanceOf(KafkaAdminException.class)
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("unclean.leader.election.enable");
    }

    @Test
    void modificarConfigsSinCambiosEsBadRequest() {
        assertThatThrownBy(() -> service.modificarConfigs("jobs.events", new AlterTopicConfigsRequest(Map.of(), List.of())))
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void noEliminaTopicosInternosDeKafka() {
        assertThatThrownBy(() -> service.eliminarTopico("__consumer_offsets", true))
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void topicoProtegidoExigeForce() {
        assertThatThrownBy(() -> service.eliminarTopico("jobs.events", false))
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("force=true");
    }

    @Test
    void resetConStrategyOffsetExigeOffset() {
        assertThatThrownBy(() -> service.resetearOffsets("ms-tallerpro-report",
                new ResetOffsetsRequest("jobs.events", ResetStrategy.OFFSET, null)))
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void sinKafkaConfiguradoResponde503() {
        assertThatThrownBy(() -> service.listarTopicos(false))
                .satisfies(e -> assertThat(statusDe(e)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
