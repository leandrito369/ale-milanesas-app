# G3-SEC-02 — Instanciación temprana del guard prod

Base: c74a5aaa4373554eb644b19702de3ccf5c57b497. Rama independiente: fix/g3-sec-02-precondiciones-despliegue-prod-2026-11-17. Sin merge ni autorización de despliegue.

## Diagnóstico reproducido antes de modificar producción

Se registró el componente real mediante component scan en AnnotationConfigApplicationContext con perfil prod y configuración sintética conforme. refresh falló antes de evaluar precondiciones:

    BeanCreationException: precondicionesDespliegue
    BeanInstantiationException: No default constructor found
    NoSuchMethodException: PrecondicionesDespliegue.<init>()

La prueba nueva configuracionValidaInstanciaGuardYEjecutaRefresh quedó roja sobre la base. No invoca manualmente new PrecondicionesDespliegue para verificar la creación.

Versiones efectivas: Spring Boot 4.1.2 y Spring Framework 7.0.10. El ciclo en AbstractApplicationContext.refresh invoca BeanFactoryPostProcessor antes de registrar los BeanPostProcessor, incluidos los de inyección de constructor. La clase solo tenía constructor(Environment), sin constructor vacío. En esa etapa temprana no se resuelve esa inyección y SimpleInstantiationStrategy busca un constructor sin argumentos. El componente sí estaba registrado y el perfil era correcto: el fallo se localizó en la instanciación temprana, no en los vetos ni en la disponibilidad de PostgreSQL.

Fuentes de la versión efectiva: AbstractApplicationContext.java líneas 603/605 (orden de BFPP/BPP), línea 736 (ApplicationContextAwareProcessor ya instalado); ApplicationContextAwareProcessor.java líneas 91/92 (EnvironmentAware). Sources consultados desde org.springframework:spring-context:7.0.10:sources, sin añadir dependencias al proyecto.

## Corrección mínima y fail-closed

PrecondicionesDespliegue conserva Component, Profile(prod) y BeanFactoryPostProcessor. Ahora también implementa EnvironmentAware y dispone de constructor sin argumentos. ApplicationContextAwareProcessor, disponible antes de invocar BFPP, suministra Environment antes de postProcessBeanFactory.

El constructor(Environment) permanece para compatibilidad con las pruebas directas existentes. No se utiliza como prueba de instanciación Spring. Si Environment no se suministra, postProcessBeanFactory lanza IllegalStateException: no se omite la validación, no se ignoran excepciones ni se degrada el fallo a un warning.

La lógica de todos los vetos y las exigencias de origen/backend/proxy/singleton permanece textual y funcionalmente intacta. No se modifican flags, defaults, roles, RBAC, servicios, controles de sesión/CSRF, vistas, CSS, Flyway o datos. Tampoco se toca el Corte F de Antigravity.

## Pruebas y matriz

|Caso|Resultado esperado/verificado|Evidencia|
|---|---|---|
|Configuración conforme, registro por scan, prod|PASS: refresh completo y un único guard real|PrecondicionesDespliegueContextTest|
|21 flags experimentales, cada una true en prod|PASS: 21 arranques rechazados por IllegalStateException del guard|Test parametrizado de contexto|
|UC03 y UC43 true|PASS: rechazo real de contexto, incluidos en los 21 anteriores|Mismo parametrizado|
|HTTP, HTTPS sin host, ruta, userinfo, query, fragmento u origen vacío|PASS: 7 rechazos de contexto|origenInvalidoAbortaContexto|
|Backend 0.0.0.0, singleton false, cabeceras framework|PASS: 3 rechazos de contexto|aislamientoInvalidoAbortaContexto|
|Cada propiedad obligatoria ausente|PASS: 4 rechazos; propiedades del host excluidas del contexto de ensayo|propiedadObligatoriaAusenteAbortaContexto|
|Perfil demo con configuración inválida para prod|PASS: guard ausente, refresh válido|Test de contexto|
|Sin Environment al invocar guard|PASS: falla cerrada|PrecondicionesDespliegueTest|
|Reglas unitarias directas existentes|PASS: se conservan; ahora 27 casos|PrecondicionesDespliegueTest|
|Aplicación completa prod con configuración conforme|PASS: contexto Boot real y lock singleton operativo, sin puerto HTTP|GuardProdApplicationTest|
|Aplicación completa demo|PASS: contexto Boot real, guard prod ausente y adaptadores demo presentes|GuardDemoApplicationTest|
|Auth/sesiones/UC53, CSRF/continuidad95, roles/gates y operaciones HTTP|Regresión automática ejecutada; resultado agregado en PR|Tests históricos y ContinuidadSesionHttpsTest|
|Infraestructura productiva, TLS externo y aceptación UC-54|NO EJECUTADO y no autorizado por este corte|Issue #28 mantiene estado independiente|

