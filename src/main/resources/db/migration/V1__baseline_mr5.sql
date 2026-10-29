-- B2: Migracion Flyway derivada mecanicamente de fuente canónica G0.
-- source_repo: https://github.com/leandrito369/ale-milanesas
-- source_commit: 1263ffc17bd0eb7f3c69faaa9ff5ae556cf9de7e
-- source_path: docs/05-modelo-relacional/sql/MR5_DDL_NO_EJECUTADO.sql
-- transformation: remove standalone BEGIN/COMMIT delimiters and psql-specific \\set, if present.
-- No se han modificado reglas, estados, tablas, columnas ni funciones del SQL de origen.
-- El transaction boundary lo gobierna Flyway/PostgreSQL. Pruebas requeridas antes de merge.

-- ALE MILANESAS - MODELO RELACIONAL MR-3 / 25-10-2026 / PostgreSQL
-- BASELINE HISTORICO 59/444/120; MR-3 incorpora CONFIGURACION_OPERATIVA y columnas referenciales.
-- DOCUMENTO DE DISEÑO NO EJECUTADO; requiere pruebas y migración de datos en MR-4.
-- DATOS DE CONFIGURACION DEL PLAZO NO SE PRECARGAN; NO HAY DEFAULT 48 h.
-- Esqueleto tipado e íntegro en PK/FK para comenzar el diagrama.
-- NO certifica integridad interrelacional, triggers, temporalidad ni políticas pendientes.
-- AJUSTES IMPRESCINDIBLES ANTES DE PRODUCCION: ver Esquema_Maestro_*.md y Pendientes_Integracion.md
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE "UNIDAD_MEDIDA" (
    "codigoUnidad" VARCHAR(20) NOT NULL,
    "nombre" VARCHAR(100) NOT NULL,
    CONSTRAINT "pk_unidad_medida" PRIMARY KEY ("codigoUnidad"),
    CONSTRAINT "uq_unidad_medida_0" UNIQUE ("nombre")
);

CREATE TABLE "PRODUCTO" (
    "idProducto" BIGINT NOT NULL,
    "nombre" VARCHAR(150) NOT NULL,
    "nombreNormalizado" VARCHAR(150) NOT NULL,
    "descripcion" TEXT,
    "tipoProducto" VARCHAR(20) NOT NULL,
    "codigoUnidadVenta" VARCHAR(20) NOT NULL,
    "activo" BOOLEAN NOT NULL,
    "stockActualReventa" NUMERIC(18,3),
    "costoPromedioActualReventa" NUMERIC(18,4),
    CONSTRAINT "pk_producto" PRIMARY KEY ("idProducto"),
    CONSTRAINT "uq_producto_0" UNIQUE ("nombreNormalizado"),
    CONSTRAINT "ck_producto_0" CHECK ("tipoProducto" IN ('ELABORADO','REVENTA')),
    CONSTRAINT "ck_producto_1" CHECK ("stockActualReventa" IS NULL OR "stockActualReventa">=0),
    CONSTRAINT "ck_producto_2" CHECK ("costoPromedioActualReventa" IS NULL OR "costoPromedioActualReventa">=0)
);

CREATE TABLE "INSUMO" (
    "idInsumo" BIGINT NOT NULL,
    "nombre" VARCHAR(150) NOT NULL,
    "codigoUnidadMedida" VARCHAR(20) NOT NULL,
    "stockActual" NUMERIC(18,3) NOT NULL,
    "umbralMinimo" NUMERIC(18,3) NOT NULL,
    "nivelObjetivoReposicion" NUMERIC(18,3) NOT NULL,
    "automatizacionCompraActiva" BOOLEAN NOT NULL,
    "activo" BOOLEAN NOT NULL,
    CONSTRAINT "pk_insumo" PRIMARY KEY ("idInsumo"),
    CONSTRAINT "ck_insumo_0" CHECK ("stockActual">=0),
    CONSTRAINT "ck_insumo_1" CHECK ("umbralMinimo">=0),
    CONSTRAINT "ck_insumo_2" CHECK ("nivelObjetivoReposicion">"umbralMinimo")
);

CREATE TABLE "RECETA" (
    "idReceta" BIGINT NOT NULL,
    "idProducto" BIGINT NOT NULL,
    CONSTRAINT "pk_receta" PRIMARY KEY ("idReceta"),
    CONSTRAINT "uq_receta_0" UNIQUE ("idProducto")
);

CREATE TABLE "VERSION_RECETA" (
    "idReceta" BIGINT NOT NULL,
    "numeroVersion" INTEGER NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    "cantidadRendimientoBase" NUMERIC(18,3) NOT NULL,
    "codigoUnidadRendimiento" VARCHAR(20) NOT NULL,
    CONSTRAINT "pk_version_receta" PRIMARY KEY ("idReceta", "numeroVersion"),
    CONSTRAINT "ck_version_receta_0" CHECK ("numeroVersion">0),
    CONSTRAINT "ck_version_receta_1" CHECK ("cantidadRendimientoBase">0),
    CONSTRAINT "ck_version_receta_2" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde")
);

CREATE TABLE "DETALLE_VERSION_RECETA" (
    "idReceta" BIGINT NOT NULL,
    "numeroVersion" INTEGER NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "cantidadNecesaria" NUMERIC(18,3) NOT NULL,
    "codigoUnidadCantidad" VARCHAR(20) NOT NULL,
    CONSTRAINT "pk_detalle_version_receta" PRIMARY KEY ("idReceta", "numeroVersion", "idInsumo"),
    CONSTRAINT "ck_detalle_version_receta_0" CHECK ("cantidadNecesaria">0)
);

CREATE TABLE "PREPARACION" (
    "idPreparacion" BIGINT NOT NULL,
    "idPedido" BIGINT NOT NULL,
    "numeroDetalle" INTEGER NOT NULL,
    "idReceta" BIGINT NOT NULL,
    "numeroVersion" INTEGER NOT NULL,
    "fechaHoraPreparacion" TIMESTAMPTZ NOT NULL,
    "cantidadPreparada" NUMERIC(18,3) NOT NULL,
    CONSTRAINT "pk_preparacion" PRIMARY KEY ("idPreparacion"),
    CONSTRAINT "ck_preparacion_0" CHECK ("cantidadPreparada">0)
);

CREATE TABLE "COSTO_INSUMO_HISTORICO" (
    "idInsumo" BIGINT NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    "costoUnitario" NUMERIC(18,4) NOT NULL,
    "fuenteCosto" VARCHAR(30) NOT NULL,
    "referenciaOrigen" VARCHAR(100),
    "idRecepcionCompra" BIGINT,
    CONSTRAINT "pk_costo_insumo_historico" PRIMARY KEY ("idInsumo", "vigenteDesde"),
    CONSTRAINT "uq_costo_insumo_historico_0" UNIQUE ("idRecepcionCompra"),
    CONSTRAINT "ck_costo_insumo_historico_0" CHECK ("costoUnitario">0),
    CONSTRAINT "ck_costo_insumo_historico_1" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde")
);

CREATE TABLE "MOVIMIENTO_INSUMO" (
    "idMovimientoInsumo" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "numeroSecuenciaInsumo" BIGINT NOT NULL,
    "idPreparacion" BIGINT,
    "idRecepcionCompra" BIGINT,
    "vigenteDesdeCosto" TIMESTAMPTZ NOT NULL,
    "idUsuarioResponsable" BIGINT NOT NULL,
    "tipoMovimiento" VARCHAR(30) NOT NULL,
    "cantidad" NUMERIC(18,3) NOT NULL,
    "fechaHora" TIMESTAMPTZ NOT NULL,
    "stockResultante" NUMERIC(18,3) NOT NULL,
    "origen" VARCHAR(30) NOT NULL,
    "referenciaOrigen" VARCHAR(100),
    "motivo" TEXT,
    "observacion" TEXT,
    CONSTRAINT "pk_movimiento_insumo" PRIMARY KEY ("idMovimientoInsumo"),
    CONSTRAINT "uq_movimiento_insumo_0" UNIQUE ("idInsumo", "numeroSecuenciaInsumo"),
    CONSTRAINT "uq_movimiento_insumo_1" UNIQUE ("idRecepcionCompra"),
    CONSTRAINT "ck_movimiento_insumo_0" CHECK ("numeroSecuenciaInsumo">0),
    CONSTRAINT "ck_movimiento_insumo_1" CHECK ("cantidad">0),
    CONSTRAINT "ck_movimiento_insumo_2" CHECK ("stockResultante">=0),
    CONSTRAINT "ck_movimiento_insumo_3" CHECK ("tipoMovimiento" IN ('INGRESO_COMPRA','CONSUMO_PRODUCCION','AJUSTE_POSITIVO','AJUSTE_NEGATIVO'))
);

CREATE TABLE "PEDIDO" (
    "idPedido" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idUsuarioCreador" BIGINT,
    "idSesionOnline" UUID,
    "fechaHoraCarga" TIMESTAMPTZ NOT NULL,
    "fechaReparto" DATE NOT NULL,
    "canalOrigen" VARCHAR(10) NOT NULL,
    "estado" VARCHAR(15) NOT NULL,
    "categoriaComercialAplicada" VARCHAR(20) NOT NULL,
    "idPoliticaComercialAplicada" BIGINT NOT NULL,
    "tipoAjuste" VARCHAR(15) NOT NULL,
    "porcentajeAjuste" NUMERIC(5,2) NOT NULL,
    "totalOriginal" NUMERIC(18,2) NOT NULL,
    "motivoCancelacion" TEXT,
    "fechaHoraCancelacion" TIMESTAMPTZ,
    "idOperacionOrigen" UUID,
    "direccionEntregaCalle" VARCHAR(150) NOT NULL,
    "direccionEntregaNumero" VARCHAR(20),
    "direccionEntregaPiso" VARCHAR(10),
    "direccionEntregaUnidadFuncional" VARCHAR(20),
    "direccionEntregaLocalidad" VARCHAR(100) NOT NULL,
    "direccionEntregaProvincia" VARCHAR(100),
    "direccionEntregaReferencia" VARCHAR(250),
    "direccionEntregaZona" VARCHAR(100),
    CONSTRAINT "pk_pedido" PRIMARY KEY ("idPedido"),
    CONSTRAINT "uq_pedido_0" UNIQUE ("idOperacionOrigen"),
    CONSTRAINT "uq_pedido_1" UNIQUE ("idPedido", "idCliente"),
    CONSTRAINT "ck_pedido_0" CHECK ("canalOrigen" IN ('INTERNO','ONLINE')),
    CONSTRAINT "ck_pedido_1" CHECK ("estado" IN ('PENDIENTE','ENTREGADO','CANCELADO')),
    CONSTRAINT "ck_pedido_2" CHECK ("tipoAjuste" IN ('DESCUENTO','RECARGO','SIN_AJUSTE')),
    CONSTRAINT "ck_pedido_3" CHECK ("porcentajeAjuste" BETWEEN 0 AND 100),
    CONSTRAINT "ck_pedido_4" CHECK ("totalOriginal">=0),
    CONSTRAINT "ck_pedido_5" CHECK (("canalOrigen"='ONLINE' AND "idSesionOnline" IS NOT NULL AND "idUsuarioCreador" IS NULL) OR ("canalOrigen"='INTERNO' AND "idSesionOnline" IS NULL AND "idUsuarioCreador" IS NOT NULL))
);

CREATE TABLE "DETALLE_PEDIDO" (
    "idPedido" BIGINT NOT NULL,
    "numeroDetalle" INTEGER NOT NULL,
    "idProducto" BIGINT NOT NULL,
    "codigoUnidadVenta" VARCHAR(20) NOT NULL,
    "cantidadOriginal" NUMERIC(18,3) NOT NULL,
    "precioUnitarioOriginal" NUMERIC(18,4),
    "porcentajePromocionOriginal" NUMERIC(5,2) NOT NULL,
    "idPromocionAplicada" BIGINT,
    CONSTRAINT "pk_detalle_pedido" PRIMARY KEY ("idPedido", "numeroDetalle"),
    CONSTRAINT "uq_detalle_pedido_0" UNIQUE ("idPedido", "idProducto"),
    CONSTRAINT "uq_detalle_pedido_1" UNIQUE ("idPedido", "numeroDetalle", "idProducto"),
    CONSTRAINT "ck_detalle_pedido_0" CHECK ("numeroDetalle">0),
    CONSTRAINT "ck_detalle_pedido_1" CHECK ("cantidadOriginal">=0),
    CONSTRAINT "ck_detalle_pedido_2" CHECK ("porcentajePromocionOriginal" BETWEEN 0 AND 100),
    CONSTRAINT "ck_detalle_pedido_3" CHECK (("cantidadOriginal"=0 AND "precioUnitarioOriginal" IS NULL AND "porcentajePromocionOriginal"=0 AND "idPromocionAplicada" IS NULL) OR ("cantidadOriginal">0 AND "precioUnitarioOriginal" IS NOT NULL AND "precioUnitarioOriginal">0))
);

CREATE TABLE "ASIGNACION_REPARTO" (
    "idPedido" BIGINT NOT NULL,
    "numeroAsignacion" INTEGER NOT NULL,
    "idUsuarioRepartidor" BIGINT NOT NULL,
    "idUsuarioVendedor" BIGINT NOT NULL,
    "fechaHoraInicio" TIMESTAMPTZ NOT NULL,
    "fechaHoraFin" TIMESTAMPTZ,
    "motivoReasignacion" TEXT,
    CONSTRAINT "pk_asignacion_reparto" PRIMARY KEY ("idPedido", "numeroAsignacion"),
    CONSTRAINT "ck_asignacion_reparto_0" CHECK ("numeroAsignacion">0),
    CONSTRAINT "ck_asignacion_reparto_1" CHECK ("fechaHoraFin" IS NULL OR "fechaHoraFin">="fechaHoraInicio")
);

CREATE TABLE "ENTREGA" (
    "idPedido" BIGINT NOT NULL,
    "fechaHoraEntrega" TIMESTAMPTZ NOT NULL,
    "idUsuarioRepartidor" BIGINT NOT NULL,
    CONSTRAINT "pk_entrega" PRIMARY KEY ("idPedido")
);

CREATE TABLE "DETALLE_ENTREGA" (
    "idPedido" BIGINT NOT NULL,
    "numeroDetalle" INTEGER NOT NULL,
    "cantidadEntregada" NUMERIC(18,3) NOT NULL,
    "costoHistoricoElaboradoVendido" NUMERIC(18,4),
    CONSTRAINT "pk_detalle_entrega" PRIMARY KEY ("idPedido", "numeroDetalle"),
    CONSTRAINT "ck_detalle_entrega_0" CHECK ("cantidadEntregada">=0),
    CONSTRAINT "ck_detalle_entrega_1" CHECK ("costoHistoricoElaboradoVendido" IS NULL OR "costoHistoricoElaboradoVendido">=0)
);

CREATE TABLE "EXCEDENTE" (
    "idExcedente" BIGINT NOT NULL,
    "idPedidoOrigen" BIGINT NOT NULL,
    "numeroDetalleOrigen" INTEGER NOT NULL,
    "origenGeneracion" VARCHAR(15) NOT NULL,
    "fechaHoraGeneracion" TIMESTAMPTZ NOT NULL,
    "cantidadInicial" NUMERIC(18,3) NOT NULL,
    "cantidadDisponible" NUMERIC(18,3) NOT NULL,
    "costoHistoricoInicial" NUMERIC(18,4) NOT NULL,
    "costoHistoricoDisponible" NUMERIC(18,4) NOT NULL,
    "idMovimientoExcedenteOrigen" BIGINT,
    CONSTRAINT "pk_excedente" PRIMARY KEY ("idExcedente"),
    CONSTRAINT "ck_excedente_0" CHECK ("origenGeneracion" IN ('ENTREGA','CANCELACION')),
    CONSTRAINT "ck_excedente_1" CHECK ("cantidadInicial">0),
    CONSTRAINT "ck_excedente_2" CHECK ("cantidadDisponible">=0),
    CONSTRAINT "ck_excedente_3" CHECK ("cantidadDisponible"<="cantidadInicial"),
    CONSTRAINT "ck_excedente_4" CHECK ("costoHistoricoInicial">0),
    CONSTRAINT "ck_excedente_5" CHECK ("costoHistoricoDisponible">=0),
    CONSTRAINT "ck_excedente_6" CHECK ("costoHistoricoDisponible"<="costoHistoricoInicial")
);

