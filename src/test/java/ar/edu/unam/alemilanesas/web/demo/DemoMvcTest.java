package ar.edu.unam.alemilanesas.web.demo;

import ar.edu.unam.alemilanesas.maestros.*;
import ar.edu.unam.alemilanesas.ventas.*;
import ar.edu.unam.alemilanesas.web.SeguridadHttpInterna.PrincipalInterno;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contrato de adaptadores/modelos; no acredita render ni navegador. */
class DemoMvcTest {
    final UUID sesion=UUID.randomUUID();
    final UsernamePasswordAuthenticationToken actor=new UsernamePasswordAuthenticationToken(
            new PrincipalInterno(sesion,7),null,List.of(new SimpleGrantedAuthority("VENDEDOR")));
    MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErroresDemoAdvice()).build();
    }
    @Test void consultasUsanSesionYProyeccionesB3SinEnriquecer() throws Exception {
        var clientes=mock(BuscarClienteService.class);var productos=mock(BuscarProductoService.class);var pedidos=mock(BuscarPedidoService.class);
        var c=new ConsultasDemoController(clientes,productos,pedidos);var m=mvc(c);
        when(clientes.buscarCliente(eq(sesion),any())).thenReturn(List.of());
        m.perform(get("/web/demo/clientes").principal(actor).param("nombre","Sintético"))
                .andExpect(status().isOk()).andExpect(view().name("demo/clientes")).andExpect(model().attribute("clientes",List.of()));
        verify(clientes).buscarCliente(sesion,new CriterioBusquedaCliente(null,"Sintético",null,null,null));
        m.perform(get("/web/demo/productos").principal(actor)).andExpect(model().attributeExists("avisoPrecio"));
        verify(productos).buscarProducto(eq(sesion),any());
        m.perform(get("/web/demo/pedidos").principal(actor).param("idPedido","12"))
                .andExpect(view().name("demo/pedidos"));
        verify(pedidos).buscarPedido(sesion,new BuscarPedidoService.CriterioBusquedaPedido(12L,null,null,null,null));
        m.perform(get("/web/demo/clientes").principal(actor).param("idSesion","otro"))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("codigo","SOLICITUD_INVALIDA"));
        m.perform(get("/web/demo/productos").principal(actor).param("activo","yes")).andExpect(status().isBadRequest());
    }
    @Test void tresRenglonesIdempotenciaY303() throws Exception {
        var service=mock(CargarPedidoService.class);var m=mvc(new CargarPedidoDemoController(service));
        String clave=UUID.randomUUID().toString();
        when(service.cargarPedido(eq(sesion),any())).thenReturn(new CargarPedidoService.PedidoCreado(42,"PENDIENTE","INTERNO",LocalDate.now(),"CUMPLIDOR",BigDecimal.TEN,1,true));
        var request=post("/web/demo/pedidos").principal(actor).param("idCliente","9").param("fechaReparto","2026-11-18")
                .param("claveIdempotencia",clave).param("items[0].idProducto","3").param("items[0].cantidadSolicitada","1.250")
                .param("items[1].idProducto","").param("items[1].cantidadSolicitada","")
                .param("calle","Calle sintética").param("localidad","Posadas");
        m.perform(request).andExpect(status().isSeeOther()).andExpect(header().string("Location","/web/demo/pedidos?idPedido=42"));
        var captor=org.mockito.ArgumentCaptor.forClass(CargarPedidoService.DatosPedido.class);
        verify(service).cargarPedido(eq(sesion),captor.capture());
        assertEquals(UUID.fromString(clave),captor.getValue().claveIdempotencia());
        assertEquals(1,captor.getValue().items().size());
        assertEquals(new BigDecimal("1.250"),captor.getValue().items().getFirst().cantidadSolicitada());
        when(service.cargarPedido(eq(sesion),any())).thenThrow(new IllegalArgumentException("SQL secreto"));
        var resultado=m.perform(request).andExpect(status().isBadRequest()).andExpect(view().name("demo/pedido-nuevo"))
                .andExpect(model().attribute("codigo","SOLICITUD_INVALIDA")).andReturn();
        assertEquals(clave,((Map<?,?>)resultado.getModelAndView().getModel().get("form")).get("claveIdempotencia"));
        assertFalse(resultado.getModelAndView().getModel().toString().contains("SQL secreto"));
    }
    @Test void asignacionInicialMotivoVacioSeConvierteEnNulo() throws Exception {
        var reparto=mock(AsignarRepartoService.class);var pedidos=mock(BuscarPedidoService.class);
        var m=mvc(new AsignarRepartoDemoController(reparto,pedidos));
        m.perform(post("/web/demo/pedidos/8/asignar").principal(actor).param("idUsuarioRepartidor","10").param("motivoReasignacion",""))
                .andExpect(status().isSeeOther());
        verify(reparto).asignarOReasignar(sesion,new AsignarRepartoService.DatosAsignacion(8,10,null));
        when(reparto.asignarOReasignar(eq(sesion),any())).thenThrow(new IllegalArgumentException("Pedido no está PENDIENTE"));
        m.perform(post("/web/demo/pedidos/8/asignar").principal(actor).param("idUsuarioRepartidor","10"))
                .andExpect(status().isConflict()).andExpect(model().attribute("codigo","PEDIDO_NO_PENDIENTE"));
        when(pedidos.buscarPedido(eq(sesion),any())).thenReturn(List.of());
        m.perform(get("/web/demo/pedidos/8/asignar").principal(actor)).andExpect(status().isNotFound());
    }
    @Test void misEntregasEsSoloInformativaSinCamposOperativos() throws Exception {
        var r=mvc(new MisEntregasDemoController()).perform(get("/web/demo/mis-entregas"))
                .andExpect(view().name("demo/mis-entregas")).andReturn();
        assertTrue(r.getModelAndView().getModel().keySet().containsAll(Set.of("mensaje","aviso")));
        assertFalse(r.getModelAndView().getModel().containsKey("pedidos"));
    }
    @Test void erroresDeInfraestructuraNoFiltranDetalles() throws Exception {
        var clientes=mock(BuscarClienteService.class);
        when(clientes.buscarCliente(eq(sesion),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("password secreto"));
        mvc(new ConsultasDemoController(clientes,mock(BuscarProductoService.class),mock(BuscarPedidoService.class)))
                .perform(get("/web/demo/clientes").principal(actor)).andExpect(status().isServiceUnavailable())
                .andExpect(model().attribute("codigo","PERSISTENCIA_NO_DISPONIBLE"));
    }
}
