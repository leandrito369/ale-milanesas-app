package ar.edu.unam.alemilanesas.web;

import ar.edu.unam.alemilanesas.maestros.GestionClientesService;
import java.util.Map;
import java.util.Set;

/**
 * Adaptador estricto de DatosCliente MR5 compartido por UC02 (POST)
 * y UC32 (PUT completo). Nunca admite atributos de autoridad,
 * estado, categoria ni IDs en el body.
 */
final class DatosClienteHttpParser {
    private static final Set<String> CAMPOS=Set.of(
            "nombres","apellidos","telefonoPrincipal","calle","numeroPuerta",
            "piso","unidadFuncional","localidad","provincia",
            "referenciaDomicilio","zona","diaHabitualReparto");

    private DatosClienteHttpParser() {}

    static GestionClientesService.DatosCliente interpretar(Map<String,Object> datos) {
        if (datos==null || !CAMPOS.containsAll(datos.keySet())) {
            throw new IllegalArgumentException("Campos de Cliente invalidos");
        }
        return new GestionClientesService.DatosCliente(
                texto(datos,"nombres"),texto(datos,"apellidos"),
                texto(datos,"telefonoPrincipal"),texto(datos,"calle"),
                texto(datos,"numeroPuerta"),texto(datos,"piso"),
                texto(datos,"unidadFuncional"),texto(datos,"localidad"),
                texto(datos,"provincia"),texto(datos,"referenciaDomicilio"),
                texto(datos,"zona"),dia(datos));
    }

    private static String texto(Map<String,Object> datos,String nombre) {
        Object valor=datos.get(nombre);
        if(valor==null) return null;
        if(!(valor instanceof String cadena)) {
            throw new IllegalArgumentException("Tipo de campo incorrecto");
        }
        return cadena;
    }

    private static Short dia(Map<String,Object> datos) {
        Object valor=datos.get("diaHabitualReparto");
        if (valor==null) return null;
        if (!(valor instanceof Integer entero) || entero<1 || entero>7) {
            throw new IllegalArgumentException("Dia fuera de rango");
        }
        return entero.shortValue();
    }
}
