package ar.edu.unam.alemilanesas.web.demo;

import java.util.*;

/** Modelos de presentación, con campos presentes incluso en estado inicial vacío. */
final class FormularioDemo {
    static final List<String> DIRECCION=List.of("calle","numero","piso","unidadFuncional","localidad","provincia","referencia","zona");
    private FormularioDemo() {}
    static Map<String,Object> pedido(Map<String,String> datos) {
        Map<String,Object> form=new LinkedHashMap<>();
        for (String campo:List.of("idCliente","fechaReparto","claveIdempotencia")) form.put(campo,Objects.toString(datos.get(campo),""));
        for (String campo:DIRECCION) form.put(campo,Objects.toString(datos.get(campo),""));
        List<Map<String,String>> items=new ArrayList<>();
        for (int i=0;i<3;i++) items.add(Map.of(
                "idProducto",Objects.toString(datos.get("items["+i+"].idProducto"),""),
                "cantidadSolicitada",Objects.toString(datos.get("items["+i+"].cantidadSolicitada"),"")));
        form.put("items",items);
        return form;
    }
}
