package ar.edu.unam.alemilanesas.concurrencia;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instrumentación de observabilidad PostgreSQL para experimentos C3-A.3 (solo test).
 * Consulta pg_locks, pg_stat_activity, pg_blocking_pids, wait_event_type y SQLSTATE.
 */
final class ObservabilidadPostgresTestHelper {
    private ObservabilidadPostgresTestHelper() {}

    static int pid(Connection conexion) throws SQLException {
        try (PreparedStatement p = conexion.prepareStatement("SELECT pg_backend_pid()");
             ResultSet rs = p.executeQuery()) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    static List<Map<String, Object>> esperarBloqueoDePid(
            JdbcTemplate jdbc, int pidBloqueador, String relacion) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> !sesionesEsperandoRelacion(jdbc, pidBloqueador, relacion).isEmpty());
        return sesionesEsperandoRelacion(jdbc, pidBloqueador, relacion);
    }

    static List<Map<String, Object>> sesionesEsperandoRelacion(
            JdbcTemplate jdbc, int pidBloqueador, String relacion) {
        return jdbc.queryForList("""
                SELECT a.pid, a.wait_event_type, a.wait_event, l.locktype, l.mode,
                       l.granted, pg_blocking_pids(a.pid) AS bloqueadores
                  FROM pg_stat_activity a
                  JOIN pg_locks l ON l.pid = a.pid
                 WHERE NOT l.granted
                   AND l.relation = to_regclass(?)
                   AND a.wait_event_type = 'Lock'
                   AND ? = ANY(pg_blocking_pids(a.pid))
                """, "\"" + relacion + "\"", pidBloqueador);
    }

    static List<Map<String, Object>> esperarBloqueoAdvisory2(
            JdbcTemplate jdbc, int pidBloqueador, int classid, int objid) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> !sesionesEsperandoAdvisory2(jdbc, pidBloqueador, classid, objid).isEmpty());
        return sesionesEsperandoAdvisory2(jdbc, pidBloqueador, classid, objid);
    }

    static List<Map<String, Object>> sesionesEsperandoAdvisory2(
            JdbcTemplate jdbc, int pidBloqueador, int classid, int objid) {
        return jdbc.queryForList("""
                SELECT a.pid, a.wait_event_type, a.wait_event, l.locktype, l.mode,
                       l.granted, pg_blocking_pids(a.pid) AS bloqueadores
                  FROM pg_stat_activity a
                  JOIN pg_locks l ON l.pid = a.pid
                 WHERE NOT l.granted
                   AND l.locktype = 'advisory'
                   AND l.classid = ?
                   AND l.objid = ?
                   AND l.objsubid = 2
                   AND a.wait_event_type = 'Lock'
                   AND ? = ANY(pg_blocking_pids(a.pid))
                """, classid, objid, pidBloqueador);
    }

    static List<Map<String, Object>> esperarBloqueoAdvisory1(
            JdbcTemplate jdbc, int pidBloqueador, long clave) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> !sesionesEsperandoAdvisory1(jdbc, pidBloqueador, clave).isEmpty());
        return sesionesEsperandoAdvisory1(jdbc, pidBloqueador, clave);
    }

    static List<Map<String, Object>> sesionesEsperandoAdvisory1(
            JdbcTemplate jdbc, int pidBloqueador, long clave) {
        return jdbc.queryForList("""
                SELECT a.pid, a.wait_event_type, a.wait_event, l.locktype, l.mode,
                       l.granted, pg_blocking_pids(a.pid) AS bloqueadores
                  FROM pg_stat_activity a
                  JOIN pg_locks l ON l.pid = a.pid
                 WHERE NOT l.granted
                   AND l.locktype = 'advisory'
                   AND l.classid::bigint = 0
                   AND l.objid::bigint = ?
                   AND l.objsubid = 1
                   AND a.wait_event_type = 'Lock'
                   AND ? = ANY(pg_blocking_pids(a.pid))
                """, clave, pidBloqueador);
    }

    static String extraerSqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx && sqlEx.getSQLState() != null) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
