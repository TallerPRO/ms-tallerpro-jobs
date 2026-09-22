package com.tallerpro.ms_tallerpro_jobs.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Servicio del catalogo ejecutado sobre la orden (mano de obra). Igual que en
 * RepuestoUtilizado, el precio queda congelado al momento de aplicarlo.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServicioAplicado {
    private UUID servicioId;
    private String nombre;
    private Integer cantidad;

    /** Precio unitario vigente al momento de aplicarlo (CLP). */
    private BigDecimal precioUnitario;

    /** precioUnitario x cantidad. Cero si aun no se conoce el precio. */
    public BigDecimal getSubtotal() {
        if (precioUnitario == null) {
            return BigDecimal.ZERO;
        }
        return precioUnitario.multiply(BigDecimal.valueOf(cantidad == null ? 1 : cantidad));
    }
}
