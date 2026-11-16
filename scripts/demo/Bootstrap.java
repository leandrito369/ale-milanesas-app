import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Solo fixtures DML en DB aislada ya migrada. No crea esquema ni sesiones falsas. */
class Bootstrap {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("ALE_DEMO_JDBC_URL");
        if (!"jdbc:postgresql://127.0.0.1:55439/ale_milanesas_demo".equals(url))
            throw new IllegalArgumentException("Solo DB demo loopback fija");
        String clave=System.getenv("ALE_DEMO_CREDENTIAL");
        if (clave==null || clave.length()<12 || clave.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("Credencial efimera: 12 caracteres minimo y 72 bytes maximo");
        String hash=new BCryptPasswordEncoder(12).encode(clave);
        try(Connection c=DriverManager.getConnection(url,"ale_demo",System.getenv("ALE_DB_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                try(Statement s=c.createStatement()) {
                    s.execute("SET LOCAL search_path TO ale_gestion");
                    try(ResultSet r=s.executeQuery("SELECT current_database(), (SELECT count(*) FROM \"USUARIO\"), (SELECT count(*) FROM \"CLIENTE\"), (SELECT count(*) FROM \"PRODUCTO\"), (SELECT count(*) FROM \"POLITICA_CATEGORIA_COMERCIAL\")")) {
                        r.next();
                        if (!"ale_milanesas_demo".equals(r.getString(1)) || r.getLong(2)!=0 || r.getLong(3)!=0 || r.getLong(4)!=0 || r.getLong(5)!=0)
                            throw new IllegalStateException("Se requiere demo vacia; usar reset explicito");
                    }
                }
                String[] roles={"ADMINISTRADOR","VENDEDOR","REPARTIDOR"};
                for(int i=0;i<roles.length;i++) {
                    update(c,"INSERT INTO \"ROL\" VALUES (?,?) ON CONFLICT DO NOTHING",roles[i],roles[i]);
                    update(c,"INSERT INTO \"USUARIO\" (\"idUsuario\",\"nombreUsuario\",\"credencialProtegida\",\"estado\",\"cambioCredencialPendiente\",\"fechaHoraAlta\") VALUES (?,?,?,'HABILITADO',false,clock_timestamp())",91001+i,"demo_"+roles[i].toLowerCase(Locale.ROOT),hash);
                    update(c,"INSERT INTO \"USUARIO_ROL\" VALUES (?,?,clock_timestamp())",91001+i,roles[i]);
                }
                update(c,"INSERT INTO \"UNIDAD_MEDIDA\" VALUES ('UN','Unidad') ON CONFLICT DO NOTHING");
                update(c,"INSERT INTO \"CLIENTE\" (\"idCliente\",\"nombres\",\"telefonoPrincipal\",\"estado\",\"categoriaComercial\",\"fechaHoraAlta\") VALUES (92001,'Cliente sintetico demo','0000000000','ACTIVO','CUMPLIDOR',clock_timestamp())");
                update(c,"INSERT INTO \"PRODUCTO\" (\"idProducto\",\"nombre\",\"nombreNormalizado\",\"tipoProducto\",\"codigoUnidadVenta\",\"activo\") VALUES (93001,'Producto sintetico demo','producto sintetico demo','ELABORADO','UN',true)");
                update(c,"INSERT INTO \"PRECIO_PRODUCTO\" (\"idPrecio\",\"idProducto\",\"precioBase\",\"vigenteDesde\",\"fechaHoraRegistro\",\"idUsuarioRegistro\") VALUES (94001,93001,100,clock_timestamp()-interval '1 day',clock_timestamp(),91001)");
                update(c,"INSERT INTO \"POLITICA_CATEGORIA_COMERCIAL\" (\"idPoliticaComercial\",\"diasToleranciaMora\",\"vigenteDesde\",\"fechaHoraRegistro\",\"idUsuarioRegistro\") VALUES (95001,5,clock_timestamp()-interval '1 day',clock_timestamp(),91001)");
                update(c,"INSERT INTO \"AJUSTE_POLITICA_CATEGORIA\" VALUES (95001,'CUMPLIDOR','SIN_AJUSTE',0),(95001,'EN_MORA','RECARGO',10)");
                c.commit();
                System.out.println("Fixture sintetica creada. Cliente 92001; producto 93001; repartidor 91003. Sin sesiones prefabricadas.");
            } catch(Exception ex) {c.rollback();throw ex;}
        }
    }
    static void update(Connection c,String sql,Object... values) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);
            s.executeUpdate();
        }
    }
}
