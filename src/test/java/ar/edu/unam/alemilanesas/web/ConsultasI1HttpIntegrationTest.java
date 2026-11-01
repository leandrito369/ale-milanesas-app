package ar.edu.unam.alemilanesas.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * B4-01: HTTP real vía MockMvc + Spring Security + BD PostgreSQL MR5/F01.
 * Token secreto 256-bit solo en el cliente, SHA-256 almacenado en BYTEA;
 * cada solicitud verifica revocación, estado, clave pendiente y roles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ConsultasI1HttpIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static final UUID SESION_ADMIN=UUID.fromString("44010100-0000-4000-8000-000000000001");
    static final UUID SESION_SELLER=UUID.fromString("44010100-0000-4000-8000-000000000002");
    static final UUID SESION_PROD=UUID.fromString("44010100-0000-4000-8000-000000000003");
    static final UUID SESION_REPART=UUID.fromString("44010100-0000-4000-8000-000000000004");

    static String token(int semilla) {
        byte[] valor=new byte[32];
        for(int i=0;i<valor.length;i++)valor[i]=(byte)(i*7+semilla);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valor);
    }
    static byte[] huella(String token) {
        try {return MessageDigest.getInstance("SHA-256").digest(
                Base64.getUrlDecoder().decode(token));}
        catch(Exception error){throw new AssertionError(error);}
    }
    private String admin(){return "Bearer "+token(11);}
    private String seller(){return "Bearer "+token(12);}
    private String prod(){return "Bearer "+token(13);}
    private String repart(){return "Bearer "+token(14);}

    @BeforeEach void datos(){
        jdbc.update("""
            INSERT INTO "UNIDAD_MEDIDA" ("codigoUnidad","nombre")
            VALUES ('KG','Kilogramo'),('UN','Unidad')
            """);
        jdbc.update("""
            INSERT INTO "INSUMO" ("idInsumo","nombre","codigoUnidadMedida",
                "stockActual","umbralMinimo","nivelObjetivoReposicion",
                "automatizacionCompraActiva","activo")
            VALUES (44011,'Carne','KG',0,2,5,false,true),
                   (44012,'Papas','KG',0,1,3,false,false)
            """);
        jdbc.update("""
            INSERT INTO "CLIENTE" ("idCliente","nombres","telefonoPrincipal",
                "calle","localidad","estado","categoriaComercial","fechaHoraAlta")
            VALUES (44021,'Cliente HTTP','3764400001','Calle uno','Posadas',
                    'ACTIVO','CUMPLIDOR',clock_timestamp())
            """);
        jdbc.update("""
            INSERT INTO "ROL" ("codigoRol","nombreMostrado")
            VALUES ('ADMINISTRADOR','Admin HTTP'),('VENDEDOR','Vendedor HTTP'),
                   ('RESPONSABLE_PRODUCCION','Produccion HTTP'),('REPARTIDOR','Repartidor HTTP')
            """);
        jdbc.update("""
            INSERT INTO "USUARIO" ("idUsuario","nombreUsuario","credencialProtegida",
                "estado","cambioCredencialPendiente","fechaHoraAlta")
            VALUES (44031,'admin_http','HASH_TEST','HABILITADO',false,now()),
                   (44032,'vendedor_http','HASH_TEST','HABILITADO',false,now()),
                   (44033,'produccion_http','HASH_TEST','HABILITADO',false,now()),
                   (44034,'repartidor_http','HASH_TEST','HABILITADO',false,now())
            """);
        jdbc.update("""
            INSERT INTO "USUARIO_ROL" ("idUsuario","codigoRol","fechaHoraAsignacion")
            VALUES (44031,'ADMINISTRADOR',now()),(44032,'VENDEDOR',now()),
                   (44033,'RESPONSABLE_PRODUCCION',now()),(44034,'REPARTIDOR',now())
            """);
        sesion(SESION_ADMIN,44031,11);
        sesion(SESION_SELLER,44032,12);
        sesion(SESION_PROD,44033,13);
        sesion(SESION_REPART,44034,14);
    }
    private void sesion(UUID id,long user,int seed){
        jdbc.update("""
            INSERT INTO "SESION_USUARIO" ("idSesionUsuario","idUsuario","tokenProtegido",
                "fechaHoraInicio","fechaHoraExpiracion")
            VALUES (?,?,?,clock_timestamp()-interval '1 minute',
                      clock_timestamp()+interval '1 hour')
            """,id,user,huella(token(seed)));
    }

    @Test void getClientesSoloAdminYVendedorYNoExponeSaldo() throws Exception{
        mvc.perform(get("/api/v1/clientes").header("Authorization",admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$[0].idCliente").value(44021))
                .andExpect(jsonPath("$[0].telefonoPrincipal").value("3764400001"))
                .andExpect(jsonPath("$[0].saldo").doesNotExist());
        mvc.perform(get("/api/v1/clientes").header("Authorization",seller())
                .param("nombre","Cliente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Cliente HTTP"));
        mvc.perform(get("/api/v1/clientes").header("Authorization",prod()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/clientes").header("Authorization",repart()))
                .andExpect(status().isForbidden());
    }

    @Test void getInsumosYUnidadesSoloAdminOProduccion() throws Exception {
        mvc.perform(get("/api/v1/insumos").header("Authorization",prod()).param("activo","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Carne"))
                .andExpect(jsonPath("$[0].stockActual").value(0));
        mvc.perform(get("/api/v1/unidades").header("Authorization",admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/unidades").header("Authorization",seller()))
                .andExpect(status().isForbidden());
    }

    @Test void ningunaConsultaAceptaSesionFalsaNiTokenDeSoloUuid() throws Exception {
        mvc.perform(get("/api/v1/clientes").param("idSesionUsuario",SESION_ADMIN.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get("/api/v1/clientes").header("Authorization","Bearer "+SESION_ADMIN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/clientes").header("Authorization","Bearer "+token(90)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/clientes").header("Authorization","Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/clientes").header("Authorization",admin()+" corrupto"))
                .andExpect(status().isUnauthorized());
    }

    @Test void tokenRevocadoNoAccede() throws Exception {
        jdbc.update("UPDATE \"SESION_USUARIO\" SET \"fechaHoraRevocacion\"=clock_timestamp() WHERE \"idSesionUsuario\"=?",SESION_SELLER);
        mvc.perform(get("/api/v1/clientes").header("Authorization",seller()))
                .andExpect(status().isUnauthorized());
    }

    @Test void tokenVencidoNoAccede() throws Exception {
        jdbc.update("""
            UPDATE "SESION_USUARIO" SET "fechaHoraExpiracion"=clock_timestamp()-interval '1 second'
            WHERE "idSesionUsuario"=?
            """,SESION_ADMIN);
        mvc.perform(get("/api/v1/clientes").header("Authorization",admin()))
                .andExpect(status().isUnauthorized());
    }

    @Test void usuarioDeshabilitadoNoAccede() throws Exception {
        jdbc.update("UPDATE \"USUARIO\" SET \"estado\"='DESHABILITADO' WHERE \"idUsuario\"=44031");
        mvc.perform(get("/api/v1/insumos").header("Authorization",admin()))
                .andExpect(status().isUnauthorized());
    }

    @Test void cambioCredencialPendienteNoAccede() throws Exception {
        jdbc.update("UPDATE \"USUARIO\" SET \"cambioCredencialPendiente\"=true WHERE \"idUsuario\"=44031");
        mvc.perform(get("/api/v1/unidades").header("Authorization",admin()))
                .andExpect(status().isUnauthorized());
    }

    @Test void rolRevocadoSeEvaluaEnSolicitudSiguiente() throws Exception {
        mvc.perform(get("/api/v1/insumos").header("Authorization",prod()))
                .andExpect(status().isOk());
        jdbc.update("""
            DELETE FROM "USUARIO_ROL" WHERE "idUsuario"=44033
               AND "codigoRol"='RESPONSABLE_PRODUCCION'
            """);
        mvc.perform(get("/api/v1/insumos").header("Authorization",prod()))
                .andExpect(status().isUnauthorized());
    }

    @Test void requestInvalidaEs400YNoDivulgaSql() throws Exception {
        mvc.perform(get("/api/v1/clientes").header("Authorization",admin()).param("estado","NO_EXISTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        mvc.perform(get("/api/v1/insumos").header("Authorization",prod()).param("idInsumo","-4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM \"INSUMO\"",Integer.class));
    }

    @Test void escrituraHttpBloqueadaAunqueElTokenSeaValido() throws Exception {
        mvc.perform(post("/api/v1/clientes").header("Authorization",admin()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/insumos/44011").header("Authorization",admin()))
                .andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM \"MOVIMIENTO_INSUMO\"",Integer.class));
    }

    @Test void healthSinAutenticacionYNoExponerApisFueraDeLaLista() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/precios").header("Authorization",admin()))
                .andExpect(status().isForbidden());
    }
}