CREATE TABLE "OFERTA_EXCEDENTE" (
    "idOfertaExcedente" BIGINT NOT NULL,
    "idExcedente" BIGINT NOT NULL,
    "idPedidoReceptor" BIGINT NOT NULL,
    "idUsuarioVendedor" BIGINT NOT NULL,
    "fechaHoraOferta" TIMESTAMPTZ NOT NULL,
    "cantidadOfrecida" NUMERIC(18,3) NOT NULL,
    "importeInformado" NUMERIC(18,2) NOT NULL,
    "respuesta" VARCHAR(15) NOT NULL,
    "fechaHoraRespuesta" TIMESTAMPTZ,
    "cantidadAceptada" NUMERIC(18,3),
    "importeAceptado" NUMERIC(18,2),
    CONSTRAINT "pk_oferta_excedente" PRIMARY KEY ("idOfertaExcedente"),
    CONSTRAINT "uq_oferta_excedente_0" UNIQUE ("idOfertaExcedente", "idExcedente"),
    CONSTRAINT "ck_oferta_excedente_0" CHECK ("cantidadOfrecida">0),
    CONSTRAINT "ck_oferta_excedente_1" CHECK ("importeInformado">0),
    CONSTRAINT "ck_oferta_excedente_2" CHECK ("respuesta" IN ('PENDIENTE','ACEPTADA','RECHAZADA')),
    CONSTRAINT "ck_oferta_excedente_3" CHECK (("respuesta"='PENDIENTE' AND "fechaHoraRespuesta" IS NULL AND "cantidadAceptada" IS NULL AND "importeAceptado" IS NULL) OR ("respuesta"='RECHAZADA' AND "fechaHoraRespuesta" IS NOT NULL AND "cantidadAceptada" IS NULL AND "importeAceptado" IS NULL) OR ("respuesta"='ACEPTADA' AND "fechaHoraRespuesta" IS NOT NULL AND "cantidadAceptada" IS NOT NULL AND "cantidadAceptada">0 AND "cantidadAceptada"<="cantidadOfrecida" AND "importeAceptado" IS NOT NULL AND "importeAceptado">0))
);

CREATE TABLE "MOVIMIENTO_EXCEDENTE" (
    "idMovimientoExcedente" BIGINT NOT NULL,
    "idExcedente" BIGINT NOT NULL,
    "idOfertaExcedente" BIGINT,
    "tipoMovimiento" VARCHAR(15) NOT NULL,
    "cantidad" NUMERIC(18,3) NOT NULL,
    "costoHistoricoSalida" NUMERIC(18,4) NOT NULL,
    "fechaHoraMovimiento" TIMESTAMPTZ NOT NULL,
    "motivoDescarte" TEXT,
    CONSTRAINT "pk_movimiento_excedente" PRIMARY KEY ("idMovimientoExcedente"),
    CONSTRAINT "uq_movimiento_excedente_0" UNIQUE ("idOfertaExcedente"),
    CONSTRAINT "ck_movimiento_excedente_0" CHECK ("tipoMovimiento" IN ('REASIGNACION','DESCARTE')),
    CONSTRAINT "ck_movimiento_excedente_1" CHECK ("cantidad">0),
    CONSTRAINT "ck_movimiento_excedente_2" CHECK ("costoHistoricoSalida">=0),
    CONSTRAINT "ck_movimiento_excedente_3" CHECK (("tipoMovimiento"='REASIGNACION' AND "idOfertaExcedente" IS NOT NULL AND "motivoDescarte" IS NULL) OR ("tipoMovimiento"='DESCARTE' AND "idOfertaExcedente" IS NULL AND "motivoDescarte" IS NOT NULL AND length(trim("motivoDescarte"))>0))
);

CREATE TABLE "IMPUTACION_ENTREGA_ADICIONAL" (
    "idPedido" BIGINT NOT NULL,
    "numeroDetalle" INTEGER NOT NULL,
    "idMovimientoExcedente" BIGINT NOT NULL,
    "cantidadImputada" NUMERIC(18,3) NOT NULL,
    "importeImputado" NUMERIC(18,2) NOT NULL,
    CONSTRAINT "pk_imputacion_entrega_adicional" PRIMARY KEY ("idPedido", "numeroDetalle", "idMovimientoExcedente"),
    CONSTRAINT "uq_imputacion_entrega_adicional_0" UNIQUE ("idMovimientoExcedente"),
    CONSTRAINT "ck_imputacion_entrega_adicional_0" CHECK ("cantidadImputada">0),
    CONSTRAINT "ck_imputacion_entrega_adicional_1" CHECK ("importeImputado">=0)
);

CREATE TABLE "PRECIO_PRODUCTO" (
    "idPrecio" BIGINT NOT NULL,
    "idProducto" BIGINT NOT NULL,
    "precioBase" NUMERIC(18,4) NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "idUsuarioRegistro" BIGINT NOT NULL,
    "fechaHoraCancelacion" TIMESTAMPTZ,
    "idUsuarioCancelacion" BIGINT,
    CONSTRAINT "pk_precio_producto" PRIMARY KEY ("idPrecio"),
    CONSTRAINT "ck_precio_producto_0" CHECK ("precioBase">0),
    CONSTRAINT "ck_precio_producto_1" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde"),
    CONSTRAINT "ck_precio_producto_2" CHECK (("fechaHoraCancelacion" IS NULL AND "idUsuarioCancelacion" IS NULL) OR ("fechaHoraCancelacion" IS NOT NULL AND "idUsuarioCancelacion" IS NOT NULL AND "fechaHoraCancelacion"<"vigenteDesde"))
);

CREATE TABLE "AUDITORIA_PRECIO" (
    "idAuditoriaPrecio" BIGINT NOT NULL,
    "idPrecio" BIGINT NOT NULL,
    "idUsuario" BIGINT NOT NULL,
    "fechaHoraEvento" TIMESTAMPTZ NOT NULL,
    "tipoEvento" VARCHAR(30) NOT NULL,
    "precioAnterior" NUMERIC(18,4),
    "precioNuevo" NUMERIC(18,4),
    "vigenteDesdeAnterior" TIMESTAMPTZ,
    "vigenteDesdeNuevo" TIMESTAMPTZ,
    "vigenteHastaAnterior" TIMESTAMPTZ,
    "vigenteHastaNuevo" TIMESTAMPTZ,
    CONSTRAINT "pk_auditoria_precio" PRIMARY KEY ("idAuditoriaPrecio"),
    CONSTRAINT "ck_auditoria_precio_0" CHECK ("tipoEvento" IN ('MODIFICACION_PROGRAMADA','CANCELACION_PROGRAMADA','CIERRE_VIGENCIA'))
);

CREATE TABLE "PROMOCION" (
    "idPromocion" BIGINT NOT NULL,
    "descripcion" VARCHAR(300) NOT NULL,
    "porcentajeDescuento" NUMERIC(5,2) NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ NOT NULL,
    "estado" VARCHAR(30) NOT NULL,
    "fechaHoraCreacion" TIMESTAMPTZ NOT NULL,
    "idUsuarioCreador" BIGINT NOT NULL,
    "codigoAccesoOnline" UUID NOT NULL,
    CONSTRAINT "pk_promocion" PRIMARY KEY ("idPromocion"),
    CONSTRAINT "uq_promocion_0" UNIQUE ("codigoAccesoOnline"),
    CONSTRAINT "ck_promocion_0" CHECK ("porcentajeDescuento">0),
    CONSTRAINT "ck_promocion_1" CHECK ("porcentajeDescuento"<=100),
    CONSTRAINT "ck_promocion_2" CHECK ("vigenteHasta">"vigenteDesde")
);

CREATE TABLE "PROMOCION_PRODUCTO" (
    "idPromocion" BIGINT NOT NULL,
    "idProducto" BIGINT NOT NULL,
    CONSTRAINT "pk_promocion_producto" PRIMARY KEY ("idPromocion", "idProducto")
);

CREATE TABLE "DESTINATARIO_PROMOCION" (
    "idPromocion" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "fechaHoraSeleccion" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_destinatario_promocion" PRIMARY KEY ("idPromocion", "idCliente")
);

CREATE TABLE "INTENTO_COMUNICACION" (
    "idPromocion" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "numeroIntento" INTEGER NOT NULL,
    "fechaHoraIntento" TIMESTAMPTZ NOT NULL,
    "canal" VARCHAR(30) NOT NULL,
    "resultado" VARCHAR(30) NOT NULL,
    "fechaHoraResultado" TIMESTAMPTZ,
    "claveIdempotencia" UUID NOT NULL,
    "referenciaExterna" VARCHAR(150),
    CONSTRAINT "pk_intento_comunicacion" PRIMARY KEY ("idPromocion", "idCliente", "numeroIntento"),
    CONSTRAINT "uq_intento_comunicacion_0" UNIQUE ("claveIdempotencia"),
    CONSTRAINT "uq_intento_comunicacion_1" UNIQUE ("referenciaExterna"),
    CONSTRAINT "ck_intento_comunicacion_0" CHECK ("numeroIntento">0),
    CONSTRAINT "ck_intento_comunicacion_1" CHECK ("canal"='WHATSAPP'),
    CONSTRAINT "ck_intento_comunicacion_2" CHECK ("fechaHoraResultado" IS NULL OR "fechaHoraResultado">="fechaHoraIntento")
);

CREATE TABLE "POLITICA_CATEGORIA_COMERCIAL" (
    "idPoliticaComercial" BIGINT NOT NULL,
    "diasToleranciaMora" INTEGER NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "idUsuarioRegistro" BIGINT NOT NULL,
    CONSTRAINT "pk_politica_categoria_comercial" PRIMARY KEY ("idPoliticaComercial"),
    CONSTRAINT "ck_politica_categoria_comercial_0" CHECK ("diasToleranciaMora">=0),
    CONSTRAINT "ck_politica_categoria_comercial_1" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde")
);

CREATE TABLE "AJUSTE_POLITICA_CATEGORIA" (
    "idPoliticaComercial" BIGINT NOT NULL,
    "categoriaComercial" VARCHAR(20) NOT NULL,
    "tipoAjuste" VARCHAR(15) NOT NULL,
    "porcentajeAjuste" NUMERIC(5,2) NOT NULL,
    CONSTRAINT "pk_ajuste_politica_categoria" PRIMARY KEY ("idPoliticaComercial", "categoriaComercial"),
    CONSTRAINT "ck_ajuste_politica_categoria_0" CHECK ("categoriaComercial" IN ('CUMPLIDOR','EN_MORA')),
    CONSTRAINT "ck_ajuste_politica_categoria_1" CHECK ("tipoAjuste" IN ('DESCUENTO','RECARGO','SIN_AJUSTE')),
    CONSTRAINT "ck_ajuste_politica_categoria_2" CHECK ("porcentajeAjuste" BETWEEN 0 AND 100),
    CONSTRAINT "ck_ajuste_politica_categoria_3" CHECK (("tipoAjuste"='SIN_AJUSTE' AND "porcentajeAjuste"=0) OR ("tipoAjuste" IN ('DESCUENTO','RECARGO') AND "porcentajeAjuste">0))
);

CREATE TABLE "CLIENTE" (
    "idCliente" BIGINT NOT NULL,
    "nombres" VARCHAR(120) NOT NULL,
    "apellidos" VARCHAR(120),
    "telefonoPrincipal" VARCHAR(20) NOT NULL,
    "calle" VARCHAR(150),
    "numeroPuerta" VARCHAR(20),
    "piso" VARCHAR(10),
    "unidadFuncional" VARCHAR(20),
    "localidad" VARCHAR(100),
    "provincia" VARCHAR(100),
    "referenciaDomicilio" VARCHAR(250),
    "zona" VARCHAR(100),
    "diaHabitualReparto" SMALLINT,
    "estado" VARCHAR(10) NOT NULL,
    "categoriaComercial" VARCHAR(20) NOT NULL,
    "fechaHoraAlta" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_cliente" PRIMARY KEY ("idCliente"),
    CONSTRAINT "ck_cliente_0" CHECK ("estado" IN ('ACTIVO','INACTIVO')),
    CONSTRAINT "ck_cliente_1" CHECK ("categoriaComercial" IN ('CUMPLIDOR','EN_MORA')),
    CONSTRAINT "ck_cliente_2" CHECK ("diaHabitualReparto" IS NULL OR "diaHabitualReparto" BETWEEN 1 AND 7)
);

CREATE TABLE "HISTORIAL_ESTADO_CLIENTE" (
    "idCliente" BIGINT NOT NULL,
    "numeroCambio" INTEGER NOT NULL,
    "estadoAnterior" VARCHAR(10) NOT NULL,
    "estadoNuevo" VARCHAR(10) NOT NULL,
    "fechaHoraCambio" TIMESTAMPTZ NOT NULL,
    "motivo" TEXT,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    CONSTRAINT "pk_historial_estado_cliente" PRIMARY KEY ("idCliente", "numeroCambio"),
    CONSTRAINT "ck_historial_estado_cliente_0" CHECK ("numeroCambio">0),
    CONSTRAINT "ck_historial_estado_cliente_1" CHECK ("estadoAnterior" IN ('ACTIVO','INACTIVO')),
    CONSTRAINT "ck_historial_estado_cliente_2" CHECK ("estadoNuevo" IN ('ACTIVO','INACTIVO')),
    CONSTRAINT "ck_historial_estado_cliente_3" CHECK ("estadoAnterior"<>"estadoNuevo")
);

CREATE TABLE "USUARIO" (
    "idUsuario" BIGINT NOT NULL,
    "nombreUsuario" VARCHAR(80) NOT NULL,
    "credencialProtegida" VARCHAR(255) NOT NULL,
    "estado" VARCHAR(15) NOT NULL,
    "cambioCredencialPendiente" BOOLEAN NOT NULL,
    "fechaHoraAlta" TIMESTAMPTZ NOT NULL,
    "fechaHoraDeshabilitacion" TIMESTAMPTZ,
    CONSTRAINT "pk_usuario" PRIMARY KEY ("idUsuario"),
    CONSTRAINT "ck_usuario_0" CHECK ("estado" IN ('HABILITADO','DESHABILITADO'))
);

CREATE TABLE "ROL" (
    "codigoRol" VARCHAR(30) NOT NULL,
    "nombreMostrado" VARCHAR(100) NOT NULL,
    CONSTRAINT "pk_rol" PRIMARY KEY ("codigoRol"),
    CONSTRAINT "uq_rol_0" UNIQUE ("nombreMostrado"),
    CONSTRAINT "ck_rol_0" CHECK ("codigoRol" IN ('ADMINISTRADOR','VENDEDOR','RESPONSABLE_PRODUCCION','REPARTIDOR','PROVEEDOR'))
);

CREATE TABLE "USUARIO_ROL" (
    "idUsuario" BIGINT NOT NULL,
    "codigoRol" VARCHAR(30) NOT NULL,
    "fechaHoraAsignacion" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_usuario_rol" PRIMARY KEY ("idUsuario", "codigoRol")
);

CREATE TABLE "EVENTO_ASIGNACION_ROL" (
    "idEventoRol" BIGINT NOT NULL,
    "idUsuario" BIGINT NOT NULL,
    "codigoRol" VARCHAR(30) NOT NULL,
    "tipoEvento" VARCHAR(15) NOT NULL,
    "fechaHoraEvento" TIMESTAMPTZ NOT NULL,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    CONSTRAINT "pk_evento_asignacion_rol" PRIMARY KEY ("idEventoRol"),
    CONSTRAINT "ck_evento_asignacion_rol_0" CHECK ("tipoEvento" IN ('ASIGNACION','REVOCACION'))
);

CREATE TABLE "USUARIO_PROVEEDOR" (
    "idUsuario" BIGINT NOT NULL,
    "idProveedor" BIGINT NOT NULL,
    "fechaHoraVinculacion" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_usuario_proveedor" PRIMARY KEY ("idUsuario")
);

CREATE TABLE "SESION_USUARIO" (
    "idSesionUsuario" UUID NOT NULL,
    "idUsuario" BIGINT NOT NULL,
    "tokenProtegido" BYTEA NOT NULL,
    "fechaHoraInicio" TIMESTAMPTZ NOT NULL,
    "fechaHoraExpiracion" TIMESTAMPTZ NOT NULL,
    "fechaHoraRevocacion" TIMESTAMPTZ,
    CONSTRAINT "pk_sesion_usuario" PRIMARY KEY ("idSesionUsuario"),
    CONSTRAINT "uq_sesion_usuario_0" UNIQUE ("tokenProtegido"),
    CONSTRAINT "ck_sesion_usuario_0" CHECK ("fechaHoraExpiracion">"fechaHoraInicio"),
    CONSTRAINT "ck_sesion_usuario_1" CHECK ("fechaHoraRevocacion" IS NULL OR "fechaHoraRevocacion">="fechaHoraInicio")
);

CREATE TABLE "DESAFIO_AUTENTICACION_CLIENTE" (
    "idDesafio" UUID NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "telefonoDestino" VARCHAR(20) NOT NULL,
    "codigoProtegido" BYTEA NOT NULL,
    "fechaHoraCreacion" TIMESTAMPTZ NOT NULL,
    "fechaHoraAceptacionEnvio" TIMESTAMPTZ,
    "fechaHoraExpiracion" TIMESTAMPTZ NOT NULL,
    "intentosFallidos" SMALLINT NOT NULL,
    "fechaHoraValidacion" TIMESTAMPTZ,
    "fechaHoraBloqueo" TIMESTAMPTZ,
    CONSTRAINT "pk_desafio_autenticacion_cliente" PRIMARY KEY ("idDesafio"),
    CONSTRAINT "ck_desafio_autenticacion_cliente_0" CHECK ("fechaHoraExpiracion">"fechaHoraCreacion"),
    CONSTRAINT "ck_desafio_autenticacion_cliente_1" CHECK ("intentosFallidos">=0)
);

