package ar.edu.unam.alemilanesas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cubre los handlers del @RestControllerAdvice sin pasar por HTTP. */
class ErroresConsultaProductoHttpTest {

    @Test
    void illegalArgumentDevuelve400SolicitudInvalida() {
        var advice = new ErroresConsultaProductoHttp();
        var r = advice.invalidos();
        assertEquals(400, r.getStatusCode().value());
        assertEquals("SOLICITUD_INVALIDA", r.getBody().get("codigo"));
    }

    @Test
    void securityExceptionDevuelve403OperacionProhibida() {
        var advice = new ErroresConsultaProductoHttp();
        var r = advice.prohibido();
        assertEquals(403, r.getStatusCode().value());
        assertEquals("OPERACION_PROHIBIDA", r.getBody().get("codigo"));
    }

    @Test
    void dataAccessExceptionDevuelve503PersistenciaNoDisponible() {
        var advice = new ErroresConsultaProductoHttp();
        var r = advice.persistencia();
        assertEquals(503, r.getStatusCode().value());
        assertEquals("PERSISTENCIA_NO_DISPONIBLE", r.getBody().get("codigo"));
    }
}
