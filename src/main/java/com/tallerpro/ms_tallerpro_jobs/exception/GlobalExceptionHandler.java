package com.tallerpro.ms_tallerpro_jobs.exception;

import com.tallerpro.ms_tallerpro_jobs.admin.kafka.KafkaAdminException;
import com.tallerpro.ms_tallerpro_jobs.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrdenNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(OrdenNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(EstadoInvalidoException.class)
    public ResponseEntity<ApiError> handleEstadoInvalido(EstadoInvalidoException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(RecursoNoDisponibleException.class)
    public ResponseEntity<ApiError> handleRecursoNoDisponible(RecursoNoDisponibleException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String mensaje = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, mensaje, req);
    }

    /** Restricciones sobre @PathVariable/@RequestParam (p. ej. nombre de topico invalido). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Parametros invalidos en la peticion", req);
    }

    /** JSON malformado o valor de enum desconocido en el body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Cuerpo de la peticion invalido o malformado", req);
    }

    /** Administrador de Kafka: el servicio ya tradujo el error de Kafka a un codigo HTTP. */
    @ExceptionHandler(KafkaAdminException.class)
    public ResponseEntity<ApiError> handleKafkaAdmin(KafkaAdminException ex, HttpServletRequest req) {
        return build(ex.getStatus(), ex.getMessage(), req);
    }

    /** Capa de autorizacion (ARQUITECTURA_ACCESO.md, seccion 8): rol o alcance insuficiente -> 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "No tienes permisos para esta accion", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno: " + ex.getMessage(), req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String mensaje, HttpServletRequest req) {
        ApiError error = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), mensaje, req.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }
}