CREATE TABLE "SESION_CLIENTE_ONLINE" (
    "idSesionOnline" UUID NOT NULL,
    "idDesafio" UUID NOT NULL,
    "tokenProtegido" BYTEA NOT NULL,
    "fechaHoraInicio" TIMESTAMPTZ NOT NULL,
    "fechaHoraExpiracion" TIMESTAMPTZ NOT NULL,
    "fechaHoraRevocacion" TIMESTAMPTZ,
    CONSTRAINT "pk_sesion_cliente_online" PRIMARY KEY ("idSesionOnline"),
    CONSTRAINT "uq_sesion_cliente_online_0" UNIQUE ("idDesafio"),
    CONSTRAINT "uq_sesion_cliente_online_1" UNIQUE ("tokenProtegido"),
    CONSTRAINT "ck_sesion_cliente_online_0" CHECK ("fechaHoraExpiracion">"fechaHoraInicio"),
    CONSTRAINT "ck_sesion_cliente_online_1" CHECK ("fechaHoraRevocacion" IS NULL OR "fechaHoraRevocacion">="fechaHoraInicio")
);

CREATE TABLE "PROVEEDOR" (
    "idProveedor" BIGINT NOT NULL,
    "cuit" CHAR(11) NOT NULL,
    "razonSocial" VARCHAR(180) NOT NULL,
    "telefonoContacto" VARCHAR(20),
    "emailContacto" VARCHAR(254),
    "calle" VARCHAR(150),
    "numeroPuerta" VARCHAR(20),
    "localidad" VARCHAR(100),
    "provincia" VARCHAR(100),
    "estado" VARCHAR(10) NOT NULL,
    "fechaHoraAlta" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_proveedor" PRIMARY KEY ("idProveedor"),
    CONSTRAINT "uq_proveedor_0" UNIQUE ("cuit"),
    CONSTRAINT "ck_proveedor_0" CHECK ("estado" IN ('ACTIVO','INACTIVO')),
    CONSTRAINT "ck_proveedor_1" CHECK ("cuit" ~ '^[0-9]{11}$')
);

CREATE TABLE "HISTORIAL_ESTADO_PROVEEDOR" (
    "idProveedor" BIGINT NOT NULL,
    "numeroCambio" INTEGER NOT NULL,
    "estadoAnterior" VARCHAR(10) NOT NULL,
    "estadoNuevo" VARCHAR(10) NOT NULL,
    "fechaHoraCambio" TIMESTAMPTZ NOT NULL,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    "motivo" TEXT,
    CONSTRAINT "pk_historial_estado_proveedor" PRIMARY KEY ("idProveedor", "numeroCambio"),
    CONSTRAINT "ck_historial_estado_proveedor_0" CHECK ("numeroCambio">0),
    CONSTRAINT "ck_historial_estado_proveedor_1" CHECK ("estadoAnterior"<>"estadoNuevo")
);

CREATE TABLE "PROVEEDOR_INSUMO" (
    "idProveedor" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    CONSTRAINT "pk_proveedor_insumo" PRIMARY KEY ("idProveedor", "idInsumo", "vigenteDesde"),
    CONSTRAINT "ck_proveedor_insumo_0" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde")
);

CREATE TABLE "EPISODIO_FALTANTE" (
    "idEpisodio" BIGINT NOT NULL,
    "idInsumo" BIGINT NOT NULL,
    "fechaHoraInicio" TIMESTAMPTZ NOT NULL,
    "fechaHoraCierre" TIMESTAMPTZ,
    "idMovimientoApertura" BIGINT,
    CONSTRAINT "pk_episodio_faltante" PRIMARY KEY ("idEpisodio"),
    CONSTRAINT "uq_episodio_faltante_0" UNIQUE ("idMovimientoApertura"),
    CONSTRAINT "ck_episodio_faltante_0" CHECK ("fechaHoraCierre" IS NULL OR "fechaHoraCierre">"fechaHoraInicio")
);

CREATE TABLE "SOLICITUD_COTIZACION" (
    "idSolicitud" BIGINT NOT NULL,
    "idEpisodio" BIGINT NOT NULL,
    "idSolicitudAnterior" BIGINT,
    "idUsuarioEmisor" BIGINT,
    "cantidadSolicitada" NUMERIC(18,3) NOT NULL,
    "fechaHoraEmision" TIMESTAMPTZ NOT NULL,
    "fechaHoraVencimiento" TIMESTAMPTZ NOT NULL,
    "fechaHoraFinRecepcion" TIMESTAMPTZ,
    "modalidadInicio" VARCHAR(15) NOT NULL,
    "estado" VARCHAR(30) NOT NULL,
    "motivoCierre" VARCHAR(40),
    "fechaHoraResolucion" TIMESTAMPTZ,
    CONSTRAINT "pk_solicitud_cotizacion" PRIMARY KEY ("idSolicitud"),
    CONSTRAINT "uq_solicitud_cotizacion_0" UNIQUE ("idSolicitud", "idEpisodio"),
    CONSTRAINT "uq_solicitud_cotizacion_1" UNIQUE ("idSolicitudAnterior"),
    CONSTRAINT "ck_solicitud_cotizacion_0" CHECK ("cantidadSolicitada">0),
    CONSTRAINT "ck_solicitud_cotizacion_1" CHECK ("fechaHoraVencimiento">"fechaHoraEmision"),
    CONSTRAINT "ck_solicitud_cotizacion_2" CHECK ("modalidadInicio" IN ('AUTOMATICA','MANUAL')),
    CONSTRAINT "ck_solicitud_cotizacion_3" CHECK ("estado" IN ('RECEPCION_ABIERTA','PENDIENTE_DECISION','RESUELTA_CON_COMPRA','RESUELTA_SIN_COMPRA'))
);

CREATE TABLE "INVITACION_COTIZACION" (
    "idSolicitud" BIGINT NOT NULL,
    "idProveedor" BIGINT NOT NULL,
    CONSTRAINT "pk_invitacion_cotizacion" PRIMARY KEY ("idSolicitud", "idProveedor")
);

CREATE TABLE "RESPUESTA_COTIZACION" (
    "idRespuesta" BIGINT NOT NULL,
    "idSolicitud" BIGINT NOT NULL,
    "idProveedor" BIGINT NOT NULL,
    "idUsuarioRegistrador" BIGINT NOT NULL,
    "cantidadOfrecida" NUMERIC(18,3) NOT NULL,
    "precioUnitario" NUMERIC(18,4),
    "tiempoEntregaEstimado" INTERVAL,
    "fechaHoraRespuesta" TIMESTAMPTZ NOT NULL,
    CONSTRAINT "pk_respuesta_cotizacion" PRIMARY KEY ("idRespuesta"),
    CONSTRAINT "uq_respuesta_cotizacion_0" UNIQUE ("idSolicitud", "idProveedor"),
    CONSTRAINT "uq_respuesta_cotizacion_1" UNIQUE ("idRespuesta", "idSolicitud"),
    CONSTRAINT "ck_respuesta_cotizacion_0" CHECK ("cantidadOfrecida">=0),
    CONSTRAINT "ck_respuesta_cotizacion_1" CHECK (("cantidadOfrecida"=0 AND "precioUnitario" IS NULL AND "tiempoEntregaEstimado" IS NULL) OR ("cantidadOfrecida">0 AND "precioUnitario" IS NOT NULL AND "precioUnitario">0 AND "tiempoEntregaEstimado" IS NOT NULL AND "tiempoEntregaEstimado">INTERVAL '0 seconds'))
);

CREATE TABLE "POLITICA_COMPRA" (
    "idPoliticaCompra" BIGINT NOT NULL,
    "numeroVersion" INTEGER NOT NULL,
    "vigenteDesde" TIMESTAMPTZ NOT NULL,
    "vigenteHasta" TIMESTAMPTZ,
    "periodoCalculoDias" INTEGER,
    "fechaHoraRegistro" TIMESTAMPTZ NOT NULL,
    "idUsuarioRegistro" BIGINT NOT NULL,
    CONSTRAINT "pk_politica_compra" PRIMARY KEY ("idPoliticaCompra"),
    CONSTRAINT "uq_politica_compra_0" UNIQUE ("numeroVersion"),
    CONSTRAINT "ck_politica_compra_0" CHECK ("numeroVersion">0),
    CONSTRAINT "ck_politica_compra_1" CHECK ("periodoCalculoDias" IS NULL OR "periodoCalculoDias">0),
    CONSTRAINT "ck_politica_compra_2" CHECK ("vigenteHasta" IS NULL OR "vigenteHasta">"vigenteDesde")
);

CREATE TABLE "CRITERIO_POLITICA_COMPRA" (
    "idPoliticaCompra" BIGINT NOT NULL,
    "codigoCriterio" VARCHAR(40) NOT NULL,
    "pesoRelativo" NUMERIC(12,6),
    "ordenDesempate" SMALLINT,
    CONSTRAINT "pk_criterio_politica_compra" PRIMARY KEY ("idPoliticaCompra", "codigoCriterio"),
    CONSTRAINT "uq_criterio_politica_compra_0" UNIQUE ("idPoliticaCompra", "ordenDesempate"),
    CONSTRAINT "ck_criterio_politica_compra_0" CHECK ("pesoRelativo" IS NULL OR "pesoRelativo">=0),
    CONSTRAINT "ck_criterio_politica_compra_1" CHECK ("ordenDesempate" IS NULL OR "ordenDesempate">0)
);

CREATE TABLE "DECISION_COMPRA" (
    "idDecisionCompra" BIGINT NOT NULL,
    "idSolicitud" BIGINT NOT NULL,
    "idPoliticaCompra" BIGINT NOT NULL,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    "idRespuestaSeleccionada" BIGINT,
    "fechaHoraDecision" TIMESTAMPTZ NOT NULL,
    "resultado" VARCHAR(15) NOT NULL,
    "justificacion" TEXT NOT NULL,
    CONSTRAINT "pk_decision_compra" PRIMARY KEY ("idDecisionCompra"),
    CONSTRAINT "uq_decision_compra_0" UNIQUE ("idSolicitud"),
    CONSTRAINT "ck_decision_compra_0" CHECK ("resultado" IN ('COMPRA','SIN_COMPRA')),
    CONSTRAINT "ck_decision_compra_1" CHECK (("resultado"='COMPRA' AND "idRespuestaSeleccionada" IS NOT NULL) OR ("resultado"='SIN_COMPRA' AND "idRespuestaSeleccionada" IS NULL))
);

CREATE TABLE "DETALLE_DECISION" (
    "idDecisionCompra" BIGINT NOT NULL,
    "idRespuesta" BIGINT NOT NULL,
    "fechaHoraEvaluacion" TIMESTAMPTZ NOT NULL,
    "elegible" BOOLEAN NOT NULL,
    "estadoProveedorEvaluado" VARCHAR(10) NOT NULL,
    "motivoInelegibilidad" TEXT,
    CONSTRAINT "pk_detalle_decision" PRIMARY KEY ("idDecisionCompra", "idRespuesta"),
    CONSTRAINT "ck_detalle_decision_0" CHECK ("estadoProveedorEvaluado" IN ('ACTIVO','INACTIVO')),
    CONSTRAINT "ck_detalle_decision_1" CHECK ("elegible"=TRUE OR ("motivoInelegibilidad" IS NOT NULL AND length(trim("motivoInelegibilidad"))>0))
);

CREATE TABLE "DETALLE_DECISION_CRITERIO" (
    "idDecisionCompra" BIGINT NOT NULL,
    "idRespuesta" BIGINT NOT NULL,
    "codigoCriterio" VARCHAR(40) NOT NULL,
    "valorNumerico" NUMERIC(18,6),
    "valorTexto" VARCHAR(200),
    "puntajeEvaluado" NUMERIC(18,6),
    CONSTRAINT "pk_detalle_decision_criterio" PRIMARY KEY ("idDecisionCompra", "idRespuesta", "codigoCriterio"),
    CONSTRAINT "ck_detalle_decision_criterio_0" CHECK (("valorNumerico" IS NOT NULL AND "valorTexto" IS NULL) OR ("valorNumerico" IS NULL AND "valorTexto" IS NOT NULL))
);

CREATE TABLE "ORDEN_COMPRA" (
    "idOrdenCompra" BIGINT NOT NULL,
    "idDecisionCompra" BIGINT NOT NULL,
    "cantidadOrdenada" NUMERIC(18,3) NOT NULL,
    "precioUnitarioAcordado" NUMERIC(18,4) NOT NULL,
    "fechaHoraEmision" TIMESTAMPTZ NOT NULL,
    "fechaHoraComprometida" TIMESTAMPTZ,
    "estado" VARCHAR(30) NOT NULL,
    "fechaHoraCancelacion" TIMESTAMPTZ,
    "idUsuarioCancelacion" BIGINT,
    "motivoCancelacion" TEXT,
    "cancelacionAtribuibleProveedor" BOOLEAN,
    CONSTRAINT "pk_orden_compra" PRIMARY KEY ("idOrdenCompra"),
    CONSTRAINT "uq_orden_compra_0" UNIQUE ("idDecisionCompra"),
    CONSTRAINT "ck_orden_compra_0" CHECK ("cantidadOrdenada">0),
    CONSTRAINT "ck_orden_compra_1" CHECK ("precioUnitarioAcordado">0),
    CONSTRAINT "ck_orden_compra_2" CHECK ("estado" IN ('PENDIENTE','PARCIALMENTE_RECIBIDA','RECIBIDA','CANCELADA'))
);

CREATE TABLE "RECEPCION_COMPRA" (
    "idRecepcionCompra" BIGINT NOT NULL,
    "idOrdenCompra" BIGINT,
    "idProveedorDirecto" BIGINT,
    "idInsumoRecibido" BIGINT,
    "idProductoReventa" BIGINT,
    "idUsuarioResponsable" BIGINT NOT NULL,
    "fechaHoraRecepcion" TIMESTAMPTZ NOT NULL,
    "cantidadRecibida" NUMERIC(18,3) NOT NULL,
    "codigoUnidadMedida" VARCHAR(20) NOT NULL,
    "costoUnitarioReal" NUMERIC(18,4) NOT NULL,
    "factorConversionAplicado" NUMERIC(24,12) NOT NULL,
    "referenciaComercial" VARCHAR(200),
    CONSTRAINT "pk_recepcion_compra" PRIMARY KEY ("idRecepcionCompra"),
    CONSTRAINT "uq_recepcion_compra_0" UNIQUE ("idRecepcionCompra", "idInsumoRecibido"),
    CONSTRAINT "uq_recepcion_compra_1" UNIQUE ("idRecepcionCompra", "idProductoReventa"),
    CONSTRAINT "ck_recepcion_compra_0" CHECK ("cantidadRecibida">0),
    CONSTRAINT "ck_recepcion_compra_1" CHECK ("costoUnitarioReal">0),
    CONSTRAINT "ck_recepcion_compra_2" CHECK ("factorConversionAplicado">0),
    CONSTRAINT "ck_recepcion_compra_3" CHECK (("idInsumoRecibido" IS NOT NULL AND "idProductoReventa" IS NULL) OR ("idInsumoRecibido" IS NULL AND "idProductoReventa" IS NOT NULL)),
    CONSTRAINT "ck_recepcion_compra_4" CHECK ("idOrdenCompra" IS NULL OR ("idInsumoRecibido" IS NOT NULL AND "idProductoReventa" IS NULL AND "idProveedorDirecto" IS NULL))
);

CREATE TABLE "MOVIMIENTO_STOCK_REVENTA" (
    "idMovimientoStockReventa" BIGINT NOT NULL,
    "idProducto" BIGINT NOT NULL,
    "numeroSecuenciaProducto" BIGINT NOT NULL,
    "idRecepcionCompra" BIGINT,
    "idPedidoEntrega" BIGINT,
    "numeroDetalleEntrega" INTEGER,
    "idValoracionReventa" BIGINT,
    "idUsuarioResponsable" BIGINT NOT NULL,
    "tipoMovimiento" VARCHAR(20) NOT NULL,
    "cantidad" NUMERIC(18,3) NOT NULL,
    "fechaHoraMovimiento" TIMESTAMPTZ NOT NULL,
    "stockResultante" NUMERIC(18,3) NOT NULL,
    "costoPromedioResultante" NUMERIC(18,4),
    "costoTotalHistoricoVenta" NUMERIC(18,4),
    "motivo" TEXT,
    "observacion" TEXT,
    CONSTRAINT "pk_movimiento_stock_reventa" PRIMARY KEY ("idMovimientoStockReventa"),
    CONSTRAINT "uq_movimiento_stock_reventa_0" UNIQUE ("idProducto", "numeroSecuenciaProducto"),
    CONSTRAINT "uq_movimiento_stock_reventa_1" UNIQUE ("idRecepcionCompra"),
    CONSTRAINT "uq_movimiento_stock_reventa_2" UNIQUE ("idPedidoEntrega", "numeroDetalleEntrega"),
    CONSTRAINT "uq_movimiento_stock_reventa_3" UNIQUE ("idValoracionReventa"),
    CONSTRAINT "ck_movimiento_stock_reventa_0" CHECK ("numeroSecuenciaProducto">0),
    CONSTRAINT "ck_movimiento_stock_reventa_1" CHECK ("cantidad">0),
    CONSTRAINT "ck_movimiento_stock_reventa_2" CHECK ("stockResultante">=0),
    CONSTRAINT "ck_movimiento_stock_reventa_3" CHECK ("tipoMovimiento" IN ('INGRESO_COMPRA','EGRESO_VENTA','AJUSTE_POSITIVO','AJUSTE_NEGATIVO')),
    CONSTRAINT "ck_movimiento_stock_reventa_4" CHECK ("costoPromedioResultante" IS NULL OR "costoPromedioResultante">0)
);

