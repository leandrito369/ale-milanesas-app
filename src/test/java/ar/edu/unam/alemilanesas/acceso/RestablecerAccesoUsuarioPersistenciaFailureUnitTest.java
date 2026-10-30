package ar.edu.unam.alemilanesas.acceso;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UC-54 A3: unitario de propagación segura de fallo en UPDATE.
 * No demuestra rollback SQL; eso se prueba en Testcontainers por separado.
 */
class RestablecerAccesoUsuarioPersistenciaFailureUnitTest {

    @Test
    void a3_falloUpdateTrasValidarProveedorNoExponeSecretos() {
        // Arrange: dobles de las dependencias reales del servicio B3.
        var sql = mock(NamedParameterJdbcTemplate.class);
        var jdbc = mock(JdbcTemplate.class);
        var autorizacion = mock(AutorizacionSesionInterna.class);
        var encoder = mock(PasswordEncoder.class);
        var service = new RestablecerAccesoUsuarioService(sql, autorizacion, encoder);

        UUID admin = UUID.fromString("e5400000-0000-4000-8000-000000000001");
        String temporal = "TemporalFicticiaA3_54";
        String hashOriginal = "$2a$12$HASH_ORIGINAL_FICTICIO";
        String hashCalculado = "$2a$12$HASH_NUEVO_FICTICIO";
        long idUsuario = 95403L;

        when(sql.getJdbcTemplate()).thenReturn(jdbc);
        when(sql.queryForList(contains("FROM \"USUARIO\""), anyMap())).thenReturn(
                List.of(Map.of(
                    "idUsuario", idUsuario,
                    "estado", "HABILITADO",
                    "credencialProtegida", hashOriginal,
                    "cambioCredencialPendiente", false)));
        when(sql.queryForList(contains("FROM \"USUARIO_PROVEEDOR\""), anyMap()))
                .thenReturn(List.of(Map.of("idProveedor", 95401L, "existeProveedor", 95401L)));
        when(sql.queryForList(anyString(), anyMap(), eq(String.class)))
                .thenReturn(List.of("PROVEEDOR"));
        when(sql.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(encoder.matches(temporal, hashOriginal)).thenReturn(false);
        when(encoder.encode(temporal)).thenReturn(hashCalculado);

        var fallo = new DataAccessResourceFailureException(
                "Fallo de persistencia simulado al actualizar USUARIO");
        doThrow(fallo).when(sql).update(
                contains("UPDATE \"USUARIO\""), any(MapSqlParameterSource.class));

        // Act: control administrativo y lecturas válidos; falla el UPDATE final.
        var recibido = assertThrows(DataAccessResourceFailureException.class,
                () -> service.restablecerAccesoUsuario(
                    admin, idUsuario, true, temporal));

        // Assert: excepción controlada, sin texto claro ni hash en su mensaje.
        assertSame(fallo, recibido);
        assertFalse(recibido.getMessage().contains(temporal));
        assertFalse(recibido.getMessage().contains(hashOriginal));
        assertFalse(recibido.getMessage().contains(hashCalculado));
        verify(autorizacion, times(2)).exigirRoles(admin, Set.of("ADMINISTRADOR"));
        verify(sql, times(1)).update(
                contains("UPDATE \"USUARIO\""), any(MapSqlParameterSource.class));
        verify(encoder).encode(temporal);
    }
}
