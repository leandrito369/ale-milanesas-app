package ar.edu.unam.alemilanesas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifica la presencia de siete fronteras lógicas; no afirma cumplimiento
 * completo de reglas de dependencias aún sin servicios implementados.
 */
class ModuleSkeletonTest {

    @Test
    void sevenLogicalModulesHaveDeclaredBoundary() throws Exception {
        String[] modules = {
            "acceso", "maestros", "ventas", "produccioninventario",
            "abastecimiento", "finanzas", "canalesintegraciones"
        };
        for (String module : modules) {
            Class<?> boundary = Class.forName("ar.edu.unam.alemilanesas."
                    + module + ".ModuleBoundary");
            assertEquals("ar.edu.unam.alemilanesas." + module, boundary.getPackageName());
        }
    }
}