CREATE TABLE "VALORACION_EXTRAORDINARIA_REVENTA" (
    "idValoracionReventa" BIGINT NOT NULL,
    "idProducto" BIGINT NOT NULL,
    "tipoValoracion" VARCHAR(20) NOT NULL,
    "costoUnitario" NUMERIC(18,4) NOT NULL,
    "fechaHoraValoracion" TIMESTAMPTZ NOT NULL,
    "motivo" TEXT NOT NULL,
    "idUsuarioResponsable" BIGINT NOT NULL,
    CONSTRAINT "pk_valoracion_extraordinaria_reventa" PRIMARY KEY ("idValoracionReventa"),
    CONSTRAINT "uq_valoracion_extraordinaria_reventa_0" UNIQUE ("idValoracionReventa", "idProducto"),
    CONSTRAINT "ck_valoracion_extraordinaria_reventa_0" CHECK ("tipoValoracion" IN ('INICIAL','REGULARIZACION')),
    CONSTRAINT "ck_valoracion_extraordinaria_reventa_1" CHECK ("costoUnitario">0)
);

CREATE TABLE "COBRO" (
    "idCobro" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "idPedido" BIGINT,
    "idUsuarioRegistrador" BIGINT,
    "fechaHoraCobro" TIMESTAMPTZ NOT NULL,
    "importe" NUMERIC(18,2) NOT NULL,
    "medioPago" VARCHAR(20) NOT NULL,
    "origen" VARCHAR(20) NOT NULL,
    "referenciaExterna" VARCHAR(150),
    "claveIdempotenciaOrigen" UUID,
    "estadoCobro" VARCHAR(20) NOT NULL,
    CONSTRAINT "pk_cobro" PRIMARY KEY ("idCobro"),
    CONSTRAINT "uq_cobro_0" UNIQUE ("claveIdempotenciaOrigen"),
    CONSTRAINT "uq_cobro_1" UNIQUE ("medioPago", "referenciaExterna"),
    CONSTRAINT "uq_cobro_2" UNIQUE ("idCobro", "importe"),
    CONSTRAINT "ck_cobro_0" CHECK ("importe">0),
    CONSTRAINT "ck_cobro_1" CHECK ("medioPago" IN ('EFECTIVO','TRANSFERENCIA','MERCADOPAGO','OTRO')),
    CONSTRAINT "ck_cobro_2" CHECK ("origen" IN ('REPARTO','ONLINE','ADMINISTRACION'))
);

CREATE TABLE "MOVIMIENTO_CUENTA" (
    "idMovimientoCuenta" BIGINT NOT NULL,
    "idCliente" BIGINT NOT NULL,
    "numeroSecuenciaCliente" BIGINT NOT NULL,
    "tipoMovimiento" VARCHAR(20) NOT NULL,
    "importe" NUMERIC(18,2) NOT NULL,
    "fechaHoraMovimiento" TIMESTAMPTZ NOT NULL,
    "saldoResultante" NUMERIC(18,2) NOT NULL,
    "idUsuarioAjuste" BIGINT,
    "observacion" TEXT,
    CONSTRAINT "pk_movimiento_cuenta" PRIMARY KEY ("idMovimientoCuenta"),
    CONSTRAINT "uq_movimiento_cuenta_0" UNIQUE ("idCliente", "numeroSecuenciaCliente"),
    CONSTRAINT "ck_movimiento_cuenta_0" CHECK ("numeroSecuenciaCliente">0),
    CONSTRAINT "ck_movimiento_cuenta_1" CHECK ("importe">=0),
    CONSTRAINT "ck_movimiento_cuenta_2" CHECK ("tipoMovimiento" IN ('CARGO_POR_VENTA','PAGO','AJUSTE_DEBITO','AJUSTE_CREDITO'))
);

CREATE TABLE "ORIGEN_MOVIMIENTO_CUENTA" (
    "idMovimientoCuenta" BIGINT NOT NULL,
    "idPedidoEntrega" BIGINT,
    "idCobro" BIGINT,
    "idReembolso" BIGINT,
    CONSTRAINT "pk_origen_movimiento_cuenta" PRIMARY KEY ("idMovimientoCuenta"),
    CONSTRAINT "uq_origen_movimiento_cuenta_0" UNIQUE ("idPedidoEntrega"),
    CONSTRAINT "uq_origen_movimiento_cuenta_1" UNIQUE ("idCobro"),
    CONSTRAINT "uq_origen_movimiento_cuenta_2" UNIQUE ("idReembolso"),
    CONSTRAINT "ck_origen_movimiento_cuenta_0" CHECK (num_nonnulls("idPedidoEntrega","idCobro","idReembolso")=1)
);

CREATE TABLE "MOVIMIENTO_CAJA" (
    "idMovimientoCaja" BIGINT NOT NULL,
    "tipoMovimiento" VARCHAR(10) NOT NULL,
    "importe" NUMERIC(18,2) NOT NULL,
    "fechaHoraMovimiento" TIMESTAMPTZ NOT NULL,
    "origen" VARCHAR(15) NOT NULL,
    "concepto" VARCHAR(200),
    "referencia" VARCHAR(150),
    "observacion" TEXT,
    "idUsuarioRegistro" BIGINT,
    "idCobro" BIGINT,
    "idReembolso" BIGINT,
    CONSTRAINT "pk_movimiento_caja" PRIMARY KEY ("idMovimientoCaja"),
    CONSTRAINT "uq_movimiento_caja_0" UNIQUE ("idCobro"),
    CONSTRAINT "uq_movimiento_caja_1" UNIQUE ("idReembolso"),
    CONSTRAINT "ck_movimiento_caja_0" CHECK ("tipoMovimiento" IN ('INGRESO','EGRESO')),
    CONSTRAINT "ck_movimiento_caja_1" CHECK ("importe">0),
    CONSTRAINT "ck_movimiento_caja_2" CHECK ("origen" IN ('MANUAL','COBRO','REEMBOLSO'))
);

CREATE TABLE "REEMBOLSO" (
    "idReembolso" BIGINT NOT NULL,
    "idCobro" BIGINT NOT NULL,
    "importeReembolso" NUMERIC(18,2) NOT NULL,
    "fechaHoraCreacion" TIMESTAMPTZ NOT NULL,
    "fechaHoraProcesamiento" TIMESTAMPTZ,
    "motivo" TEXT NOT NULL,
    "estado" VARCHAR(15) NOT NULL,
    CONSTRAINT "pk_reembolso" PRIMARY KEY ("idReembolso"),
    CONSTRAINT "uq_reembolso_0" UNIQUE ("idCobro"),
    CONSTRAINT "ck_reembolso_0" CHECK ("importeReembolso">0),
    CONSTRAINT "ck_reembolso_1" CHECK ("estado" IN ('PENDIENTE','PROCESADO','RECHAZADO')),
    CONSTRAINT "ck_reembolso_2" CHECK (("estado"='PROCESADO' AND "fechaHoraProcesamiento" IS NOT NULL) OR ("estado" IN ('PENDIENTE','RECHAZADO') AND "fechaHoraProcesamiento" IS NULL))
);

CREATE TABLE "INTENTO_REEMBOLSO" (
    "idReembolso" BIGINT NOT NULL,
    "numeroIntento" INTEGER NOT NULL,
    "fechaHoraSolicitud" TIMESTAMPTZ NOT NULL,
    "claveIdempotencia" UUID NOT NULL,
    "referenciaOperacionExterna" VARCHAR(150),
    CONSTRAINT "pk_intento_reembolso" PRIMARY KEY ("idReembolso", "numeroIntento"),
    CONSTRAINT "uq_intento_reembolso_0" UNIQUE ("claveIdempotencia"),
    CONSTRAINT "uq_intento_reembolso_1" UNIQUE ("referenciaOperacionExterna"),
    CONSTRAINT "ck_intento_reembolso_0" CHECK ("numeroIntento">0)
);

CREATE TABLE "EVENTO_RESULTADO_REEMBOLSO" (
    "idEventoResultado" BIGINT NOT NULL,
    "idReembolso" BIGINT,
    "numeroIntento" INTEGER,
    "identificadorEventoExterno" VARCHAR(150),
    "referenciaOperacionExterna" VARCHAR(150),
    "origenResultado" VARCHAR(25) NOT NULL,
    "resultadoInformado" VARCHAR(15),
    "fechaHoraRecepcion" TIMESTAMPTZ NOT NULL,
    "autenticado" BOOLEAN NOT NULL,
    "aplicable" BOOLEAN NOT NULL,
    "motivoNoAplicacion" TEXT,
    CONSTRAINT "pk_evento_resultado_reembolso" PRIMARY KEY ("idEventoResultado"),
    CONSTRAINT "uq_evento_resultado_reembolso_0" UNIQUE ("identificadorEventoExterno"),
    CONSTRAINT "ck_evento_resultado_reembolso_0" CHECK ("origenResultado" IN ('RESPUESTA_SINCRONICA','NOTIFICACION')),
    CONSTRAINT "ck_evento_resultado_reembolso_1" CHECK ("resultadoInformado" IS NULL OR "resultadoInformado" IN ('PENDIENTE','PROCESADO','RECHAZADO')),
    CONSTRAINT "ck_evento_resultado_reembolso_2" CHECK (NOT "aplicable" OR ("autenticado" AND "idReembolso" IS NOT NULL AND "numeroIntento" IS NOT NULL AND "resultadoInformado" IS NOT NULL)),
    CONSTRAINT "ck_evento_resultado_reembolso_3" CHECK ("aplicable" OR ("motivoNoAplicacion" IS NOT NULL AND length(trim("motivoNoAplicacion"))>0))
);

