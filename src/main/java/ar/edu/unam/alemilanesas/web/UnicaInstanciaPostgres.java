package ar.edu.unam.alemilanesas.web;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gate B4-02D: aplica el limite por cuenta de JVM solo cuando una unica
 * instancia puede operar sobre la BD. Sin tablas adicionales a MR5.
 *
 * El lock es de SESION PostgreSQL: se retiene una conexion JDBC dedicada.
 * Perder la conexion invalida irreversiblemente el proceso hasta reiniciarlo.
 * Todas las rutas protegidas fallan cerradas si el lock no se verifica.
 */
@Component
@ConditionalOnProperty(prefix="ale.security", name="singleton-db-guard", havingValue="true")
public class UnicaInstanciaPostgres implements ApplicationRunner, AutoCloseable {
    private static final int LOCK_NAMESPACE=4402;
    private static final int LOCK_KEY=2026;

    private final DataSource dataSource;
    private Connection conexion;
    private boolean perdido;

    public UnicaInstanciaPostgres(DataSource dataSource) {this.dataSource=dataSource;}

    @Override
    public synchronized void run(ApplicationArguments args) throws Exception {
        if (conexion!=null || perdido) throw new IllegalStateException("Gate de instancia ya iniciado");
        Connection c=dataSource.getConnection();
        try {
            c.setAutoCommit(true);
            try (PreparedStatement ps=c.prepareStatement(
                    "SELECT pg_try_advisory_lock(?, ?)")) {
                ps.setInt(1,LOCK_NAMESPACE);
                ps.setInt(2,LOCK_KEY);
                try (ResultSet rs=ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean(1)) {
                        throw new IllegalStateException(
                                "Otra instancia autentica contra esta base de datos");
                    }
                }
            }
            conexion=c;
        } catch (Exception ex) {
            perdido=true;
            try { c.close(); } catch (SQLException ignored) { /* fail closed */ }
            throw ex;
        }
    }

    public synchronized boolean operativo() {
        if (perdido || conexion==null) return false;
        try {
            // El chequeo se ejecuta en la misma sesion que retiene el lock.
            // Si falla o cambia la conexion, no intentamos recuperar el lock
            // silenciosamente: reinicio controlado obligatorio.
            if (!conexion.isValid(1)) { perdido=true; return false; }
            return true;
        } catch (SQLException ex) {
            perdido=true;
            return false;
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        perdido=true;
        if (conexion!=null) {
            try { conexion.close(); } catch (SQLException ignored) { /* cierre best effort */ }
            conexion=null;
        }
    }

    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(prefix="ale.security", name="singleton-db-guard", havingValue="true")
    public static class FiltroGate extends OncePerRequestFilter {
        private final UnicaInstanciaPostgres gate;
        public FiltroGate(UnicaInstanciaPostgres gate) {this.gate=gate;}

        @Override
        protected boolean shouldNotFilter(HttpServletRequest req) {
            String path=req.getServletPath();
            return !(path.startsWith("/api/v1/") || path.startsWith("/web/"));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,
                FilterChain chain) throws ServletException,IOException {
            if (!gate.operativo()) {
                res.setHeader("Cache-Control","no-store");
                res.sendError(503);
                return;
            }
            chain.doFilter(req,res);
        }
    }
}
