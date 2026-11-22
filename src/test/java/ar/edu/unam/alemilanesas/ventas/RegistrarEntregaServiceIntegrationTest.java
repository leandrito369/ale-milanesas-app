package ar.edu.unam.alemilanesas.ventas;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import ar.edu.unam.alemilanesas.finanzas.CobroManualService;
import ar.edu.unam.alemilanesas.finanzas.EvaluadorCategoriaComercialInterno;
import ar.edu.unam.alemilanesas.finanzas.ImputacionFinancieraService;
import ar.edu.unam.alemilanesas.produccioninventario.PreparacionFixture;
import ar.edu.unam.alemilanesas.produccioninventario.RegistrarPreparacionService;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.DatosEntrega;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.EntregaRegistrada;
import ar.edu.unam.alemilanesas.ventas.RegistrarEntregaService.ItemEntrega;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RegistrarEntregaServiceIntegrationTest {
    @Container
    static PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CargarPedidoService pedidos;
    @Autowired RegistrarPreparacionService preparaciones;
    @Autowired AsignarRepartoService asignaciones;
    @Autowired GestionarRecorridoRepartoService recorridos;
    @Autowired CobroManualService cobros;
    @Autowired ImputacionFinancieraService imputacion;
    @Autowired EvaluadorCategoriaComercialInterno evaluador;
    @Autowired RegistrarEntregaService entregas;

    private static void assertDec(String esperado, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(esperado).compareTo(actual),
                "Esperado=" + esperado + " pero actual=" + actual);
    }

    @Test
    void entregaCompletaElaboradoYReventaCalculaCeCongeladoYImpBConCeroCobros() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        long idElab = f.producto;
        long idReventa = f.actores.base + 1;

        // Preparar 10 ELABORADO reales + cargar REVENTA + abrir recorrido + reservar anticipo + credito libre
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("10"));

        long pedidoMixto = tx.execute(s -> {
            jdbc.update("""
                    UPDATE "PRODUCTO"
                       SET "tipoProducto" = 'REVENTA',
                           "stockActualReventa" = 5.000,
                           "costoPromedioActualReventa" = 60.0000
                     WHERE "idProducto" = ?
                    """, idReventa);
            return pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                    idCliente, f.actores.hoy.plusDays(1),
                    List.of(
                            new CargarPedidoService.ItemPedido(idElab, new BigDecimal("2.000")),
                            new CargarPedidoService.ItemPedido(idReventa, new BigDecimal("1.500"))),
                    f.actores.datos().direccionEntrega(),
                    UUID.randomUUID())).idPedido();
        });
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(pedidoMixto, idElab, new BigDecimal("2.000"), null));

        // Congelado pedidoMixto: 2*100 + 1.5*200 = 500 -> CUMPLIDOR -10% = 450.00
        // Alterar precio y politica DESPUES de cargar el Pedido: NO debe recotizar en UC-05
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE \"PRECIO_PRODUCTO\" SET \"precioBase\" = 999 WHERE \"idProducto\" = ?", idElab);
            jdbc.update("""
                    UPDATE "AJUSTE_POLITICA_CATEGORIA"
                       SET "porcentajeAjuste" = 50.00
                     WHERE "idPoliticaComercial" = ? AND "categoriaComercial" = 'CUMPLIDOR'
                    """, f.actores.politica);
        });
        try {
            var cobroReserva = cobros.registrarCobro(f.actores.vendedor(), idCliente, pedidoMixto,
                    new BigDecimal("120.00"), "EFECTIVO", UUID.randomUUID());
            imputacion.registrarReservaAnticipo(f.actores.vendedor(), cobroReserva.idCobro(), pedidoMixto,
                    new BigDecimal("120.00"), "Anticipo explicitado");
            cobros.registrarCobro(f.actores.vendedor(), idCliente, null,
                    new BigDecimal("80.00"), "TRANSFERENCIA", UUID.randomUUID());

            var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-UC05-MIX");
            recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                    new AsignarRepartoService.DatosAsignacion(pedidoMixto, idRep, null), rec.idRecorrido());

            int cobrosAntes = jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class);
            int pagosAntes = jdbc.queryForObject(
                    "SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"tipoMovimiento\"='PAGO'", Integer.class);
            int cajaAntes = jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class);

            UUID clave = UUID.randomUUID();
            EntregaRegistrada res = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                    pedidoMixto,
                    List.of(
                            new ItemEntrega(idElab, new BigDecimal("2.000")),
                            new ItemEntrega(idReventa, new BigDecimal("1.500"))),
                    new BigDecimal("300.00"),
                    clave));

            assertTrue(res.creado());
            assertEquals(pedidoMixto, res.idPedido());
            assertEquals(rec.idRecorrido(), res.idRecorrido());
            assertEquals("ENTREGADO", res.estadoPedido());
            assertDec("450.00", res.cargoRealCe());
            assertDec("120.00", res.anticipoExplicitoPe());
            assertDec("80.00", res.fifoVinculanteFe());
            assertDec("200.00", res.coberturaTotalKe());
            assertDec("250.00", res.exigibleEe());
            assertDec("300.00", res.declaradoRepartidorDe());
            assertDec("250.00", res.reconocimientoInicialV0());
            assertEquals("IMP_B", res.modoEvidencia());
            assertTrue(res.excedentesGenerados().isEmpty());

            assertEquals(cobrosAntes, jdbc.queryForObject("SELECT count(*) FROM \"COBRO\"", Integer.class));
            assertEquals(pagosAntes, jdbc.queryForObject(
                    "SELECT count(*) FROM \"MOVIMIENTO_CUENTA\" WHERE \"tipoMovimiento\"='PAGO'", Integer.class));
            assertEquals(cajaAntes, jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_CAJA\"", Integer.class));

            // Verificar efectos fisicos e historicos
            assertDec("3.500", jdbc.queryForObject(
                    "SELECT \"stockActualReventa\" FROM \"PRODUCTO\" WHERE \"idProducto\"=?", BigDecimal.class, idReventa));
            var movReventa = jdbc.queryForMap(
                    "SELECT \"tipoMovimiento\",\"cantidad\",\"stockResultante\",\"costoTotalHistoricoVenta\" FROM \"MOVIMIENTO_STOCK_REVENTA\" WHERE \"idPedidoEntrega\"=?",
                    pedidoMixto);
            assertEquals("EGRESO_VENTA", movReventa.get("tipoMovimiento"));
            assertDec("1.500", (BigDecimal) movReventa.get("cantidad"));
            assertDec("90.0000", (BigDecimal) movReventa.get("costoTotalHistoricoVenta"));

            assertDec("140.0000", jdbc.queryForObject(
                    "SELECT \"costoHistoricoElaboradoVendido\" FROM \"DETALLE_ENTREGA\" WHERE \"idPedido\"=? AND \"numeroDetalle\"=1",
                    BigDecimal.class, pedidoMixto));
            assertNull(jdbc.queryForObject(
                    "SELECT \"costoHistoricoElaboradoVendido\" FROM \"DETALLE_ENTREGA\" WHERE \"idPedido\"=? AND \"numeroDetalle\"=2",
                    BigDecimal.class, pedidoMixto));

            assertEquals("ENTREGADA", jdbc.queryForObject(
                    "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=?",
                    String.class, pedidoMixto));
            assertEquals("ENTREGADO", jdbc.queryForObject(
                    "SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?", String.class, pedidoMixto));
        } finally {
            tx.executeWithoutResult(s -> jdbc.update("""
                    UPDATE "AJUSTE_POLITICA_CATEGORIA"
                       SET "porcentajeAjuste" = 10.00
                     WHERE "idPoliticaComercial" = ? AND "categoriaComercial" = 'CUMPLIDOR'
                    """, f.actores.politica));
        }
    }

    @Test
    void entregaParcialGeneraExcedenteTrazableYLiberaReservaSobrante() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("5"));

        var cobro = cobros.registrarCobro(f.actores.vendedor(), idCliente, null,
                new BigDecimal("400.00"), "EFECTIVO", UUID.randomUUID());
        imputacion.registrarReservaAnticipo(f.actores.vendedor(), cobro.idCobro(), f.pedido,
                new BigDecimal("400.00"), "Reserva excedente de cargo parcial");

        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-PARCIAL");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, idRep, null), rec.idRecorrido());

        // Original 10, preparado 5 (costo total 5*(0.2+0.5)*100 = 350.0000), entregado 3 -> C_e = 3*100*0.9 = 270.00
        EntregaRegistrada res = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                f.pedido,
                List.of(new ItemEntrega(f.producto, new BigDecimal("3.000"))),
                new BigDecimal("50.00"),
                UUID.randomUUID()));

        assertDec("270.00", res.cargoRealCe());
        assertDec("270.00", res.anticipoExplicitoPe());
        assertDec("0.00", res.exigibleEe());
        assertDec("50.00", res.declaradoRepartidorDe());
        assertDec("0.00", res.reconocimientoInicialV0());
        assertEquals("IMP_B", res.modoEvidencia());
        assertEquals(1, res.excedentesGenerados().size());
        assertDec("2.000", res.excedentesGenerados().getFirst().cantidadInicial());

        // Verificar conservacion de costos: vendido 210.0000 + excedente 140.0000 = 350.0000
        BigDecimal costoVendido = jdbc.queryForObject(
                "SELECT \"costoHistoricoElaboradoVendido\" FROM \"DETALLE_ENTREGA\" WHERE \"idPedido\"=? AND \"numeroDetalle\"=1",
                BigDecimal.class, f.pedido);
        BigDecimal costoExcedente = jdbc.queryForObject(
                "SELECT \"costoHistoricoInicial\" FROM \"EXCEDENTE\" WHERE \"idExcedente\"=?",
                BigDecimal.class, res.excedentesGenerados().getFirst().idExcedente());
        assertDec("210.0000", costoVendido);
        assertDec("140.0000", costoExcedente);

        // Verificar liberacion de 400 - 270 = 130.00 hacia importeLibreDisponible
        BigDecimal libre = jdbc.queryForObject(
                "SELECT \"importeLibreDisponible\" FROM \"VW_IMP_A_DISPONIBILIDAD_COBRO\" WHERE \"idCobro\"=?",
                BigDecimal.class, cobro.idCobro());
        assertDec("130.00", libre);
    }

    @Test
    void uc15IgnoraDeclaracionDeYNoConsideraTransitoComoCobroConfirmado() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        // PedidoFixture crea base+1 con AJUSTE_DEBITO de 1000.00 vencido hace 10 dias (> 5 dias)
        long clienteMora = f.actores.base + 1;
        long idRep = f.actores.base + 3;

        long pedMora = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                clienteMora, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(pedMora, f.producto, new BigDecimal("2.000"), null));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-UC15");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(pedMora, idRep, null), rec.idRecorrido());

        // Efectivo declarado 240.00 (= 100% de C_e) NO cancela deuda ni recategoriza a CUMPLIDOR
        EntregaRegistrada res = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                pedMora,
                List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                new BigDecimal("240.00"),
                UUID.randomUUID()));

        assertDec("240.00", res.cargoRealCe());
        assertDec("240.00", res.declaradoRepartidorDe());
        assertDec("240.00", res.reconocimientoInicialV0());
        assertEquals("EN_MORA", jdbc.queryForObject(
                "SELECT \"categoriaComercial\" FROM \"CLIENTE\" WHERE \"idCliente\"=?", String.class, clienteMora));
    }

    @Test
    void contencionImpCAnteCobroIdPedidoSinReservaYReembolsoPendiente() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;

        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("2"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-IMPC");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, idRep, null), rec.idRecorrido());

        // Cobro legacy con COBRO.idPedido sin evento IMP-B (RCG-06 senal A)
        var cobroLegacy = cobros.registrarCobro(f.actores.vendedor(), idCliente, f.pedido,
                new BigDecimal("180.00"), "EFECTIVO", UUID.randomUUID());

        EntregaRegistrada res = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                f.pedido,
                List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                new BigDecimal("180.00"),
                UUID.randomUUID()));

        assertEquals("IMP_C", res.modoEvidencia());
        assertDec("0.00", res.anticipoExplicitoPe());
        assertDec("0.00", res.fifoVinculanteFe());
        assertDec("0.00", res.reconocimientoInicialV0());
        assertDec("180.00", res.declaradoRepartidorDe());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idCobro\"=?",
                Integer.class, cobroLegacy.idCobro()));

        // Caso B: REEMBOLSO PENDIENTE sin conciliar en otro Cliente => IMP_C sin inventar V_e^0
        long clienteB = f.actores.base + 1;
        long pedB = pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                clienteB, f.actores.hoy.plusDays(1),
                List.of(new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000"))),
                f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(pedB, f.producto, new BigDecimal("2.000"), null));
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(pedB, idRep, null), rec.idRecorrido());
        var cobroB = cobros.registrarCobro(f.actores.vendedor(), clienteB, null,
                new BigDecimal("150.00"), "EFECTIVO", UUID.randomUUID());
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO "REEMBOLSO" ("idReembolso","idCobro","importeReembolso",
                    "fechaHoraCreacion","motivo","estado")
                VALUES (?,?,?,clock_timestamp(),'Reembolso pendiente UC-05','PENDIENTE')
                """, f.actores.base + 890, cobroB.idCobro(), new BigDecimal("150.00")));

        EntregaRegistrada resB = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                pedB, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                new BigDecimal("90.00"), UUID.randomUUID()));
        assertEquals("IMP_C", resB.modoEvidencia());
        assertDec("0.00", resB.reconocimientoInicialV0());
        assertDec("90.00", resB.declaradoRepartidorDe());
    }

    @Test
    void rechazaFaltaStockCostoCeroYCantidadesFueraDeOriginalSinEscribir() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idCliente = f.actores.base;
        long idRep = f.actores.base + 3;
        long idReventa = f.actores.base + 1;

        long ped = tx.execute(s -> {
            jdbc.update("""
                    UPDATE "PRODUCTO"
                       SET "tipoProducto" = 'REVENTA',
                           "stockActualReventa" = 2.000,
                           "costoPromedioActualReventa" = NULL
                     WHERE "idProducto" = ?
                    """, idReventa);
            return pedidos.cargarPedido(f.actores.vendedor(), new CargarPedidoService.DatosPedido(
                    idCliente, f.actores.hoy.plusDays(1),
                    List.of(
                            new CargarPedidoService.ItemPedido(f.producto, new BigDecimal("2.000")),
                            new CargarPedidoService.ItemPedido(idReventa, new BigDecimal("2.000"))),
                    f.actores.datos().direccionEntrega(), UUID.randomUUID())).idPedido();
        });
        preparaciones.registrarPreparacionPedido(f.produccion(),
                new RegistrarPreparacionService.DatosPreparacion(ped, f.producto, new BigDecimal("1.000"), null));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-VAL");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(ped, idRep, null), rec.idRecorrido());
        UUID rep = f.actores.sesiones.get("REPARTIDOR");

        assertEquals("CANTIDADES_EN_CERO", assertThrows(IllegalArgumentException.class, () ->
                entregas.registrarEntrega(rep, new DatosEntrega(
                        ped, List.of(new ItemEntrega(f.producto, BigDecimal.ZERO)), null, UUID.randomUUID()))).getMessage());

        assertEquals("CANTIDAD_EXCEDE_ORIGINAL", assertThrows(IllegalArgumentException.class, () ->
                entregas.registrarEntrega(rep, new DatosEntrega(
                        ped, List.of(new ItemEntrega(f.producto, new BigDecimal("3.000"))), null, UUID.randomUUID()))).getMessage());

        assertEquals("DISPONIBILIDAD_ELABORADO_INSUFICIENTE", assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(rep, new DatosEntrega(
                        ped, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))), null, UUID.randomUUID()))).getMessage());

        assertEquals("COSTO_HISTORICO_REQUERIDO", assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(rep, new DatosEntrega(
                        ped, List.of(new ItemEntrega(idReventa, new BigDecimal("1.000"))), null, UUID.randomUUID()))).getMessage());

        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE \"PRODUCTO\" SET \"stockActualReventa\"=1.000, \"costoPromedioActualReventa\"=50.0000 WHERE \"idProducto\"=?",
                idReventa));
        assertEquals("STOCK_REVENTA_INSUFICIENTE", assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(rep, new DatosEntrega(
                        ped, List.of(new ItemEntrega(idReventa, new BigDecimal("2.000"))), null, UUID.randomUUID()))).getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, ped));
    }

    @Test
    void guardiaUc07FailClosedRechazaAdicionalAceptadoPermitiendoPendienteORechazada() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long idRep = f.actores.base + 3;
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("2"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), idRep, "REC-UC07");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, idRep, null), rec.idRecorrido());

        long otroPedido = pedidos.cargarPedido(f.actores.vendedor(), f.actores.datos()).idPedido();
        long ofertaId = f.actores.base + 801;
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO "EXCEDENTE" (
                        "idExcedente","idPedidoOrigen","numeroDetalleOrigen","origenGeneracion",
                        "fechaHoraGeneracion","cantidadInicial","cantidadDisponible",
                        "costoHistoricoInicial","costoHistoricoDisponible")
                    VALUES (?,?,1,'CANCELACION',now(),5.000,5.000,100.0000,100.0000)
                    """, f.actores.base + 800, otroPedido);
            jdbc.update("""
                    INSERT INTO "OFERTA_EXCEDENTE" (
                        "idOfertaExcedente","idExcedente","idPedidoReceptor","idUsuarioVendedor",
                        "cantidadOfrecida","importeInformado","fechaHoraOferta","respuesta","fechaHoraRespuesta","cantidadAceptada","importeAceptado")
                    VALUES (?,?,?,?,2.000,180.00,now(),'ACEPTADA',now(),2.000,180.00)
                    """, ofertaId, f.actores.base + 800, f.pedido, f.actores.base);
        });

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                        f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                        null, UUID.randomUUID())));
        assertEquals("ADICIONALES_UC07_NO_SOPORTADOS", ex.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));
        assertEquals("PENDIENTE", jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?", String.class, f.pedido));

        // Oferta RECHAZADA no bloquea entrega sobre originales
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE \"OFERTA_EXCEDENTE\" SET \"respuesta\"='RECHAZADA', \"cantidadAceptada\"=NULL, \"importeAceptado\"=NULL WHERE \"idOfertaExcedente\"=?",
                ofertaId));
        EntregaRegistrada ok = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                null, UUID.randomUUID()));
        assertEquals("ENTREGADO", ok.estadoPedido());
    }

    @Test
    void recorridoFa1Fa2YReasignacionUc43ConservanInvariantes() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long rep1 = f.actores.base + 3;
        long rep2 = tx.execute(s -> {
            long r2 = f.actores.base + 77;
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                    """, r2, "rep2_" + r2);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", r2);
            return r2;
        });

        var rec1 = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "R1");
        assertThrows(IllegalStateException.class, () ->
                recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "R1-DUP"));
        var rec2 = recorridos.abrirRecorrido(f.actores.vendedor(), rep2, "R2");

        // UC-43 sin recorrido falla en UC-05 (nunca deriva por Repartidor+fecha)
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("1"));
        asignaciones.asignarOReasignar(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null));
        IllegalStateException sinRec = assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                        f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("1.000"))), null, UUID.randomUUID())));
        assertEquals("RECORRIDO_ACTIVO_REQUERIDO", sinRec.getMessage());

        // Vincular a R1 y comprobar bloqueo FA-2 al intentar cerrar R1 con pedido PENDIENTE
        recorridos.vincularPedidoARecorrido(f.actores.vendedor(), rec1.idRecorrido(), f.pedido);
        assertEquals("FA2_PEDIDOS_PENDIENTES_ACTIVOS", assertThrows(IllegalStateException.class, () ->
                recorridos.cerrarRecorridoSinPendientes(f.actores.vendedor(), rec1.idRecorrido())).getMessage());

        // Reasignar a R2 desvincula R1; sin contrato ratificado de cierre sin PE-2, R1 permanece ABIERTO y FA-1 sigue vigente
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep2, "Cambio de ruta"), rec2.idRecorrido());
        assertEquals("DESVINCULADA_POR_REASIGNACION", jdbc.queryForObject(
                "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=? AND \"numeroAsignacion\"=1",
                String.class, f.pedido));
        assertEquals("CIERRE_SIN_PE2_NO_AUTORIZADO", assertThrows(IllegalStateException.class, () ->
                recorridos.cerrarRecorridoSinPendientes(f.actores.vendedor(), rec1.idRecorrido())).getMessage());
        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, rec1.idRecorrido()));
        assertEquals("RECORRIDO_ABIERTO_EXISTENTE", assertThrows(IllegalStateException.class, () ->
                recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "R1-NUEVO")).getMessage());
    }

    @Test
    void idempotenciaReplayAutorizadoConflictoYRevalidacionActor() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long rep1 = f.actores.base + 3;
        UUID rep2Sesion = tx.execute(s -> {
            long r2 = f.actores.base + 78;
            UUID ses = UUID.randomUUID();
            String tok = TokenSesionInterna.nuevo();
            jdbc.update("""
                    INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                        "estado","cambioCredencialPendiente","fechaHoraAlta")
                    VALUES (?,?,'HASH_TEST','HABILITADO',false,now())
                    """, r2, "rep_otro_" + r2);
            jdbc.update("INSERT INTO \"USUARIO_ROL\" VALUES (?,'REPARTIDOR',now())", r2);
            jdbc.update("""
                    INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido","fechaHoraInicio","fechaHoraExpiracion")
                    VALUES (?,?,?,now()-interval '1 minute',now()+interval '1 hour')
                    """, ses, r2, TokenSesionInterna.huella(tok).orElseThrow());
            return ses;
        });

        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("3"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "REC-IDEMP");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null), rec.idRecorrido());

        UUID clave = UUID.randomUUID();
        DatosEntrega cmd = new DatosEntrega(
                f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("3.000"))),
                new BigDecimal("100.00"), clave);

        // Rol ajeno o Repartidor no asignado rechazados antes de escribir
        assertThrows(SecurityException.class, () -> entregas.registrarEntrega(f.actores.vendedor(), cmd));
        assertThrows(SecurityException.class, () -> entregas.registrarEntrega(rep2Sesion, cmd));

        EntregaRegistrada primera = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), cmd);
        assertTrue(primera.creado());

        // Cierre sin PE-2 tras Entrega confirmada debe fallar cerrado sin alterar RECORRIDO_REPARTO ni PE-1
        assertEquals("RENDICION_PE2_PENDIENTE", assertThrows(IllegalStateException.class, () ->
                recorridos.cerrarRecorridoSinPendientes(f.actores.vendedor(), rec.idRecorrido())).getMessage());
        assertEquals("ABIERTO", jdbc.queryForObject(
                "SELECT \"estadoOperativo\" FROM \"RECORRIDO_REPARTO\" WHERE \"idRecorrido\"=?",
                String.class, rec.idRecorrido()));

        EntregaRegistrada replay = entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), cmd);
        assertFalse(replay.creado());
        assertDec(primera.cargoRealCe().toPlainString(), replay.cargoRealCe());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));

        // Misma clave con distinto contenido => conflicto sin escrituras
        assertEquals("IDEMPOTENCIA_CONFLICTO_DATOS", assertThrows(IllegalStateException.class, () ->
                entregas.registrarEntrega(f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                        f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                        new BigDecimal("100.00"), clave))).getMessage());

        // Misma clave replayed por otro Repartidor => SecurityException
        assertThrows(SecurityException.class, () -> entregas.registrarEntrega(rep2Sesion, cmd));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackTotalSiFallaPasoIntermedioNoDejaResiduosFinancierosNiInventario() {
        var f = new PreparacionFixture(jdbc, tx, pedidos);
        long rep1 = f.actores.base + 3;
        preparaciones.registrarPreparacionPedido(f.produccion(), f.datos("4"));
        var rec = recorridos.abrirRecorrido(f.actores.vendedor(), rep1, "REC-RB");
        recorridos.asignarYVincularRecorrido(f.actores.vendedor(),
                new AsignarRepartoService.DatosAsignacion(f.pedido, rep1, null), rec.idRecorrido());

        cobros.registrarCobro(f.actores.vendedor(), f.actores.base, null,
                new BigDecimal("100.00"), "EFECTIVO", UUID.randomUUID());

        jdbc.execute("""
                CREATE FUNCTION fallo_recorrido_entrega_uc05_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'fallo simulado post-pe1'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fallo_recorrido_entrega_uc05_test
                BEFORE INSERT ON "RECORRIDO_ENTREGA"
                FOR EACH ROW EXECUTE FUNCTION fallo_recorrido_entrega_uc05_test()
                """);
        try {
            UUID clave = UUID.randomUUID();
            assertThrows(DataAccessException.class, () -> entregas.registrarEntrega(
                    f.actores.sesiones.get("REPARTIDOR"), new DatosEntrega(
                            f.pedido, List.of(new ItemEntrega(f.producto, new BigDecimal("2.000"))),
                            new BigDecimal("50.00"), clave)));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"ENTREGA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"RECONOCIMIENTO_ENTREGA_PE1\" WHERE \"idPedido\"=?", Integer.class, f.pedido));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"EXCEDENTE\" WHERE \"idPedidoOrigen\"=?", Integer.class, f.pedido));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"ORIGEN_MOVIMIENTO_CUENTA\" WHERE \"idPedidoEntrega\"=?", Integer.class, f.pedido));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM \"EVENTO_IMPUTACION_FINANCIERA\" WHERE \"idPedido\"=?", Integer.class, f.pedido));
            assertEquals("PENDIENTE", jdbc.queryForObject("SELECT \"estado\" FROM \"PEDIDO\" WHERE \"idPedido\"=?", String.class, f.pedido));
            assertEquals("ACTIVA", jdbc.queryForObject(
                    "SELECT \"estadoPertenencia\" FROM \"RECORRIDO_ASIGNACION\" WHERE \"idPedido\"=?", String.class, f.pedido));
        } finally {
            jdbc.execute("DROP TRIGGER fallo_recorrido_entrega_uc05_test ON \"RECORRIDO_ENTREGA\"");
            jdbc.execute("DROP FUNCTION fallo_recorrido_entrega_uc05_test()");
        }
    }
}
