package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.DatosPreparacion;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService.PreparacionRegistrada;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService;
import ar.edu.unam.alemilanesas.ventas.BuscarPedidoService.PedidoProduccion;
import ar.edu.unam.alemilanesas.web.SeguridadHttpInterna.PrincipalInterno;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contrato MVC del vertical UC-28:adaptadores, errores de catálogo y shouting de costos. */
class PrepararPedidoDemoMvcTest {
    final UUID sesion = UUID.randomUUID();
    final UsernamePasswordAuthenticationToken produccion = new UsernamePasswordAuthenticationToken(
            new PrincipalInterno(sesion, 7), null,
            List.of(new SimpleGrantedAuthority("RESPONSABLE_PRODUCCION")));

    MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErroresDemoAdvice()).build();
    }

    RegistrarPreparacionService servi() { return mock(RegistrarPreparacionService.class); }
    BuscarPedidoService pedidos() { return mock(BuscarPedidoService.class); }
    PedidoProduccion pedido() {
        return new PedidoProduccion(12L, 9L, LocalDate.of(2026, 12, 1), "PENDIENTE", "INTERNO");
    }
    void contexto(BuscarPedidoService pedidos) {
        when(pedidos.buscarPedido(eq(sesion), any())).thenReturn(List.of(pedido()));
    }

    @Test
    void getAutorizadoMuestraContextoBC07YCantidad() throws Exception {
        var servicio = servi(); var pedidos = pedidos(); contexto(pedidos);
        var m = mvc(new PrepararPedidoDemoController(servicio, pedidos));
        var respuesta = m.perform(get("/web/demo/pedidos/12/preparar").principal(produccion))
                .andExpect(status().isOk()).andExpect(view().name("demo/pedido-preparar"))
                .andExpect(model().attribute("idPedido", 12L))
                .andExpect(model().attributeExists("pedido", "form", "aviso"))
                .andReturn();
        verify(pedidos).buscarPedido(sesion, new BuscarPedidoService.CriterioBusquedaPedido(12L, null, null, null, null));
        var claves = respuesta.getModelAndView().getModel().keySet();
        assertFalse(claves.stream().anyMatch(k -> k.toLowerCase().contains("total")));
        assertFalse(claves.stream().anyMatch(k -> k.toLowerCase().contains("precio")));
        assertFalse(claves.stream().anyMatch(k -> k.toLowerCase().contains("costo")));
    }

    @Test
    void postAutorizadoUsaServicioIntegradoYConfirmaIdReal() throws Exception {
        var servicio = servi(); var pedidos = pedidos(); contexto(pedidos);
        when(servicio.registrarPreparacionPedido(eq(sesion), any())).thenReturn(
                new PreparacionRegistrada(77L, 12L, 5L, 1, new BigDecimal("2.000"),
                        OffsetDateTime.parse("2026-11-22T10:00:00Z"), 2));
        var m = mvc(new PrepararPedidoDemoController(servicio, pedidos));
        m.perform(post("/web/demo/pedidos/12/preparar").principal(produccion)
                        .param("idProducto", "5").param("cantidadPreparada", "2.000"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/web/demo/pedidos/12/preparar?confirmada=77"));
        var captor = ArgumentCaptor.forClass(DatosPreparacion.class);
        verify(servicio).registrarPreparacionPedido(eq(sesion), captor.capture());
        assertEquals(12L, captor.getValue().idPedido());
        assertEquals(5L, captor.getValue().idProducto());
        assertEquals(new BigDecimal("2.000"), captor.getValue().cantidadPreparada());
        assertNull(captor.getValue().partidas());
    }

    @Test
    void postConCantidadNoNumericaNoLlamaAlServicio() throws Exception {
        var servicio = servi(); var pedidos = pedidos(); contexto(pedidos);
        var m = mvc(new PrepararPedidoDemoController(servicio, pedidos));
        m.perform(post("/web/demo/pedidos/12/preparar").principal(produccion)
                        .param("idProducto", "5").param("cantidadPreparada", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("codigo", "SOLICITUD_INVALIDA"))
                .andExpect(view().name("demo/pedido-preparar"));
        verifyNoInteractions(servicio);
    }

    @Test
    void erroresDeDominioSeMuestranComoCatalogo() throws Exception {
        var servicio = servi(); var pedidos = pedidos(); contexto(pedidos);
        when(servicio.registrarPreparacionPedido(eq(sesion), any()))
                .thenThrow(new IllegalArgumentException("CANTIDAD_EXCEDE_ORIGINAL"))
                .thenThrow(new IllegalArgumentException("STOCK_UTILIZABLE_INSUFICIENTE"));
        var m = mvc(new PrepararPedidoDemoController(servicio, pedidos));
        var cuerpo = post("/web/demo/pedidos/12/preparar").principal(produccion)
                .param("idProducto", "5").param("cantidadPreparada", "99");
        m.perform(cuerpo).andExpect(status().isBadRequest())
                .andExpect(model().attribute("codigo", "CANTIDAD_EXCEDE_ORIGINAL"));
        m.perform(cuerpo).andExpect(status().isConflict())
                .andExpect(model().attribute("codigo", "STOCK_UTILIZABLE_INSUFICIENTE"));
    }

    @Test
    void pedidoFueraDeAmbitoSinExponerDetalle() throws Exception {
        var servicio = servi(); var pedidos = pedidos();
        when(pedidos.buscarPedido(eq(sesion), any()))
                .thenThrow(new BuscarPedidoService.FueraDeContextoException());
        var m = mvc(new PrepararPedidoDemoController(servicio, pedidos));
        var r = m.perform(get("/web/demo/pedidos/12/preparar").principal(produccion))
                .andExpect(status().isForbidden())
                .andExpect(model().attribute("codigo", "OPERACION_PROHIBIDA"))
                .andReturn();
        assertFalse(r.getResponse().getContentAsString().contains("FUERA_DE_CONTEXTO"));
        verifyNoInteractions(servicio);
    }
}