Las21 banderas cubren clientes 02/32/38; productos 30/47/48; usuarios 17/18/55; compras 08 insumo/08 reventa/42; precios 19; categorías 57; insumos 50/51/52; pedidos 03/43; recetas 10; preparaciones 28. La matriz de contexto no se limita al conjunto representativo solicitado: comprueba el catálogo completo actual. Se mantienen además los parametrizados unitarios previos.

La primera ejecución posterior al cambio tuvo una aserción del arnés demasiado estricta: exigía que la causa más profunda de todo rechazo fuera IllegalStateException. Para una URI inválida, el guard ya la envolvía correctamente en IllegalStateException con URISyntaxException anidada. Se corrigió el test para exigir la excepción del guard en refresh, sin confundirla con BeanCreationException; no se cambió la regla del origen.

## Alcance de los contextos completos

GuardProdApplicationTest y GuardDemoApplicationTest cargan la aplicación completa con SpringBootTest/MOCK y PostgreSQL 17.11 efímero por Testcontainers. No levantan un servidor HTTP. Se aplican las migraciones existentes a una DB descartable; no se edita DDL ni se prepara información comercial. En prod el ApplicationRunner del singleton adquiere su lock real y se comprueba operativo. En demo se comprueba ausencia del guard y presencia del controlador de consultas.

El contexto negativo de la matriz solo registra el guard: no usa DB ni abre puertos. Esto aísla el motivo de rechazo. Los tests HTTP históricos de regresión sí usan sus servidores/fixtures sintéticos habituales, conforme su alcance; no apuntan a una instalación del negocio.

## Comandos y resultados

    mvn -B -ntp -Dtest=PrecondicionesDespliegueContextTest#configuracionValidaInstanciaGuardYEjecutaRefresh test
    # Antes del fix: FAIL, NoSuchMethodException/No default constructor.

    mvn -B -ntp -Dtest=PrecondicionesDespliegueContextTest,PrecondicionesDespliegueTest,GuardProdApplicationTest,GuardDemoApplicationTest,ContinuidadSesionHttpsTest,DemoSeguridadTest,AutenticacionHttpExternaTest,AutenticacionHttpIntegrationTest,CargarPedidoHttpExternoTest,AsignarRepartoHttpExternoTest,BuscarPedidoHttpExternoTest verify

Resultado local: **119 tests, 0 fallos, 0 errores, 0 omitidos; BUILD SUCCESS**. Incluye compilación y fase Maven verify.

La ejecución local usa JDK 21/Maven 3.9.11 con caché y settings de proxy fuera del repositorio. El resultado final, SHA y enlaces CI se registran en la PR independiente, sin atribuir los checks de main al nuevo SHA.

## Riesgos y límites

La configuración válida superó el guard y el contexto completo MOCK; esto no certifica una interfaz de red real, Nginx, TLS público, failover o acceso productivo. La validación de las reglas no se amplía ni se cambia silenciosamente. El catálogo de flags debe acompañar futuras operaciones mediante su propio cambio y tests; este corte preserva el catálogo existente.

Sin despliegue, merge, rebase, amend, force-push o push a main. Issue #28, infraestructura y aceptación UC-54/BC06 siguen independientes. La corrección es candidata a revisión humana, no una autorización de producción.