ALTER TABLE "PRODUCTO" ADD CONSTRAINT "fk_producto_0" FOREIGN KEY ("codigoUnidadVenta") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INSUMO" ADD CONSTRAINT "fk_insumo_0" FOREIGN KEY ("codigoUnidadMedida") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECETA" ADD CONSTRAINT "fk_receta_0" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "VERSION_RECETA" ADD CONSTRAINT "fk_version_receta_0" FOREIGN KEY ("idReceta") REFERENCES "RECETA" ("idReceta") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "VERSION_RECETA" ADD CONSTRAINT "fk_version_receta_1" FOREIGN KEY ("codigoUnidadRendimiento") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_VERSION_RECETA" ADD CONSTRAINT "fk_detalle_version_receta_0" FOREIGN KEY ("idReceta", "numeroVersion") REFERENCES "VERSION_RECETA" ("idReceta", "numeroVersion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "DETALLE_VERSION_RECETA" ADD CONSTRAINT "fk_detalle_version_receta_1" FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_VERSION_RECETA" ADD CONSTRAINT "fk_detalle_version_receta_2" FOREIGN KEY ("codigoUnidadCantidad") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PREPARACION" ADD CONSTRAINT "fk_preparacion_0" FOREIGN KEY ("idPedido", "numeroDetalle") REFERENCES "DETALLE_PEDIDO" ("idPedido", "numeroDetalle") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PREPARACION" ADD CONSTRAINT "fk_preparacion_1" FOREIGN KEY ("idReceta", "numeroVersion") REFERENCES "VERSION_RECETA" ("idReceta", "numeroVersion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "COSTO_INSUMO_HISTORICO" ADD CONSTRAINT "fk_costo_insumo_historico_0" FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "COSTO_INSUMO_HISTORICO" ADD CONSTRAINT "fk_costo_insumo_historico_1" FOREIGN KEY ("idRecepcionCompra", "idInsumo") REFERENCES "RECEPCION_COMPRA" ("idRecepcionCompra", "idInsumoRecibido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "fk_movimiento_insumo_0" FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "fk_movimiento_insumo_1" FOREIGN KEY ("idPreparacion") REFERENCES "PREPARACION" ("idPreparacion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "fk_movimiento_insumo_2" FOREIGN KEY ("idRecepcionCompra", "idInsumo") REFERENCES "RECEPCION_COMPRA" ("idRecepcionCompra", "idInsumoRecibido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "fk_movimiento_insumo_3" FOREIGN KEY ("idInsumo", "vigenteDesdeCosto") REFERENCES "COSTO_INSUMO_HISTORICO" ("idInsumo", "vigenteDesde") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "fk_movimiento_insumo_4" FOREIGN KEY ("idUsuarioResponsable") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PEDIDO" ADD CONSTRAINT "fk_pedido_0" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PEDIDO" ADD CONSTRAINT "fk_pedido_1" FOREIGN KEY ("idUsuarioCreador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PEDIDO" ADD CONSTRAINT "fk_pedido_2" FOREIGN KEY ("idSesionOnline") REFERENCES "SESION_CLIENTE_ONLINE" ("idSesionOnline") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PEDIDO" ADD CONSTRAINT "fk_pedido_3" FOREIGN KEY ("idPoliticaComercialAplicada") REFERENCES "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_PEDIDO" ADD CONSTRAINT "fk_detalle_pedido_0" FOREIGN KEY ("idPedido") REFERENCES "PEDIDO" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "DETALLE_PEDIDO" ADD CONSTRAINT "fk_detalle_pedido_1" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_PEDIDO" ADD CONSTRAINT "fk_detalle_pedido_2" FOREIGN KEY ("codigoUnidadVenta") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_PEDIDO" ADD CONSTRAINT "fk_detalle_pedido_3" FOREIGN KEY ("idPromocionAplicada") REFERENCES "PROMOCION" ("idPromocion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ASIGNACION_REPARTO" ADD CONSTRAINT "fk_asignacion_reparto_0" FOREIGN KEY ("idPedido") REFERENCES "PEDIDO" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ASIGNACION_REPARTO" ADD CONSTRAINT "fk_asignacion_reparto_1" FOREIGN KEY ("idUsuarioRepartidor") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ASIGNACION_REPARTO" ADD CONSTRAINT "fk_asignacion_reparto_2" FOREIGN KEY ("idUsuarioVendedor") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ENTREGA" ADD CONSTRAINT "fk_entrega_0" FOREIGN KEY ("idPedido") REFERENCES "PEDIDO" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ENTREGA" ADD CONSTRAINT "fk_entrega_1" FOREIGN KEY ("idUsuarioRepartidor") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_ENTREGA" ADD CONSTRAINT "fk_detalle_entrega_0" FOREIGN KEY ("idPedido") REFERENCES "ENTREGA" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "DETALLE_ENTREGA" ADD CONSTRAINT "fk_detalle_entrega_1" FOREIGN KEY ("idPedido", "numeroDetalle") REFERENCES "DETALLE_PEDIDO" ("idPedido", "numeroDetalle") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EXCEDENTE" ADD CONSTRAINT "fk_excedente_0" FOREIGN KEY ("idPedidoOrigen", "numeroDetalleOrigen") REFERENCES "DETALLE_PEDIDO" ("idPedido", "numeroDetalle") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EXCEDENTE" ADD CONSTRAINT "fk_excedente_1" FOREIGN KEY ("idMovimientoExcedenteOrigen") REFERENCES "MOVIMIENTO_EXCEDENTE" ("idMovimientoExcedente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "OFERTA_EXCEDENTE" ADD CONSTRAINT "fk_oferta_excedente_0" FOREIGN KEY ("idExcedente") REFERENCES "EXCEDENTE" ("idExcedente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "OFERTA_EXCEDENTE" ADD CONSTRAINT "fk_oferta_excedente_1" FOREIGN KEY ("idPedidoReceptor") REFERENCES "PEDIDO" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "OFERTA_EXCEDENTE" ADD CONSTRAINT "fk_oferta_excedente_2" FOREIGN KEY ("idUsuarioVendedor") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_EXCEDENTE" ADD CONSTRAINT "fk_movimiento_excedente_0" FOREIGN KEY ("idExcedente") REFERENCES "EXCEDENTE" ("idExcedente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_EXCEDENTE" ADD CONSTRAINT "fk_movimiento_excedente_1" FOREIGN KEY ("idOfertaExcedente", "idExcedente") REFERENCES "OFERTA_EXCEDENTE" ("idOfertaExcedente", "idExcedente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "IMPUTACION_ENTREGA_ADICIONAL" ADD CONSTRAINT "fk_imputacion_entrega_adicional_0" FOREIGN KEY ("idPedido", "numeroDetalle") REFERENCES "DETALLE_ENTREGA" ("idPedido", "numeroDetalle") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "IMPUTACION_ENTREGA_ADICIONAL" ADD CONSTRAINT "fk_imputacion_entrega_adicional_1" FOREIGN KEY ("idMovimientoExcedente") REFERENCES "MOVIMIENTO_EXCEDENTE" ("idMovimientoExcedente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PRECIO_PRODUCTO" ADD CONSTRAINT "fk_precio_producto_0" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PRECIO_PRODUCTO" ADD CONSTRAINT "fk_precio_producto_1" FOREIGN KEY ("idUsuarioRegistro") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PRECIO_PRODUCTO" ADD CONSTRAINT "fk_precio_producto_2" FOREIGN KEY ("idUsuarioCancelacion") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "AUDITORIA_PRECIO" ADD CONSTRAINT "fk_auditoria_precio_0" FOREIGN KEY ("idPrecio") REFERENCES "PRECIO_PRODUCTO" ("idPrecio") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "AUDITORIA_PRECIO" ADD CONSTRAINT "fk_auditoria_precio_1" FOREIGN KEY ("idUsuario") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PROMOCION" ADD CONSTRAINT "fk_promocion_0" FOREIGN KEY ("idUsuarioCreador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PROMOCION_PRODUCTO" ADD CONSTRAINT "fk_promocion_producto_0" FOREIGN KEY ("idPromocion") REFERENCES "PROMOCION" ("idPromocion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "PROMOCION_PRODUCTO" ADD CONSTRAINT "fk_promocion_producto_1" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DESTINATARIO_PROMOCION" ADD CONSTRAINT "fk_destinatario_promocion_0" FOREIGN KEY ("idPromocion") REFERENCES "PROMOCION" ("idPromocion") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "DESTINATARIO_PROMOCION" ADD CONSTRAINT "fk_destinatario_promocion_1" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INTENTO_COMUNICACION" ADD CONSTRAINT "fk_intento_comunicacion_0" FOREIGN KEY ("idPromocion", "idCliente") REFERENCES "DESTINATARIO_PROMOCION" ("idPromocion", "idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "POLITICA_CATEGORIA_COMERCIAL" ADD CONSTRAINT "fk_politica_categoria_comercial_0" FOREIGN KEY ("idUsuarioRegistro") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "AJUSTE_POLITICA_CATEGORIA" ADD CONSTRAINT "fk_ajuste_politica_categoria_0" FOREIGN KEY ("idPoliticaComercial") REFERENCES "POLITICA_CATEGORIA_COMERCIAL" ("idPoliticaComercial") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "HISTORIAL_ESTADO_CLIENTE" ADD CONSTRAINT "fk_historial_estado_cliente_0" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "HISTORIAL_ESTADO_CLIENTE" ADD CONSTRAINT "fk_historial_estado_cliente_1" FOREIGN KEY ("idUsuarioAdministrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "USUARIO_ROL" ADD CONSTRAINT "fk_usuario_rol_0" FOREIGN KEY ("idUsuario") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "USUARIO_ROL" ADD CONSTRAINT "fk_usuario_rol_1" FOREIGN KEY ("codigoRol") REFERENCES "ROL" ("codigoRol") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EVENTO_ASIGNACION_ROL" ADD CONSTRAINT "fk_evento_asignacion_rol_0" FOREIGN KEY ("idUsuario") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EVENTO_ASIGNACION_ROL" ADD CONSTRAINT "fk_evento_asignacion_rol_1" FOREIGN KEY ("codigoRol") REFERENCES "ROL" ("codigoRol") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EVENTO_ASIGNACION_ROL" ADD CONSTRAINT "fk_evento_asignacion_rol_2" FOREIGN KEY ("idUsuarioAdministrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "USUARIO_PROVEEDOR" ADD CONSTRAINT "fk_usuario_proveedor_0" FOREIGN KEY ("idUsuario") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "USUARIO_PROVEEDOR" ADD CONSTRAINT "fk_usuario_proveedor_1" FOREIGN KEY ("idProveedor") REFERENCES "PROVEEDOR" ("idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "SESION_USUARIO" ADD CONSTRAINT "fk_sesion_usuario_0" FOREIGN KEY ("idUsuario") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DESAFIO_AUTENTICACION_CLIENTE" ADD CONSTRAINT "fk_desafio_autenticacion_cliente_0" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "SESION_CLIENTE_ONLINE" ADD CONSTRAINT "fk_sesion_cliente_online_0" FOREIGN KEY ("idDesafio") REFERENCES "DESAFIO_AUTENTICACION_CLIENTE" ("idDesafio") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "HISTORIAL_ESTADO_PROVEEDOR" ADD CONSTRAINT "fk_historial_estado_proveedor_0" FOREIGN KEY ("idProveedor") REFERENCES "PROVEEDOR" ("idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "HISTORIAL_ESTADO_PROVEEDOR" ADD CONSTRAINT "fk_historial_estado_proveedor_1" FOREIGN KEY ("idUsuarioAdministrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PROVEEDOR_INSUMO" ADD CONSTRAINT "fk_proveedor_insumo_0" FOREIGN KEY ("idProveedor") REFERENCES "PROVEEDOR" ("idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "PROVEEDOR_INSUMO" ADD CONSTRAINT "fk_proveedor_insumo_1" FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EPISODIO_FALTANTE" ADD CONSTRAINT "fk_episodio_faltante_0" FOREIGN KEY ("idInsumo") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EPISODIO_FALTANTE" ADD CONSTRAINT "fk_episodio_faltante_1" FOREIGN KEY ("idMovimientoApertura") REFERENCES "MOVIMIENTO_INSUMO" ("idMovimientoInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "SOLICITUD_COTIZACION" ADD CONSTRAINT "fk_solicitud_cotizacion_0" FOREIGN KEY ("idEpisodio") REFERENCES "EPISODIO_FALTANTE" ("idEpisodio") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "SOLICITUD_COTIZACION" ADD CONSTRAINT "fk_solicitud_cotizacion_1" FOREIGN KEY ("idUsuarioEmisor") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "SOLICITUD_COTIZACION" ADD CONSTRAINT "fk_solicitud_cotizacion_2" FOREIGN KEY ("idSolicitudAnterior", "idEpisodio") REFERENCES "SOLICITUD_COTIZACION" ("idSolicitud", "idEpisodio") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INVITACION_COTIZACION" ADD CONSTRAINT "fk_invitacion_cotizacion_0" FOREIGN KEY ("idSolicitud") REFERENCES "SOLICITUD_COTIZACION" ("idSolicitud") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INVITACION_COTIZACION" ADD CONSTRAINT "fk_invitacion_cotizacion_1" FOREIGN KEY ("idProveedor") REFERENCES "PROVEEDOR" ("idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RESPUESTA_COTIZACION" ADD CONSTRAINT "fk_respuesta_cotizacion_0" FOREIGN KEY ("idSolicitud", "idProveedor") REFERENCES "INVITACION_COTIZACION" ("idSolicitud", "idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RESPUESTA_COTIZACION" ADD CONSTRAINT "fk_respuesta_cotizacion_1" FOREIGN KEY ("idUsuarioRegistrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "POLITICA_COMPRA" ADD CONSTRAINT "fk_politica_compra_0" FOREIGN KEY ("idUsuarioRegistro") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "CRITERIO_POLITICA_COMPRA" ADD CONSTRAINT "fk_criterio_politica_compra_0" FOREIGN KEY ("idPoliticaCompra") REFERENCES "POLITICA_COMPRA" ("idPoliticaCompra") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE CASCADE;
ALTER TABLE "DECISION_COMPRA" ADD CONSTRAINT "fk_decision_compra_0" FOREIGN KEY ("idSolicitud") REFERENCES "SOLICITUD_COTIZACION" ("idSolicitud") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DECISION_COMPRA" ADD CONSTRAINT "fk_decision_compra_1" FOREIGN KEY ("idPoliticaCompra") REFERENCES "POLITICA_COMPRA" ("idPoliticaCompra") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DECISION_COMPRA" ADD CONSTRAINT "fk_decision_compra_2" FOREIGN KEY ("idUsuarioAdministrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DECISION_COMPRA" ADD CONSTRAINT "fk_decision_compra_3" FOREIGN KEY ("idRespuestaSeleccionada", "idSolicitud") REFERENCES "RESPUESTA_COTIZACION" ("idRespuesta", "idSolicitud") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_DECISION" ADD CONSTRAINT "fk_detalle_decision_0" FOREIGN KEY ("idDecisionCompra") REFERENCES "DECISION_COMPRA" ("idDecisionCompra") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_DECISION" ADD CONSTRAINT "fk_detalle_decision_1" FOREIGN KEY ("idRespuesta") REFERENCES "RESPUESTA_COTIZACION" ("idRespuesta") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "DETALLE_DECISION_CRITERIO" ADD CONSTRAINT "fk_detalle_decision_criterio_0" FOREIGN KEY ("idDecisionCompra", "idRespuesta") REFERENCES "DETALLE_DECISION" ("idDecisionCompra", "idRespuesta") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORDEN_COMPRA" ADD CONSTRAINT "fk_orden_compra_0" FOREIGN KEY ("idDecisionCompra") REFERENCES "DECISION_COMPRA" ("idDecisionCompra") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORDEN_COMPRA" ADD CONSTRAINT "fk_orden_compra_1" FOREIGN KEY ("idUsuarioCancelacion") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_0" FOREIGN KEY ("idOrdenCompra") REFERENCES "ORDEN_COMPRA" ("idOrdenCompra") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_1" FOREIGN KEY ("idProveedorDirecto") REFERENCES "PROVEEDOR" ("idProveedor") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_2" FOREIGN KEY ("idInsumoRecibido") REFERENCES "INSUMO" ("idInsumo") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_3" FOREIGN KEY ("idProductoReventa") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_4" FOREIGN KEY ("idUsuarioResponsable") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "RECEPCION_COMPRA" ADD CONSTRAINT "fk_recepcion_compra_5" FOREIGN KEY ("codigoUnidadMedida") REFERENCES "UNIDAD_MEDIDA" ("codigoUnidad") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_0" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_1" FOREIGN KEY ("idRecepcionCompra", "idProducto") REFERENCES "RECEPCION_COMPRA" ("idRecepcionCompra", "idProductoReventa") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_2" FOREIGN KEY ("idPedidoEntrega", "numeroDetalleEntrega") REFERENCES "DETALLE_ENTREGA" ("idPedido", "numeroDetalle") MATCH FULL ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_3" FOREIGN KEY ("idPedidoEntrega", "numeroDetalleEntrega", "idProducto") REFERENCES "DETALLE_PEDIDO" ("idPedido", "numeroDetalle", "idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_4" FOREIGN KEY ("idValoracionReventa", "idProducto") REFERENCES "VALORACION_EXTRAORDINARIA_REVENTA" ("idValoracionReventa", "idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_STOCK_REVENTA" ADD CONSTRAINT "fk_movimiento_stock_reventa_5" FOREIGN KEY ("idUsuarioResponsable") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "VALORACION_EXTRAORDINARIA_REVENTA" ADD CONSTRAINT "fk_valoracion_extraordinaria_reventa_0" FOREIGN KEY ("idProducto") REFERENCES "PRODUCTO" ("idProducto") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "VALORACION_EXTRAORDINARIA_REVENTA" ADD CONSTRAINT "fk_valoracion_extraordinaria_reventa_1" FOREIGN KEY ("idUsuarioResponsable") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "COBRO" ADD CONSTRAINT "fk_cobro_0" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "COBRO" ADD CONSTRAINT "fk_cobro_1" FOREIGN KEY ("idUsuarioRegistrador") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "COBRO" ADD CONSTRAINT "fk_cobro_2" FOREIGN KEY ("idPedido", "idCliente") REFERENCES "PEDIDO" ("idPedido", "idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_movimiento_cuenta_0" FOREIGN KEY ("idCliente") REFERENCES "CLIENTE" ("idCliente") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_movimiento_cuenta_1" FOREIGN KEY ("idUsuarioAjuste") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_origen_movimiento_cuenta_0" FOREIGN KEY ("idMovimientoCuenta") REFERENCES "MOVIMIENTO_CUENTA" ("idMovimientoCuenta") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_origen_movimiento_cuenta_1" FOREIGN KEY ("idPedidoEntrega") REFERENCES "ENTREGA" ("idPedido") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_origen_movimiento_cuenta_2" FOREIGN KEY ("idCobro") REFERENCES "COBRO" ("idCobro") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_origen_movimiento_cuenta_3" FOREIGN KEY ("idReembolso") REFERENCES "REEMBOLSO" ("idReembolso") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "fk_movimiento_caja_0" FOREIGN KEY ("idUsuarioRegistro") REFERENCES "USUARIO" ("idUsuario") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "fk_movimiento_caja_1" FOREIGN KEY ("idCobro") REFERENCES "COBRO" ("idCobro") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "fk_movimiento_caja_2" FOREIGN KEY ("idReembolso") REFERENCES "REEMBOLSO" ("idReembolso") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "REEMBOLSO" ADD CONSTRAINT "fk_reembolso_0" FOREIGN KEY ("idCobro") REFERENCES "COBRO" ("idCobro") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "REEMBOLSO" ADD CONSTRAINT "fk_reembolso_1" FOREIGN KEY ("idCobro", "importeReembolso") REFERENCES "COBRO" ("idCobro", "importe") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INTENTO_REEMBOLSO" ADD CONSTRAINT "fk_intento_reembolso_0" FOREIGN KEY ("idReembolso") REFERENCES "REEMBOLSO" ("idReembolso") MATCH SIMPLE ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "EVENTO_RESULTADO_REEMBOLSO" ADD CONSTRAINT "fk_evento_resultado_reembolso_0" FOREIGN KEY ("idReembolso", "numeroIntento") REFERENCES "INTENTO_REEMBOLSO" ("idReembolso", "numeroIntento") MATCH FULL ON UPDATE RESTRICT ON DELETE RESTRICT;

-- Restricciones por expresion / parciales (aun requiere implementar funciones de normalizacion):
CREATE UNIQUE INDEX "uq_usuario_nombre_ci" ON "USUARIO" (lower("nombreUsuario"));
CREATE UNIQUE INDEX "uq_asignacion_reparto_vigente" ON "ASIGNACION_REPARTO" ("idPedido") WHERE "fechaHoraFin" IS NULL;
CREATE UNIQUE INDEX "uq_episodio_faltante_abierto" ON "EPISODIO_FALTANTE" ("idInsumo") WHERE "fechaHoraCierre" IS NULL;
CREATE UNIQUE INDEX "uq_solicitud_primera" ON "SOLICITUD_COTIZACION" ("idEpisodio") WHERE "idSolicitudAnterior" IS NULL;
CREATE UNIQUE INDEX "uq_solicitud_automatica" ON "SOLICITUD_COTIZACION" ("idEpisodio") WHERE "modalidadInicio" = 'AUTOMATICA';
CREATE UNIQUE INDEX "uq_mov_insumo_preparacion" ON "MOVIMIENTO_INSUMO" ("idPreparacion","idInsumo") WHERE "tipoMovimiento" = 'CONSUMO_PRODUCCION';

-- EXCLUDE contra superposiciones se implementan con GiST y modelo temporal validado en fase de SQL físico.
-- Los saldos históricos, claves de idempotencia y registros sensibles exigen triggers/privilegios que NO se incluyen como si estuvieran probados.



-- MR-3: DELTA FISICO Y FUNCIONES. Los CREATE TABLE anteriores son la base histórica.

-- MR3-01 / SALDO_INICIAL (apertura física; UC-42), sin recepción inventada.
ALTER TABLE "MOVIMIENTO_INSUMO" DROP CONSTRAINT "ck_movimiento_insumo_3";
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "ck_movimiento_insumo_3" CHECK ("tipoMovimiento" IN ('INGRESO_COMPRA','CONSUMO_PRODUCCION','AJUSTE_POSITIVO','AJUSTE_NEGATIVO','SALDO_INICIAL'));
ALTER TABLE "MOVIMIENTO_INSUMO" ADD CONSTRAINT "ck_mov_insumo_saldo_inicial_origen" CHECK (
    "tipoMovimiento" <> 'SALDO_INICIAL' OR ("idPreparacion" IS NULL AND "idRecepcionCompra" IS NULL AND "origen" = 'APERTURA_INVENTARIO')
);
CREATE UNIQUE INDEX "uq_movimiento_insumo_saldo_inicial" ON "MOVIMIENTO_INSUMO" ("idInsumo") WHERE "tipoMovimiento" = 'SALDO_INICIAL';
ALTER TABLE "COSTO_INSUMO_HISTORICO" ADD CONSTRAINT "ck_costo_saldo_inicial" CHECK (
    "fuenteCosto" <> 'SALDO_INICIAL' OR "idRecepcionCompra" IS NULL
);

-- MR3-02 / Índices UNIQUE por nombres normalizados; Cliente conserva teléfonos compartidos.
CREATE UNIQUE INDEX "uq_insumo_nombre_normalizado" ON "INSUMO" (lower(regexp_replace(btrim("nombre"), '[[:space:]]+', ' ', 'g')));
CREATE UNIQUE INDEX "uq_cliente_nombre_telefono_normalizado" ON "CLIENTE" (
    lower(regexp_replace(btrim("nombres" || ' ' || coalesce("apellidos", '')), '[[:space:]]+', ' ', 'g')),
    regexp_replace("telefonoPrincipal", '[^0-9]', '', 'g')
);

-- MR3-03 / EXCLUDE GiST con vigencias [desde,hasta), sin solapamientos.
ALTER TABLE "VERSION_RECETA" ADD CONSTRAINT "ex_version_receta_no_solape" EXCLUDE USING gist (
    "idReceta" WITH =, tstzrange("vigenteDesde", COALESCE("vigenteHasta", 'infinity'::timestamptz), '[)') WITH &&
);
ALTER TABLE "PRECIO_PRODUCTO" ADD CONSTRAINT "ex_precio_producto_no_solape" EXCLUDE USING gist (
    "idProducto" WITH =, tstzrange("vigenteDesde", COALESCE("vigenteHasta", 'infinity'::timestamptz), '[)') WITH &&
) WHERE ("fechaHoraCancelacion" IS NULL);
ALTER TABLE "POLITICA_CATEGORIA_COMERCIAL" ADD CONSTRAINT "ex_politica_categoria_no_solape" EXCLUDE USING gist (
    tstzrange("vigenteDesde", COALESCE("vigenteHasta", 'infinity'::timestamptz), '[)') WITH &&
);
ALTER TABLE "POLITICA_COMPRA" ADD CONSTRAINT "ex_politica_compra_no_solape" EXCLUDE USING gist (
    tstzrange("vigenteDesde", COALESCE("vigenteHasta", 'infinity'::timestamptz), '[)') WITH &&
);

-- MR3-04 / Catálogo de cinco estados acordado, sin modificación de pedidos previos.
ALTER TABLE "PROMOCION" ADD CONSTRAINT "ck_promocion_estado_mr3" CHECK (
    "estado" IN ('BORRADOR','ACTIVA','PAUSADA','FINALIZADA','CANCELADA')
);

-- MR3-05 / Configuración global sin duración inventada; plazo aplicado congelado en episodio/solicitud.
CREATE TABLE "CONFIGURACION_OPERATIVA" (
    "clave" VARCHAR(80) NOT NULL,
    "valorEntero" INTEGER NOT NULL,
    "fechaHoraModificacion" TIMESTAMPTZ NOT NULL,
    "idUsuarioAdministrador" BIGINT NOT NULL,
    CONSTRAINT "pk_configuracion_operativa" PRIMARY KEY ("clave"),
    CONSTRAINT "ck_configuracion_operativa_plazo" CHECK (
        "clave" = 'PLAZO_RESPUESTA_COTIZACION_HORAS' AND "valorEntero" > 0
    ),
    CONSTRAINT "fk_configuracion_operativa_usuario" FOREIGN KEY ("idUsuarioAdministrador")
        REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT
);
ALTER TABLE "EPISODIO_FALTANTE" ADD COLUMN "plazoRespuestaHorasAplicado" INTEGER;
ALTER TABLE "EPISODIO_FALTANTE" ADD COLUMN "idUsuarioAjustePlazo" BIGINT;
ALTER TABLE "EPISODIO_FALTANTE" ADD CONSTRAINT "ck_episodio_plazo_aplicado" CHECK (
    "plazoRespuestaHorasAplicado" IS NULL OR "plazoRespuestaHorasAplicado" > 0
);
ALTER TABLE "EPISODIO_FALTANTE" ADD CONSTRAINT "ck_episodio_override_plazo" CHECK (
    "idUsuarioAjustePlazo" IS NULL OR "plazoRespuestaHorasAplicado" IS NOT NULL
);
ALTER TABLE "EPISODIO_FALTANTE" ADD CONSTRAINT "fk_episodio_usuario_ajuste_plazo" FOREIGN KEY ("idUsuarioAjustePlazo")
    REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT;

-- MR3-06 / Respuesta sin plazo informado válida; puntaje mínimo cero para criterio sin dato, sin pesos fijos.
ALTER TABLE "RESPUESTA_COTIZACION" DROP CONSTRAINT "ck_respuesta_cotizacion_1";
ALTER TABLE "RESPUESTA_COTIZACION" ADD CONSTRAINT "ck_respuesta_cotizacion_1" CHECK (
    ("cantidadOfrecida" = 0 AND "precioUnitario" IS NULL AND "tiempoEntregaEstimado" IS NULL)
    OR ("cantidadOfrecida" > 0 AND "precioUnitario" IS NOT NULL AND "precioUnitario" > 0
        AND ("tiempoEntregaEstimado" IS NULL OR "tiempoEntregaEstimado" > INTERVAL '0 seconds'))
);
ALTER TABLE "DETALLE_DECISION_CRITERIO" DROP CONSTRAINT "ck_detalle_decision_criterio_0";
ALTER TABLE "DETALLE_DECISION_CRITERIO" ADD CONSTRAINT "ck_detalle_decision_criterio_0" CHECK (
    NOT ("valorNumerico" IS NOT NULL AND "valorTexto" IS NOT NULL)
);
ALTER TABLE "DETALLE_DECISION_CRITERIO" ADD CONSTRAINT "ck_criterio_dato_faltante_puntaje" CHECK (
    (("valorNumerico" IS NULL AND "valorTexto" IS NULL) AND "puntajeEvaluado" = 0)
    OR (("valorNumerico" IS NOT NULL OR "valorTexto" IS NOT NULL) AND ("puntajeEvaluado" IS NULL OR "puntajeEvaluado" >= 0))
);

-- MR3-07 / Contraasiento de Cobro con FK diferenciada, movimiento negativo de Caja como EGRESO.
ALTER TABLE "MOVIMIENTO_CUENTA" DROP CONSTRAINT "ck_movimiento_cuenta_2";
ALTER TABLE "MOVIMIENTO_CUENTA" ADD CONSTRAINT "ck_movimiento_cuenta_2" CHECK (
    "tipoMovimiento" IN ('CARGO_POR_VENTA','PAGO','AJUSTE_DEBITO','AJUSTE_CREDITO','ANULACION_COBRO')
);
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD COLUMN "idCobroAnulado" BIGINT;
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" DROP CONSTRAINT "ck_origen_movimiento_cuenta_0";
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "ck_origen_movimiento_cuenta_0" CHECK (
    num_nonnulls("idPedidoEntrega","idCobro","idReembolso","idCobroAnulado") = 1
);
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "uq_origen_cuenta_cobro_anulado" UNIQUE ("idCobroAnulado");
ALTER TABLE "ORIGEN_MOVIMIENTO_CUENTA" ADD CONSTRAINT "fk_origen_cuenta_cobro_anulado" FOREIGN KEY ("idCobroAnulado")
    REFERENCES "COBRO" ("idCobro") ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CAJA" ADD COLUMN "idCobroAnulado" BIGINT;
ALTER TABLE "MOVIMIENTO_CAJA" DROP CONSTRAINT "ck_movimiento_caja_2";
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "ck_movimiento_caja_2" CHECK (
    "origen" IN ('MANUAL','COBRO','REEMBOLSO','ANULACION_COBRO')
);
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "uq_caja_cobro_anulado" UNIQUE ("idCobroAnulado");
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "fk_caja_cobro_anulado" FOREIGN KEY ("idCobroAnulado")
    REFERENCES "COBRO" ("idCobro") ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "MOVIMIENTO_CAJA" ADD CONSTRAINT "ck_caja_anulacion_cobro" CHECK (
    ("origen" = 'ANULACION_COBRO' AND "tipoMovimiento" = 'EGRESO' AND "idCobroAnulado" IS NOT NULL
        AND "idCobro" IS NULL AND "idReembolso" IS NULL AND "idUsuarioRegistro" IS NOT NULL)
    OR ("origen" <> 'ANULACION_COBRO' AND "idCobroAnulado" IS NULL)
);

-- MR3-08 / Responsable opcional para primer intento automático; obligatorio en reintentos administrativos.
ALTER TABLE "INTENTO_REEMBOLSO" ADD COLUMN "idUsuarioReintento" BIGINT;
ALTER TABLE "INTENTO_REEMBOLSO" ADD CONSTRAINT "fk_intento_reembolso_usuario_reintento" FOREIGN KEY ("idUsuarioReintento")
    REFERENCES "USUARIO" ("idUsuario") ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE "INTENTO_REEMBOLSO" ADD CONSTRAINT "ck_intento_reembolso_responsable" CHECK (
    ("numeroIntento" = 1 AND "idUsuarioReintento" IS NULL)
    OR ("numeroIntento" > 1 AND "idUsuarioReintento" IS NOT NULL)
);


-- MR3-09: Estados de promocion. El paso temporal a FINALIZADA se ejecuta por tarea autorizada;
-- un CHECK no puede forzar un cambio temporal por reloj.
CREATE OR REPLACE FUNCTION "fn_mr3_promocion_estado"() RETURNS trigger
LANGUAGE plpgsql AS $mr3$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW."estado" IS DISTINCT FROM OLD."estado" THEN
        IF (OLD."estado" = 'BORRADOR' AND NEW."estado" NOT IN ('ACTIVA','CANCELADA'))
           OR (OLD."estado" = 'ACTIVA' AND NEW."estado" NOT IN ('PAUSADA','FINALIZADA','CANCELADA'))
           OR (OLD."estado" = 'PAUSADA' AND NEW."estado" NOT IN ('ACTIVA','FINALIZADA','CANCELADA'))
           OR (OLD."estado" IN ('FINALIZADA','CANCELADA')) THEN
            RAISE EXCEPTION 'Transicion de promocion no permitida: % -> %', OLD."estado", NEW."estado";
        END IF;
    END IF;
    RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_promocion_estado" BEFORE UPDATE OF "estado" ON "PROMOCION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_promocion_estado"();

-- MR3-10: Congelar plazo de EPISODIO luego de la primera SOLICITUD. No atribuye 48 h.
CREATE OR REPLACE FUNCTION "fn_mr3_proteger_plazo_episodio"() RETURNS trigger
LANGUAGE plpgsql AS $mr3$
BEGIN
  IF (NEW."plazoRespuestaHorasAplicado", NEW."idUsuarioAjustePlazo")
     IS DISTINCT FROM (OLD."plazoRespuestaHorasAplicado", OLD."idUsuarioAjustePlazo")
     AND EXISTS (SELECT 1 FROM "SOLICITUD_COTIZACION" s WHERE s."idEpisodio" = OLD."idEpisodio") THEN
     RAISE EXCEPTION 'Plazo de episodio ya utilizado; las solicitudes emitidas son inmutables';
  END IF;
  RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_proteger_plazo_episodio" BEFORE UPDATE OF "plazoRespuestaHorasAplicado", "idUsuarioAjustePlazo" ON "EPISODIO_FALTANTE"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_proteger_plazo_episodio"();

-- MR3-11: Imposibilitar cambio de Unidad de Medida con historial de Insumo.
CREATE OR REPLACE FUNCTION "fn_mr3_proteger_unidad_insumo"() RETURNS trigger
LANGUAGE plpgsql AS $mr3$
BEGIN
   IF NEW."codigoUnidadMedida" IS DISTINCT FROM OLD."codigoUnidadMedida"
      AND (EXISTS (SELECT 1 FROM "MOVIMIENTO_INSUMO" m WHERE m."idInsumo" = OLD."idInsumo")
      OR EXISTS (SELECT 1 FROM "DETALLE_VERSION_RECETA" d WHERE d."idInsumo" = OLD."idInsumo")
      OR EXISTS (SELECT 1 FROM "RECEPCION_COMPRA" r WHERE r."idInsumoRecibido" = OLD."idInsumo")) THEN
       RAISE EXCEPTION 'No se puede cambiar unidad de insumo con historial dependiente';
   END IF;
   RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_proteger_unidad_insumo" BEFORE UPDATE OF "codigoUnidadMedida" ON "INSUMO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_proteger_unidad_insumo"();

-- MR3-12: La respuesta solo se recibe mientras Solicitud está abierta, antes del vencimiento;
-- el valor de tiempoEntregaEstimado puede faltar, sin forzar una respuesta ficticia.
CREATE OR REPLACE FUNCTION "fn_mr3_validar_respuesta_cotizacion"() RETURNS trigger
LANGUAGE plpgsql AS $mr3$
DECLARE s record;
BEGIN
    SELECT "estado", "fechaHoraVencimiento" INTO s FROM "SOLICITUD_COTIZACION"
      WHERE "idSolicitud" = NEW."idSolicitud" FOR UPDATE;
    IF NOT FOUND OR s."estado" <> 'RECEPCION_ABIERTA' OR clock_timestamp() >= s."fechaHoraVencimiento" THEN
       RAISE EXCEPTION 'Solicitud inexistente, cerrada o vencida';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM "INVITACION_COTIZACION" i
        JOIN "PROVEEDOR" p ON p."idProveedor"=i."idProveedor" AND p."estado"='ACTIVO'
        WHERE i."idSolicitud" = NEW."idSolicitud" AND i."idProveedor" = NEW."idProveedor") THEN
       RAISE EXCEPTION 'Proveedor no invitado o inactivo al responder';
    END IF;
    RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_validar_respuesta_cotizacion" BEFORE INSERT ON "RESPUESTA_COTIZACION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_respuesta_cotizacion"();

-- MR3-13: Contraasiento administrativo. Parametros de IDs provistos por la capa de aplicación
-- (base de esquema académico no define secuencias de identificadores). La función es atómica.
CREATE OR REPLACE FUNCTION "fn_mr3_anular_cobro_manual"(
    p_id_cobro BIGINT, p_id_usuario_admin BIGINT, p_id_mov_cuenta BIGINT, p_id_mov_caja BIGINT,
    p_motivo TEXT
) RETURNS void LANGUAGE plpgsql AS $mr3$
DECLARE c record; s NUMERIC(18,2); n BIGINT;
BEGIN
    IF length(btrim(coalesce(p_motivo, ''))) = 0 THEN RAISE EXCEPTION 'Motivo requerido'; END IF;
    IF NOT EXISTS (SELECT 1 FROM "USUARIO" u JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario"
        WHERE u."idUsuario"=p_id_usuario_admin AND u."estado"='HABILITADO'
        AND ur."codigoRol"='ADMINISTRADOR') THEN
        RAISE EXCEPTION 'Solo Administrador habilitado puede anular cobro';
    END IF;
    SELECT * INTO c FROM "COBRO" WHERE "idCobro"=p_id_cobro FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Cobro no encontrado'; END IF;
    IF c."origen"='ONLINE' OR c."medioPago"='MERCADOPAGO' THEN
       RAISE EXCEPTION 'La anulacion local no reemplaza reembolso electronico';
    END IF;
    IF c."estadoCobro"='ANULADO' OR EXISTS (SELECT 1 FROM "REEMBOLSO" r WHERE r."idCobro"=p_id_cobro)
       OR NOT EXISTS (SELECT 1 FROM "MOVIMIENTO_CAJA" mc
           WHERE mc."idCobro"=p_id_cobro AND mc."origen"='COBRO'
             AND mc."tipoMovimiento"='INGRESO' AND mc."importe"=c."importe") THEN
       RAISE EXCEPTION 'Cobro ya anulado, reembolsado o sin asiento original valido';
    END IF;
    PERFORM 1 FROM "CLIENTE" WHERE "idCliente"=c."idCliente" FOR UPDATE;
    SELECT mc."saldoResultante", mc."numeroSecuenciaCliente" INTO s,n FROM "MOVIMIENTO_CUENTA" mc
       WHERE mc."idCliente"=c."idCliente" ORDER BY mc."numeroSecuenciaCliente" DESC LIMIT 1;
    IF n IS NULL THEN RAISE EXCEPTION 'Sin cuenta corriente de referencia'; END IF;
    IF NOT EXISTS (SELECT 1 FROM "ORIGEN_MOVIMIENTO_CUENTA" o
         JOIN "MOVIMIENTO_CUENTA" m ON m."idMovimientoCuenta"=o."idMovimientoCuenta"
         WHERE o."idCobro"=p_id_cobro AND m."tipoMovimiento"='PAGO'
         AND m."importe"=c."importe" AND m."idCliente"=c."idCliente") THEN
       RAISE EXCEPTION 'Sin pago original conciliable';
    END IF;
    INSERT INTO "MOVIMIENTO_CUENTA" (
        "idMovimientoCuenta","idCliente","numeroSecuenciaCliente","tipoMovimiento","importe",
        "fechaHoraMovimiento","saldoResultante","idUsuarioAjuste","observacion"
    ) VALUES (p_id_mov_cuenta,c."idCliente",n+1,'ANULACION_COBRO',c."importe",
        clock_timestamp(),s+c."importe",p_id_usuario_admin,p_motivo);
    INSERT INTO "ORIGEN_MOVIMIENTO_CUENTA" ("idMovimientoCuenta","idCobroAnulado")
       VALUES(p_id_mov_cuenta,p_id_cobro);
    INSERT INTO "MOVIMIENTO_CAJA" ("idMovimientoCaja","tipoMovimiento","importe","fechaHoraMovimiento",
       "origen","concepto","observacion","idUsuarioRegistro","idCobroAnulado")
       VALUES(p_id_mov_caja,'EGRESO',c."importe",clock_timestamp(),
       'ANULACION_COBRO','Contraasiento de cobro manual',p_motivo,p_id_usuario_admin,p_id_cobro);
    UPDATE "COBRO" SET "estadoCobro"='ANULADO' WHERE "idCobro"=p_id_cobro;
END;
$mr3$;

-- MR3-14: Impedir reescribir las condiciones históricas de Cotización y Reparto por cambios
-- posteriores de catálogo: debe completarse en el servicio de sincronización y sus pruebas.
-- MR3-15: El 'Precio Total' es último desempate ASC del algoritmo evaluador,
-- después de ordenDesempate dinámico; no se codifica una fórmula de normalización inexistente.


-- MR3-16: Solo nuevos registros usan catálogo actualmente elegible.
-- Desactivar PRODUCTO/PROVEEDOR no cancela operaciones iniciadas.
CREATE OR REPLACE FUNCTION "fn_mr3_validar_pedido_nuevo"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM "CLIENTE" c WHERE c."idCliente"=NEW."idCliente" AND c."estado"='ACTIVO') THEN
  RAISE EXCEPTION 'Cliente no habilitado para pedido nuevo';
 END IF;
 RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_validar_pedido_nuevo" BEFORE INSERT ON "PEDIDO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_pedido_nuevo"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_producto_detalle_nuevo"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM "PRODUCTO" p WHERE p."idProducto"=NEW."idProducto" AND p."activo") THEN
  RAISE EXCEPTION 'Producto no habilitado para pedido nuevo';
 END IF;
 RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_validar_producto_detalle_nuevo" BEFORE INSERT ON "DETALLE_PEDIDO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_producto_detalle_nuevo"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_invitacion_nueva"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
BEGIN
 IF NOT EXISTS (
  SELECT 1 FROM "PROVEEDOR" p
  JOIN "USUARIO_PROVEEDOR" up ON up."idProveedor"=p."idProveedor"
  JOIN "USUARIO" u ON u."idUsuario"=up."idUsuario" AND u."estado"='HABILITADO'
  JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario" AND ur."codigoRol"='PROVEEDOR'
  JOIN "PROVEEDOR_INSUMO" pi ON pi."idProveedor"=p."idProveedor"
  JOIN "EPISODIO_FALTANTE" e ON e."idInsumo"=pi."idInsumo"
  JOIN "SOLICITUD_COTIZACION" s ON s."idEpisodio"=e."idEpisodio"
  WHERE p."idProveedor"=NEW."idProveedor" AND p."estado"='ACTIVO'
    AND s."idSolicitud"=NEW."idSolicitud"
    AND pi."vigenteDesde"<=s."fechaHoraEmision"
    AND (pi."vigenteHasta" IS NULL OR pi."vigenteHasta">s."fechaHoraEmision")
 ) THEN RAISE EXCEPTION 'Proveedor inactivo o sin cuenta de portal habilitada'; END IF;
 RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_validar_invitacion_nueva" BEFORE INSERT ON "INVITACION_COTIZACION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_invitacion_nueva"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_orden_nueva"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
BEGIN
 IF NOT EXISTS (
   SELECT 1 FROM "DECISION_COMPRA" d
   JOIN "RESPUESTA_COTIZACION" r ON r."idRespuesta"=d."idRespuestaSeleccionada"
   JOIN "PROVEEDOR" p ON p."idProveedor"=r."idProveedor"
   WHERE d."idDecisionCompra"=NEW."idDecisionCompra" AND d."resultado"='COMPRA'
     AND p."estado"='ACTIVO'
 ) THEN RAISE EXCEPTION 'No se puede emitir orden nueva a proveedor inactivo o sin decision de compra'; END IF;
 RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_validar_orden_nueva" BEFORE INSERT ON "ORDEN_COMPRA"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_orden_nueva"();

-- MR3-17: Congelar contenido comercial de la solicitud. Estado/cierre sí evolucionan.
CREATE OR REPLACE FUNCTION "fn_mr3_proteger_solicitud_emitida"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
BEGIN
 IF ROW(NEW."idEpisodio",NEW."cantidadSolicitada",NEW."fechaHoraEmision",NEW."fechaHoraVencimiento",NEW."modalidadInicio")
    IS DISTINCT FROM ROW(OLD."idEpisodio",OLD."cantidadSolicitada",OLD."fechaHoraEmision",OLD."fechaHoraVencimiento",OLD."modalidadInicio") THEN
   RAISE EXCEPTION 'Solicitud emitida inmutable: plazo y cantidades congelados';
 END IF;
 RETURN NEW;
END;
$mr3$;
CREATE TRIGGER "tg_mr3_proteger_solicitud_emitida" BEFORE UPDATE ON "SOLICITUD_COTIZACION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_proteger_solicitud_emitida"();

-- MR3-18: Integridad de contraasiento evaluada al COMMIT cuando se completan ambas contrapartidas.
CREATE OR REPLACE FUNCTION "fn_mr3_verificar_contraasiento"() RETURNS trigger LANGUAGE plpgsql AS $mr3$
DECLARE v_cobro BIGINT; c record; v_cnt_caja INTEGER; v_cnt_cuenta INTEGER;
BEGIN
 IF TG_TABLE_NAME = 'COBRO' THEN
   IF NEW."estadoCobro" <> 'ANULADO' THEN RETURN NEW; END IF;
   v_cobro:=NEW."idCobro";
 ELSE
   v_cobro:=NEW."idCobroAnulado";
   IF v_cobro IS NULL THEN RETURN NEW; END IF;
 END IF;
 SELECT * INTO c FROM "COBRO" WHERE "idCobro"=v_cobro;
 IF NOT FOUND OR c."estadoCobro" <> 'ANULADO' THEN
   RAISE EXCEPTION 'Contraasiento sin cobro original ANULADO';
 END IF;
 SELECT count(*) INTO v_cnt_caja FROM "MOVIMIENTO_CAJA" mc
   WHERE mc."idCobroAnulado"=v_cobro AND mc."tipoMovimiento"='EGRESO'
     AND mc."importe"=c."importe" AND mc."origen"='ANULACION_COBRO';
 SELECT count(*) INTO v_cnt_cuenta FROM "ORIGEN_MOVIMIENTO_CUENTA" o
   JOIN "MOVIMIENTO_CUENTA" m ON m."idMovimientoCuenta"=o."idMovimientoCuenta"
   WHERE o."idCobroAnulado"=v_cobro AND m."idCliente"=c."idCliente"
     AND m."tipoMovimiento"='ANULACION_COBRO' AND m."importe"=c."importe";
 IF v_cnt_caja <> 1 OR v_cnt_cuenta <> 1 THEN
   RAISE EXCEPTION 'Contraasiento incompleto de cobro %: caja %, cuenta %',v_cobro,v_cnt_caja,v_cnt_cuenta;
 END IF;
 RETURN NEW;
END;
$mr3$;
CREATE CONSTRAINT TRIGGER "ct_mr3_cobro_anulado" AFTER INSERT OR UPDATE ON "COBRO"
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION "fn_mr3_verificar_contraasiento"();
CREATE CONSTRAINT TRIGGER "ct_mr3_caja_anulacion" AFTER INSERT OR UPDATE ON "MOVIMIENTO_CAJA"
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION "fn_mr3_verificar_contraasiento"();
CREATE CONSTRAINT TRIGGER "ct_mr3_origen_anulacion" AFTER INSERT OR UPDATE ON "ORIGEN_MOVIMIENTO_CUENTA"
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION "fn_mr3_verificar_contraasiento"();

-- MR3-19: Esta capa SQL no otorga a Repartidor permiso de UC-29. Registrar Cobro
-- online/offline exige permisos explícitos, instantáneas y deduplicación por clave de origen.

-- Complemento MR-5 posterior a MR-3.
-- ALE MILANESAS | MR-5 | Complemento incremental posterior a MR-3
-- NO EJECUTADO. Aplicar SOLO después de MR-3, en PostgreSQL aislado.
-- No crea tablas, columnas ni FK. Exige preflight de datos y roles de BD adecuados.
-- CU/decisiones: Cobros y reembolsos A; PRA-01; Usuario UC-53..56;
--                   desactivaciones sin cancelar operaciones históricas.
SET LOCAL lock_timeout = '10s';

-- F01: No existe catálogo completo aprobado de COBRO.estadoCobro.
-- NO inventar CONFIRMADO, PROCESADO, etc. Se limita a impedir literal vacío;
-- la enumeración cerrada sigue pendiente y está etiquetada MR5-F01.
ALTER TABLE "COBRO" ADD CONSTRAINT "ck_mr5_cobro_estado_no_vacio"
  CHECK (length(btrim("estadoCobro")) > 0);

-- F02: Los asientos ya aplicados son inmutables. Cualquier corrección posterior
-- requiere NUEVO movimiento compensatorio; el borrado físico no es admisible.
CREATE OR REPLACE FUNCTION "fn_mr5_bloquear_mutacion_asiento"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'No se permite eliminar asientos históricos de %', TG_TABLE_NAME;
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'No se permite modificar asientos históricos de %', TG_TABLE_NAME;
  END IF;
  RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_movimiento_cuenta_inmutable"
  BEFORE UPDATE OR DELETE ON "MOVIMIENTO_CUENTA" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_bloquear_mutacion_asiento"();
CREATE TRIGGER "tg_mr5_origen_movimiento_cuenta_inmutable"
  BEFORE UPDATE OR DELETE ON "ORIGEN_MOVIMIENTO_CUENTA" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_bloquear_mutacion_asiento"();
CREATE TRIGGER "tg_mr5_movimiento_caja_inmutable"
  BEFORE UPDATE OR DELETE ON "MOVIMIENTO_CAJA" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_bloquear_mutacion_asiento"();

CREATE OR REPLACE FUNCTION "fn_mr5_guardar_cobro"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'El Cobro es evidencia financiera y no se elimina';
  END IF;
  IF ROW(NEW."idCobro", NEW."idCliente", NEW."idPedido", NEW."idUsuarioRegistrador",
         NEW."fechaHoraCobro", NEW."importe", NEW."medioPago", NEW."origen",
         NEW."referenciaExterna", NEW."claveIdempotenciaOrigen")
    IS DISTINCT FROM
     ROW(OLD."idCobro", OLD."idCliente", OLD."idPedido", OLD."idUsuarioRegistrador",
         OLD."fechaHoraCobro", OLD."importe", OLD."medioPago", OLD."origen",
         OLD."referenciaExterna", OLD."claveIdempotenciaOrigen") THEN
     RAISE EXCEPTION 'Los datos de un Cobro registrado son inmutables';
  END IF;
  IF OLD."estadoCobro" = 'ANULADO' AND NEW."estadoCobro" <> 'ANULADO' THEN
     RAISE EXCEPTION 'Cobro ANULADO es estado terminal';
  END IF;
  RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_guardar_cobro"
  BEFORE UPDATE OR DELETE ON "COBRO" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_guardar_cobro"();

-- F03: referencia de costo de apertura coherente con fuente, Insumo y valor.
-- Exige registrar COSTO_INSUMO_HISTORICO antes del movimiento SALDO_INICIAL
-- en la MISMA transacción; no simula compras, cobros ni stock previo.
CREATE OR REPLACE FUNCTION "fn_mr5_validar_apertura_insumo"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
  IF NEW."tipoMovimiento" <> 'SALDO_INICIAL' THEN RETURN NEW; END IF;
  IF NEW."numeroSecuenciaInsumo" <> 1 OR NEW."stockResultante" <> NEW."cantidad" THEN
    RAISE EXCEPTION 'SALDO_INICIAL requiere secuencia 1 y stockResultante = cantidad';
  END IF;
  IF EXISTS (SELECT 1 FROM "MOVIMIENTO_INSUMO" m
    WHERE m."idInsumo"=NEW."idInsumo" AND m."idMovimientoInsumo"<>NEW."idMovimientoInsumo") THEN
    RAISE EXCEPTION 'No se puede abrir stock inicial después de movimientos registrados';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM "COSTO_INSUMO_HISTORICO" c
    WHERE c."idInsumo" = NEW."idInsumo"
      AND c."vigenteDesde" = NEW."vigenteDesdeCosto"
      AND c."fuenteCosto" = 'SALDO_INICIAL'
      AND c."idRecepcionCompra" IS NULL
  ) THEN
    RAISE EXCEPTION 'Falta COSTO_INSUMO_HISTORICO con fuente SALDO_INICIAL para el Insumo';
  END IF;
  RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_apertura_insumo"
  BEFORE INSERT OR UPDATE ON "MOVIMIENTO_INSUMO" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_validar_apertura_insumo"();
-- Ningún movimiento físico publicado se reescribe; UC-42 crea ajustes nuevos.
CREATE TRIGGER "tg_mr5_movimiento_insumo_inmutable"
  BEFORE UPDATE OR DELETE ON "MOVIMIENTO_INSUMO" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_bloquear_mutacion_asiento"();

CREATE OR REPLACE FUNCTION "fn_mr5_proteger_costo_usado"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_id BIGINT; v_fecha TIMESTAMPTZ;
BEGIN
  v_id:=OLD."idInsumo"; v_fecha:=OLD."vigenteDesde";
  IF EXISTS (SELECT 1 FROM "MOVIMIENTO_INSUMO" m
    WHERE m."idInsumo"=v_id AND m."vigenteDesdeCosto"=v_fecha) THEN
      IF TG_OP = 'DELETE' THEN
         RAISE EXCEPTION 'Costo histórico usado: no se permite su borrado';
      END IF;
      IF ROW(NEW."idInsumo", NEW."vigenteDesde", NEW."costoUnitario", NEW."fuenteCosto",
             NEW."referenciaOrigen", NEW."idRecepcionCompra")
         IS DISTINCT FROM
         ROW(OLD."idInsumo", OLD."vigenteDesde", OLD."costoUnitario", OLD."fuenteCosto",
             OLD."referenciaOrigen", OLD."idRecepcionCompra") THEN
         RAISE EXCEPTION 'Costo histórico con movimientos registrados: no puede reescribirse';
      END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_proteger_costo_usado"
  BEFORE UPDATE OR DELETE ON "COSTO_INSUMO_HISTORICO" FOR EACH ROW
  EXECUTE FUNCTION "fn_mr5_proteger_costo_usado"();

-- F06: PROCESADO exige EXACTAMENTE UN efecto de Caja y UNO de Cuenta.
-- El evento se registra y procesa por el servicio de UC-49; estos controles
-- NO autentican por sí solos firmas MercadoPago ni llaman servicios externos.
CREATE UNIQUE INDEX "uq_mr5_resultado_procesado_aplicable"
 ON "EVENTO_RESULTADO_REEMBOLSO" ("idReembolso")
 WHERE "aplicable" IS TRUE AND "resultadoInformado" = 'PROCESADO';

CREATE OR REPLACE FUNCTION "fn_mr5_validar_reembolso_financiero"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_id BIGINT; r record; v_caja INTEGER; v_cuenta INTEGER;
BEGIN
  IF TG_TABLE_NAME='REEMBOLSO' THEN
    v_id := NEW."idReembolso";
  ELSIF TG_TABLE_NAME='MOVIMIENTO_CAJA' THEN
    v_id := NEW."idReembolso";
  ELSE
    v_id := NEW."idReembolso";
  END IF;
  IF v_id IS NULL THEN RETURN NULL; END IF;
  SELECT rr."idReembolso",rr."estado",rr."importeReembolso",c."idCliente"
    INTO r FROM "REEMBOLSO" rr JOIN "COBRO" c ON c."idCobro"=rr."idCobro"
    WHERE rr."idReembolso"=v_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Reembolso sin Cobro relacionado: %',v_id; END IF;
  SELECT count(*) INTO v_caja FROM "MOVIMIENTO_CAJA" mc
    WHERE mc."idReembolso"=v_id AND mc."origen"='REEMBOLSO'
       AND mc."tipoMovimiento"='EGRESO' AND mc."importe"=r."importeReembolso";
  SELECT count(*) INTO v_cuenta FROM "ORIGEN_MOVIMIENTO_CUENTA" o
    JOIN "MOVIMIENTO_CUENTA" m ON m."idMovimientoCuenta"=o."idMovimientoCuenta"
    WHERE o."idReembolso"=v_id AND m."idCliente"=r."idCliente"
       AND m."tipoMovimiento"='AJUSTE_DEBITO' AND m."importe"=r."importeReembolso";
  IF r."estado"='PROCESADO' THEN
    IF v_caja<>1 OR v_cuenta<>1 THEN
      RAISE EXCEPTION 'Reembolso PROCESADO % exige 1 EGRESO y 1 AJUSTE_DEBITO: caja %, cuenta %',v_id,v_caja,v_cuenta;
    END IF;
  ELSIF EXISTS (SELECT 1 FROM "MOVIMIENTO_CAJA" WHERE "idReembolso"=v_id)
     OR EXISTS (SELECT 1 FROM "ORIGEN_MOVIMIENTO_CUENTA" WHERE "idReembolso"=v_id) THEN
    RAISE EXCEPTION 'Reembolso no procesado no admite efectos financieros: %',v_id;
  END IF;
  RETURN NULL;
END;
$mr5$;
CREATE CONSTRAINT TRIGGER "ct_mr5_reembolso_completo"
  AFTER INSERT OR UPDATE ON "REEMBOLSO" DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION "fn_mr5_validar_reembolso_financiero"();
CREATE CONSTRAINT TRIGGER "ct_mr5_reembolso_caja"
  AFTER INSERT ON "MOVIMIENTO_CAJA" DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION "fn_mr5_validar_reembolso_financiero"();
CREATE CONSTRAINT TRIGGER "ct_mr5_reembolso_cuenta"
  AFTER INSERT ON "ORIGEN_MOVIMIENTO_CUENTA" DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION "fn_mr5_validar_reembolso_financiero"();

CREATE OR REPLACE FUNCTION "fn_mr5_reembolso_estado_terminal"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 IF OLD."estado"='PROCESADO' AND NEW."estado" <> OLD."estado" THEN
   RAISE EXCEPTION 'Reembolso PROCESADO es terminal';
 END IF;
 IF ROW(NEW."idCobro",NEW."importeReembolso") IS DISTINCT FROM
    ROW(OLD."idCobro",OLD."importeReembolso") THEN
   RAISE EXCEPTION 'Cobro e importe de Reembolso son históricos e inmutables';
 END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_reembolso_terminal" BEFORE UPDATE ON "REEMBOLSO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr5_reembolso_estado_terminal"();

-- F07: invariantes diferidas a fin de transacción, permitiendo UC-17 alta
-- atomica de Usuario + Roles + vinculo Proveedor.
CREATE OR REPLACE FUNCTION "fn_mr5_validar_invariantes_usuarios"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 PERFORM pg_advisory_xact_lock(60505,7);
 IF EXISTS (SELECT 1 FROM "USUARIO" u WHERE NOT EXISTS
    (SELECT 1 FROM "USUARIO_ROL" ur WHERE ur."idUsuario"=u."idUsuario")) THEN
   RAISE EXCEPTION 'Todo Usuario debe conservar al menos un Rol al confirmar';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM "USUARIO" u
    JOIN "USUARIO_ROL" ur ON ur."idUsuario"=u."idUsuario"
    WHERE u."estado"='HABILITADO' AND ur."codigoRol"='ADMINISTRADOR') THEN
   RAISE EXCEPTION 'Debe conservarse un Administrador habilitado';
 END IF;
 IF EXISTS (SELECT 1 FROM "USUARIO_ROL" r WHERE r."codigoRol"='PROVEEDOR'
    AND EXISTS (SELECT 1 FROM "USUARIO_ROL" r2
       WHERE r2."idUsuario"=r."idUsuario" AND r2."codigoRol"<>'PROVEEDOR')) THEN
    RAISE EXCEPTION 'PROVEEDOR es Rol exclusivo';
 END IF;
 IF EXISTS (SELECT 1 FROM "USUARIO_ROL" r WHERE r."codigoRol"='PROVEEDOR'
    AND NOT EXISTS (SELECT 1 FROM "USUARIO_PROVEEDOR" up
       WHERE up."idUsuario"=r."idUsuario")) THEN
    RAISE EXCEPTION 'Cuenta PROVEEDOR exige vínculo a Proveedor';
 END IF;
 IF EXISTS (
    SELECT 1 FROM "USUARIO_PROVEEDOR" up JOIN "USUARIO" u
      ON u."idUsuario"=up."idUsuario" AND u."estado"='HABILITADO'
    GROUP BY up."idProveedor" HAVING count(*)>1
 ) THEN
    RAISE EXCEPTION 'Solo una cuenta habilitada por Proveedor';
 END IF;
 IF EXISTS (SELECT 1 FROM "USUARIO_PROVEEDOR" up WHERE NOT EXISTS
    (SELECT 1 FROM "USUARIO_ROL" ur WHERE ur."idUsuario"=up."idUsuario"
      AND ur."codigoRol"='PROVEEDOR')) THEN
    RAISE EXCEPTION 'Vínculo USUARIO_PROVEEDOR exige Rol PROVEEDOR';
 END IF;
 RETURN NULL;
END;
$mr5$;
CREATE CONSTRAINT TRIGGER "ct_mr5_usuario_roles" AFTER INSERT OR UPDATE OR DELETE ON "USUARIO"
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION "fn_mr5_validar_invariantes_usuarios"();
CREATE CONSTRAINT TRIGGER "ct_mr5_asignacion_roles" AFTER INSERT OR UPDATE OR DELETE ON "USUARIO_ROL"
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION "fn_mr5_validar_invariantes_usuarios"();
CREATE CONSTRAINT TRIGGER "ct_mr5_usuario_proveedor" AFTER INSERT OR UPDATE OR DELETE ON "USUARIO_PROVEEDOR"
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION "fn_mr5_validar_invariantes_usuarios"();

CREATE OR REPLACE FUNCTION "fn_mr5_revocar_sesiones_usuario"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 IF NEW."estado"='DESHABILITADO' AND OLD."estado"<>'DESHABILITADO'
   OR NEW."credencialProtegida" IS DISTINCT FROM OLD."credencialProtegida" THEN
   UPDATE "SESION_USUARIO" su SET "fechaHoraRevocacion"=clock_timestamp()
    WHERE su."idUsuario"=NEW."idUsuario" AND su."fechaHoraRevocacion" IS NULL;
 END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_revocar_sesiones_usuario" AFTER UPDATE OF "estado","credencialProtegida" ON "USUARIO"
 FOR EACH ROW EXECUTE FUNCTION "fn_mr5_revocar_sesiones_usuario"();
CREATE OR REPLACE FUNCTION "fn_mr5_revocar_sesiones_roles"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_id BIGINT;
BEGIN
 v_id:=CASE WHEN TG_OP='DELETE' THEN OLD."idUsuario" ELSE NEW."idUsuario" END;
 UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
   WHERE "idUsuario"=v_id AND "fechaHoraRevocacion" IS NULL;
 IF TG_OP='UPDATE' AND OLD."idUsuario" IS DISTINCT FROM NEW."idUsuario" THEN
   UPDATE "SESION_USUARIO" SET "fechaHoraRevocacion"=clock_timestamp()
     WHERE "idUsuario"=OLD."idUsuario" AND "fechaHoraRevocacion" IS NULL;
 END IF;
 RETURN NULL;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_revocar_sesiones_roles" AFTER INSERT OR UPDATE OR DELETE ON "USUARIO_ROL"
 FOR EACH ROW EXECUTE FUNCTION "fn_mr5_revocar_sesiones_roles"();
CREATE TRIGGER "tg_mr5_revocar_sesiones_proveedor" AFTER INSERT OR UPDATE OR DELETE ON "USUARIO_PROVEEDOR"
 FOR EACH ROW EXECUTE FUNCTION "fn_mr5_revocar_sesiones_roles"();

-- F08: PAUSADA -> ACTIVA está físicamente prohibida hasta autorizar un CU
-- de reactivación; PAUSADA -> FINALIZADA/CANCELADA sigue permitido.
CREATE OR REPLACE FUNCTION "fn_mr3_promocion_estado"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 IF NEW."estado" IS DISTINCT FROM OLD."estado" THEN
   IF (OLD."estado"='BORRADOR' AND NEW."estado" NOT IN ('ACTIVA','CANCELADA'))
     OR (OLD."estado"='ACTIVA' AND NEW."estado" NOT IN ('PAUSADA','FINALIZADA','CANCELADA'))
     OR (OLD."estado"='PAUSADA' AND NEW."estado" NOT IN ('FINALIZADA','CANCELADA'))
     OR OLD."estado" IN ('FINALIZADA','CANCELADA') THEN
     RAISE EXCEPTION 'Transición de Promoción no autorizada: % -> %',OLD."estado",NEW."estado";
   END IF;
 END IF;
 RETURN NEW;
END;
$mr5$;

-- F09: Bloqueos compatibles con UPDATE para cerrar carrera con desactivación.
-- Se valida una operación NUEVA o el CAMBIO de entidad vinculada; operaciones
-- históricas intactas siguen sus flujos y NO son canceladas.
CREATE OR REPLACE FUNCTION "fn_mr3_validar_pedido_nuevo"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 IF TG_OP='UPDATE' AND NEW."idCliente" IS NOT DISTINCT FROM OLD."idCliente" THEN RETURN NEW; END IF;
 PERFORM 1 FROM "CLIENTE" c WHERE c."idCliente"=NEW."idCliente" AND c."estado"='ACTIVO' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Cliente inactivo o inexistente para operación nueva'; END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_pedido_cambio_cliente" BEFORE UPDATE OF "idCliente" ON "PEDIDO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_pedido_nuevo"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_producto_detalle_nuevo"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
 IF TG_OP='UPDATE' AND NEW."idProducto" IS NOT DISTINCT FROM OLD."idProducto" THEN RETURN NEW; END IF;
 PERFORM 1 FROM "PRODUCTO" p WHERE p."idProducto"=NEW."idProducto" AND p."activo" FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Producto inactivo o inexistente para selección nueva'; END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_detalle_cambio_producto" BEFORE UPDATE OF "idProducto" ON "DETALLE_PEDIDO"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_producto_detalle_nuevo"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_invitacion_nueva"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_usr BIGINT; v_ins BIGINT; v_fecha TIMESTAMPTZ;
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW."idSolicitud",NEW."idProveedor")
   IS NOT DISTINCT FROM ROW(OLD."idSolicitud",OLD."idProveedor") THEN RETURN NEW; END IF;
 SELECT up."idUsuario" INTO v_usr FROM "USUARIO_PROVEEDOR" up
   JOIN "USUARIO" uv ON uv."idUsuario"=up."idUsuario" AND uv."estado"='HABILITADO'
   JOIN "USUARIO_ROL" rv ON rv."idUsuario"=up."idUsuario" AND rv."codigoRol"='PROVEEDOR'
   WHERE up."idProveedor"=NEW."idProveedor" LIMIT 1;
 PERFORM 1 FROM "PROVEEDOR" p WHERE p."idProveedor"=NEW."idProveedor" AND p."estado"='ACTIVO' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Proveedor inactivo para invitación nueva'; END IF;
 PERFORM 1 FROM "USUARIO" u WHERE u."idUsuario"=v_usr AND u."estado"='HABILITADO' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Proveedor sin cuenta de portal habilitada'; END IF;
 IF NOT EXISTS (SELECT 1 FROM "USUARIO_ROL" ur WHERE ur."idUsuario"=v_usr AND ur."codigoRol"='PROVEEDOR') THEN
   RAISE EXCEPTION 'Cuenta sin Rol PROVEEDOR';
 END IF;
 SELECT e."idInsumo",s."fechaHoraEmision" INTO v_ins,v_fecha FROM "SOLICITUD_COTIZACION" s
    JOIN "EPISODIO_FALTANTE" e ON e."idEpisodio"=s."idEpisodio"
    WHERE s."idSolicitud"=NEW."idSolicitud" AND e."fechaHoraCierre" IS NULL;
 IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM "PROVEEDOR_INSUMO" pi
    WHERE pi."idProveedor"=NEW."idProveedor" AND pi."idInsumo"=v_ins
    AND pi."vigenteDesde"<=v_fecha
    AND (pi."vigenteHasta" IS NULL OR pi."vigenteHasta">v_fecha)) THEN
    RAISE EXCEPTION 'Proveedor no elegible para episodio abierto';
 END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_invitacion_cambio" BEFORE UPDATE OF "idSolicitud","idProveedor" ON "INVITACION_COTIZACION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_invitacion_nueva"();

CREATE OR REPLACE FUNCTION "fn_mr3_validar_orden_nueva"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_proveedor BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND NEW."idDecisionCompra" IS NOT DISTINCT FROM OLD."idDecisionCompra" THEN RETURN NEW; END IF;
 SELECT r."idProveedor" INTO v_proveedor FROM "DECISION_COMPRA" d
 JOIN "RESPUESTA_COTIZACION" r ON r."idRespuesta"=d."idRespuestaSeleccionada"
 WHERE d."idDecisionCompra"=NEW."idDecisionCompra" AND d."resultado"='COMPRA';
 IF NOT FOUND THEN RAISE EXCEPTION 'Orden nueva sin decisión COMPRA válida'; END IF;
 PERFORM 1 FROM "PROVEEDOR" p WHERE p."idProveedor"=v_proveedor AND p."estado"='ACTIVO' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Proveedor inactivo para nueva Orden de Compra'; END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_orden_cambio_decision" BEFORE UPDATE OF "idDecisionCompra" ON "ORDEN_COMPRA"
 FOR EACH ROW EXECUTE FUNCTION "fn_mr3_validar_orden_nueva"();

-- F05: Controles físicos auxiliares de PRA-01, NO equivalen a su servicio.
CREATE OR REPLACE FUNCTION "fn_mr5_validar_primera_ronda"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_plazo INTEGER; v_cierre TIMESTAMPTZ; v_ins BIGINT; v_automatico BOOLEAN; v_stock NUMERIC; v_umbral NUMERIC;
BEGIN
 SELECT e."plazoRespuestaHorasAplicado",e."fechaHoraCierre",e."idInsumo",
        i."automatizacionCompraActiva",i."stockActual",i."umbralMinimo"
 INTO v_plazo,v_cierre,v_ins,v_automatico,v_stock,v_umbral
 FROM "EPISODIO_FALTANTE" e JOIN "INSUMO" i ON i."idInsumo"=e."idInsumo"
 WHERE e."idEpisodio"=NEW."idEpisodio" FOR UPDATE OF e;
 IF NOT FOUND OR v_cierre IS NOT NULL THEN
   RAISE EXCEPTION 'No se crean solicitudes en un episodio cerrado o inexistente';
 END IF;
 IF NEW."modalidadInicio"='AUTOMATICA' THEN
   IF NEW."idSolicitudAnterior" IS NOT NULL OR NOT v_automatico OR v_stock>=v_umbral
     OR v_plazo IS NULL OR NEW."fechaHoraVencimiento" <> NEW."fechaHoraEmision" + make_interval(hours=>v_plazo) THEN
     RAISE EXCEPTION 'Primera ronda automática no cumple condiciones del episodio/plazo congelado';
   END IF;
 END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_primera_ronda" BEFORE INSERT ON "SOLICITUD_COTIZACION"
FOR EACH ROW EXECUTE FUNCTION "fn_mr5_validar_primera_ronda"();

CREATE OR REPLACE FUNCTION "fn_mr5_validar_cierre_episodio"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
DECLARE v_stock NUMERIC(18,3); v_umbral NUMERIC(18,3);
BEGIN
 IF OLD."fechaHoraCierre" IS NOT NULL AND NEW."fechaHoraCierre" IS DISTINCT FROM OLD."fechaHoraCierre" THEN
   RAISE EXCEPTION 'Episodio cerrado no puede reabrirse';
 END IF;
 IF OLD."fechaHoraCierre" IS NULL AND NEW."fechaHoraCierre" IS NOT NULL THEN
   SELECT i."stockActual",i."umbralMinimo" INTO v_stock,v_umbral
     FROM "INSUMO" i WHERE i."idInsumo"=NEW."idInsumo" FOR SHARE;
   IF v_stock < v_umbral OR v_stock IS NULL THEN
     RAISE EXCEPTION 'No cerrar episodio: stock aún bajo umbral';
   END IF;
   IF EXISTS (SELECT 1 FROM "SOLICITUD_COTIZACION" s
       WHERE s."idEpisodio"=NEW."idEpisodio" AND s."estado" NOT IN ('RESUELTA_CON_COMPRA','RESUELTA_SIN_COMPRA')) THEN
     RAISE EXCEPTION 'No cerrar episodio con solicitudes sin resolver';
   END IF;
   IF EXISTS (SELECT 1 FROM "ORDEN_COMPRA" oc
       JOIN "DECISION_COMPRA" d ON d."idDecisionCompra"=oc."idDecisionCompra"
       JOIN "SOLICITUD_COTIZACION" s ON s."idSolicitud"=d."idSolicitud"
       WHERE s."idEpisodio"=NEW."idEpisodio"
         AND oc."estado" IN ('PENDIENTE','PARCIALMENTE_RECIBIDA')) THEN
     RAISE EXCEPTION 'No cerrar episodio con Orden de Compra pendiente';
   END IF;
 END IF;
 RETURN NEW;
END;
$mr5$;
CREATE TRIGGER "tg_mr5_validar_cierre_episodio" BEFORE UPDATE OF "fechaHoraCierre" ON "EPISODIO_FALTANTE"
FOR EACH ROW EXECUTE FUNCTION "fn_mr5_validar_cierre_episodio"();


-- F06: Si un evento externo PROCESADO se declara aplicable, el Reembolso
-- correspondiente debe quedar PROCESADO antes de confirmar la transacción.
CREATE OR REPLACE FUNCTION "fn_mr5_validar_evento_aplicable"() RETURNS trigger
LANGUAGE plpgsql AS $mr5$
BEGIN
  IF NEW."aplicable" AND NEW."resultadoInformado"='PROCESADO'
     AND NOT EXISTS (SELECT 1 FROM "REEMBOLSO" r
       WHERE r."idReembolso"=NEW."idReembolso" AND r."estado"='PROCESADO') THEN
    RAISE EXCEPTION 'Evento PROCESADO aplicable sin Reembolso PROCESADO';
  END IF;
  RETURN NULL;
END;
$mr5$;
CREATE CONSTRAINT TRIGGER "ct_mr5_evento_aplicable"
 AFTER INSERT OR UPDATE ON "EVENTO_RESULTADO_REEMBOLSO"
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION "fn_mr5_validar_evento_aplicable"();

