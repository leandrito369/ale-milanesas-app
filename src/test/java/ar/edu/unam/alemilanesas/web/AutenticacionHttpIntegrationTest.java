package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.acceso.TokenSesionInterna;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * B4-02B: integracion MockMvc + Spring Security + PostgreSQL real MR5/F01.
 * NO equivale a servidor HTTP externo/E2E de B4-02C.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class AutenticacionHttpIntegrationTest {
    @Container static PostgreSQLContainer db=new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void dbprops(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);
        r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;

    private static final long ADMIN=79001, PROVEEDOR=79002;
    private String accesoAdmin;

    @BeforeEach void datosFicticios() {
        jdbc.update("""
                INSERT INTO "ROL"("codigoRol","nombreMostrado")
                VALUES('ADMINISTRADOR','Administrador B4'),
                      ('PROVEEDOR','Proveedor B4')
                """);
        jdbc.update("""
                INSERT INTO "PROVEEDOR"("idProveedor","cuit","razonSocial","estado","fechaHoraAlta")
                VALUES(79010,'30790000019','Proveedor de prueba','ACTIVO',clock_timestamp())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO"("idUsuario","nombreUsuario","credencialProtegida",
                       "estado","cambioCredencialPendiente","fechaHoraAlta")
                VALUES(79001,'admin_b4',?,'HABILITADO',false,clock_timestamp()),
                      (79002,'proveedor_b4',?,'HABILITADO',true,clock_timestamp())
                """,encoder.encode("ClaveAdminB4_2026"),encoder.encode("TemporalProvB4_2026"));
        jdbc.update("""
                INSERT INTO "USUARIO_ROL"("idUsuario","codigoRol","fechaHoraAsignacion")
                VALUES(79001,'ADMINISTRADOR',clock_timestamp()),
                      (79002,'PROVEEDOR',clock_timestamp())
                """);
        jdbc.update("""
                INSERT INTO "USUARIO_PROVEEDOR"("idUsuario","idProveedor","fechaHoraVinculacion")
                VALUES(79002,79010,clock_timestamp())
                """);
        accesoAdmin=TokenSesionInterna.nuevo();
        jdbc.update("""
                INSERT INTO "SESION_USUARIO"("idSesionUsuario","idUsuario","tokenProtegido",
                        "fechaHoraInicio","fechaHoraExpiracion")
                VALUES(?,79001,?,clock_timestamp()-interval '1 minute',
                       clock_timestamp()+interval '29 minutes')
                """,UUID.randomUUID(),TokenSesionInterna.huella(accesoAdmin).orElseThrow());
    }

    private String iniciar(String usuario,String clave) throws Exception {
        String respuesta=mvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(new AuthInternaController.Login(usuario,clave))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(respuesta).get("accessToken").asText();
    }

    private String bearer(String token){return "Bearer "+token;}

    @Test void loginCreaSecretoSoloHuellaYPermiteConsulta() throws Exception {
        String token=iniciar("admin_b4","ClaveAdminB4_2026");
        assertEquals(43,token.length());
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "SESION_USUARIO"
                 WHERE "tokenProtegido"=? AND "idUsuario"=79001
                   AND "fechaHoraRevocacion" IS NULL
                """,Integer.class,TokenSesionInterna.huella(token).orElseThrow()));
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(token)))
                .andExpect(status().isOk());
    }

    @Test void loginInvalidoNoEmiteSesion() throws Exception {
        int antes=jdbc.queryForObject("SELECT count(*) FROM \"SESION_USUARIO\"",Integer.class);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"nombreUsuario\":\"admin_b4\",\"credencial\":\"incorrecta\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("AUTENTICACION_RECHAZADA"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"nombreUsuario\":\"desconocido_b4\",\"credencial\":\"incorrecta\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(antes,jdbc.queryForObject("SELECT count(*) FROM \"SESION_USUARIO\"",Integer.class));
    }

    @Test void renovarRotaYRechazaAnteriorYLogoutRevoca() throws Exception {
        String anterior=iniciar("admin_b4","ClaveAdminB4_2026");
        String respuesta=mvc.perform(post("/api/v1/auth/renew")
                .header("Authorization",bearer(anterior)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var nuevo=json.readTree(respuesta).get("accessToken").asText();
        assertNotEquals(anterior,nuevo);
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(anterior)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(nuevo)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization",bearer(nuevo)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(nuevo)))
                .andExpect(status().isUnauthorized());
    }

    @Test void sesionPendienteSoloPermiteUC53YExigeNuevoLogin() throws Exception {
        String token=iniciar("proveedor_b4","TemporalProvB4_2026");
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/renew").header("Authorization",bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/password/change")
                .header("Authorization",bearer(token)).contentType("application/json")
                .content("{\"nuevaCredencial\":\"ClaveDefinitivaB4_2026\",\"confirmacionNuevaCredencial\":\"ClaveDefinitivaB4_2026\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/password/change")
                .header("Authorization",bearer(token)).contentType("application/json")
                .content("{}")).andExpect(status().isUnauthorized());
        String loginNuevo=mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"nombreUsuario\":\"proveedor_b4\",\"credencial\":\"ClaveDefinitivaB4_2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(loginNuevo).get("requiereCambioContrasena").asBoolean());
    }

    @Test void UC54SoloAdminYProveedorConVerificacion() throws Exception {
        mvc.perform(post("/api/v1/usuarios/79002/restablecer-acceso")
                .header("Authorization",bearer(accesoAdmin)).contentType("application/json")
                .content("{\"identidadComercialVerificada\":false,\"credencialTemporal\":\"ResetA_2026\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/usuarios/79001/restablecer-acceso")
                .header("Authorization",bearer(accesoAdmin)).contentType("application/json")
                .content("{\"identidadComercialVerificada\":true,\"credencialTemporal\":\"ResetA_2026\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/usuarios/79002/restablecer-acceso")
                .header("Authorization",bearer(accesoAdmin)).contentType("application/json")
                .content("{\"identidadComercialVerificada\":true,\"credencialTemporal\":\"ResetA_2026\"}"))
                .andExpect(status().isNoContent());
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM "USUARIO" WHERE "idUsuario"=79002
                  AND "cambioCredencialPendiente"=true
                """,Integer.class));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"nombreUsuario\":\"proveedor_b4\",\"credencial\":\"TemporalProvB4_2026\"}"))
                .andExpect(status().isUnauthorized());
        iniciar("proveedor_b4","ResetA_2026");
    }

    @Test void capDeOchoHorasYEscriturasDeNegocioRechazadas() throws Exception {
        String token=iniciar("admin_b4","ClaveAdminB4_2026");
        jdbc.update("""
                UPDATE "SESION_USUARIO"
                   SET "fechaHoraInicio"=clock_timestamp()-interval '8 hours 1 minute',
                       "fechaHoraExpiracion"=clock_timestamp()+interval '10 minutes'
                 WHERE "tokenProtegido"=?
                """,TokenSesionInterna.huella(token).orElseThrow());
        mvc.perform(get("/api/v1/unidades").header("Authorization",bearer(token)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/clientes").header("Authorization",bearer(accesoAdmin)))
                .andExpect(status().isForbidden());
    }
}
