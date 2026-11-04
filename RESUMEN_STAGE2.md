# Resumen del trabajo: correcciones del Stage 1 e implementación del Stage 2

Todo está en la rama local `stage-2`. **No se ha subido nada a GitHub.** Los documentos para entregar son
[`stage2/docs/REPORT.pdf`](stage2/docs/REPORT.pdf) (informe del Stage 2) y
[`docs/STAGE1_ADDENDUM.pdf`](docs/STAGE1_ADDENDUM.pdf) (correcciones del Stage 1).

**Antes de entregar, cuatro cosas dependen de vosotros** (detalle en la sección 5): nombres y DNI/ID en la portada, crear
el repositorio `stage_2`, que el historial refleje a todos los miembros, y leer el informe para poder defenderlo.

## 1. Qué pide el Stage 2

Convertir la capa de datos del Stage 1 en **servicios que cooperan, como procesos separados, en una sola máquina**, y
averiguar con medidas hasta dónde llega el diseño y qué lo limita.

Requisitos clave:

- **Tres servicios en Java 17 con Javalin:** Ingestion (descarga al datalake), Indexing (metadatos e índice invertido en el
  datamart) y Search (búsqueda, filtros y ranking). Cada uno es un proyecto Maven con su jar ejecutable.
- **Un mecanismo de coordinación explícito y justificado.** Puede ser un orquestador, un broker, tareas programadas o
  memoria compartida. Da igual cuál, pero un libro solo se puede indexar cuando su ingesta ha terminado, Search debe leer un
  estado publicado coherente y repetir peticiones no puede duplicar ni corromper nada.
- **Réplicas e hilos, bien distinguidos.** Hay que explicar cómo se reparte el trabajo y cómo se evitan el trabajo duplicado,
  las escrituras en conflicto y el agotamiento de hilos.
- **API documentada:** una especificación versionada (OpenAPI), con inventarios separados de endpoints para el front-end,
  internos y operativos, más pruebas ejecutables que la validen. El front-end en sí no hay que construirlo.
- **Benchmarks:** JMH obligatorio (5 + 10 iteraciones, en `src/test/java`, exportando CSV); de integración, opcionales.
  Hay que separar concurrencia de clientes, hilos y réplicas, e interpretar dónde se satura y por qué.
- **Preparar el Stage 3:** identificar lo que depende de una sola máquina, sin presentar lo medido aquí como prueba de que
  escalará en un clúster.
- **Entregables:** informe PDF con estructura fija y portada, repositorio `github.com/<grupo>/stage_2`, README, especificación,
  scripts y un historial de Git con contribuciones de todos. Además hay defensa oral individual de 4 minutos, que vale el 10%.

## 2. Correcciones del profesor

El profesor presenta casi todas como mejoras sugeridas, y una como defecto.

| # | Corrección | Tipo | Qué hice |
|---|---|---|---|
| 11 | Faltaba lookup, faltaba coordinador pendiente-primero, y se marcaba un libro antes de guardarlo | **Obligatoria** (defecto, y el Stage 2 depende de ello) | Corregido: se guarda antes de marcar, escrituras atómicas, `lookup.py`, `coordinator.py` |
| 12 | Lógica de control y rutas duplicada, listas de libros fijas en el código | Recomendada | Corregido: `pipeline_common.py` y lista configurable en los tres lenguajes |
| 1 | Índice con números de página | Recomendada | En el addendum y en el informe del Stage 2 |
| 2 | Búsqueda de libros que no existen y descubrimiento de nuevos | Recomendada | Medido. Time-based es 133 veces más lento que book-based en un libro inexistente |
| 3 | Sobrecoste de cada estructura en archivos y bytes | Recomendada | Medido. Los bytes asignados son una estimación |
| 4 | Conjunto de consultas declarado, con latencia | Recomendada | Medido. Las tres estructuras devuelven los mismos IDs |
| 5 | RAM y disco de cada índice | Recomendada | Medido |
| 6 | Varios tamaños con operación fija | Recomendada | Medido |
| 7 | Interrumpir la ingesta y reanudarla | Recomendada | Hecho con caídas reales |
| 8 | Comprobaciones entre estructuras | Recomendada | Hecho. **Encontró un defecto real**: el tokenizador perdía palabras como `español` (12,8% más de términos tras arreglarlo) |
| 9 | CPU, RAM, disco, sistema y versiones reales | Recomendada | Documentado |
| 10 | Comandos y datos crudos de las dos gráficas | Recomendada | Regeneradas desde la revisión `b2224d7` |

**Apliqué todas. Con dos matices:**

- **Corrección 1:** el PDF original del Stage 1 no está en el repositorio, así que no pude añadirle el índice. Lo cubre el addendum, que complementa al informe original y no lo sustituye.
- **Corrección 10:** los datos crudos originales ya no existen, así que **no se pueden verificar los valores antiguos**, solo medir de nuevo. Dos resultados de Java del README no se reproducen: ningún layout es claramente más rápido, y su índice JSON gana a SQLite (2,5 ms frente a 8,1 ms). Está explicado en el addendum y avisado en el README, sin ocultarlo.

**No apliqué, y por qué:** los workers de Java y Node solo recibieron la lista de libros configurable. Implementan ingesta y benchmarks, no el pipeline completo, así que el resto de correcciones no les aplica.

## 3. Decisiones donde el enunciado no era claro

Las 31 decisiones, con su motivo, están en [`stage2/docs/DECISIONS.md`](stage2/docs/DECISIONS.md). Las que probablemente te preguntarán:

| Duda | Decisión | Motivo |
|---|---|---|
| ¿Qué coordinación? | Orquestación con un Control Module | Todo el estado está en una tabla que se puede inspeccionar, y no hace falta un broker. Medido: cuesta alrededor del 3% del tiempo de un libro |
| ¿Cuándo se «publica»? | Es el commit de una única transacción SQLite | Un libro se ve entero o no se ve. Un paso aparte añadiría otro punto de fallo |
| ¿Hace falta refrescar cachés? | No: las entradas llevan la versión del índice | Así no se puede olvidar una invalidación, tampoco entre réplicas |
| ¿Cómo se comprueba el último paso? | `PUBLISHED` solo tras un 200 de `GET /books/{id}` en Search | Prueba lo que importa: que el front-end lo ve |
| Layout del datalake | Time-based, con un ledger | El enunciado lo pide, y el ledger elimina su debilidad medida en el Stage 1 |
| Año de publicación | De la línea de cabecera o de una lista curada; si no, `null` | **Ninguno de los 20 libros reales lo trae en la cabecera**, y la fecha de release daría 1998 a *Orgullo y prejuicio*. Mejor sin año que con uno falso |
| Varios términos en la consulta | AND, ordenado por BM25 | Es lo habitual en un buscador, y BM25 tiene en cuenta rareza y longitud |
| Parámetros desconocidos | Error 400 | Un filtro mal escrito e ignorado devolvería resultados que parecen correctos |
| ¿Benchmarks de integración? | Sí, aunque son opcionales | Las preguntas clave sobre cuellos de botella solo se responden con el sistema completo |
| ¿Un proyecto Maven por servicio? | Agregador con un módulo `common` | Cada servicio tiene su pom y su jar y se compila por separado, y se evita la duplicación que criticó el profesor |
| ¿Contra Gutenberg de verdad? | Un sustituto local con 20 libros reales, más una ejecución aparte contra el sitio real | Si no, mediría Internet y cargaría un servicio público |

## 4. Cómo se ejecuta todo, y qué sale

### Comandos

Necesitas JDK 17, Maven 3.9 y Python 3.10 o superior. Usa carpetas de datos **fuera de OneDrive**.

```bash
cd stage2
python -m pip install -r scripts/requirements.txt
mvn clean package                                                # compila y pasa los tests

# Levantar el sistema y probarlo a mano
python scripts/deploy.py up --data-dir C:/tmp/search
curl -X POST http://localhost:7000/control/books -H "Content-Type: application/json" -d "{\"book_ids\":[1342,84,11,2701,2000]}"
curl http://localhost:7000/control/status                        # esperar a que estén PUBLISHED
curl "http://localhost:7003/search?q=whale"
curl "http://localhost:7003/search?q=rabbit&author=carroll&limit=5"
python scripts/deploy.py down --data-dir C:/tmp/search

# Validación (despliegan solos; no necesitan red)
python scripts/api_check.py --data-dir C:/tmp/api-check          # esperado: 25/25
python scripts/failure_check.py --data-dir C:/tmp/failure-check  # esperado: 17/17

# Benchmarks
python scripts/run_jmh.py                                        # unos 20 min
python scripts/bench_integration.py all --data-dir C:/tmp/bench  # algo más de 1 hora
python scripts/plot_results.py                                   # gráficas y tablas
python scripts/build_pdf.py docs/REPORT.md                       # PDF del informe
```

Para el Stage 1, dentro de `worker_python`:

```bash
python -m pytest test_pipeline.py -q
python stage1_experiments.py all --workdir <carpeta fuera de OneDrive>       # unos 45 min
python stage1_reproduce_charts.py run --workdir <carpeta fuera de OneDrive>  # unos 15 min
```

### Resultados de validación

| Comprobación | Resultado |
|---|---|
| Tests JUnit | **75/75** |
| API contra la especificación OpenAPI | **25/25** |
| Escenarios de fallo (caídas en líneas exactas, servicios caídos, duplicados entre réplicas) | **17/17** |
| Pruebas del Stage 1 en Python | **24/24** |
| 12 libros reales por el sitio real | 12 publicados, 0 fallidos |
| Todos los benchmarks | 0 libros fallidos, 0 errores de búsqueda, 0 totales incorrectos, índice idéntico en todas las configuraciones |

### Resultados de rendimiento

Medidos en un portátil AMD Ryzen 7 4800H (8 núcleos físicos, 16 lógicos), 16 GB, SSD NVMe, Windows 11.

**El hallazgo principal: lo que limita el sistema es publicar en el índice.** Es un proceso en serie, y se vuelve más lento a medida que crece el índice.

| Pregunta | Resultado |
|---|---|
| ¿Cuántos libros por segundo? | **6,46 ± 0,04** con el índice pequeño (0,155 s por libro). Se satura con solo 2 hilos del Control |
| ¿Y con el índice grande? | Baja de **8,8 a 1,7 libros/s** de 0 a 1000 libros. Cada libro toca miles de páginas: unos 53 MB de log por libro |
| ¿Ayudan más réplicas de Indexing? | **No** (6,58 y 6,81, dentro del ruido). Compiten por el mismo candado, y consumen 2 y 4 veces más memoria |
| ¿Cuesta mucho el orquestador? | **No**: de 6 a 10 ms por libro, alrededor del 3%. No es el cuello de botella |
| ¿Búsqueda bajo carga? | Se satura hacia **16 clientes, en unas 7700 peticiones/s**, con p95 de 3,2 ms. Con 64 clientes el p95 sube a 18,8 ms. Lo limita la CPU de la máquina |
| ¿Hilos en Search? | Mejora hasta 8 hilos (6914 peticiones/s), luego no gana nada. Coincide con los 8 núcleos físicos |
| ¿Réplicas de Search en una máquina? | **Empeoran**: 7036 → 6119 → 4645 peticiones/s con 1, 2 y 4. Esto **no** dice nada de un clúster |
| ¿Caché? | 12 522 peticiones/s frente a 7812, pero es el **mejor caso** posible: solo hay 20 consultas distintas |
| ¿Leer y escribir a la vez? | Funciona sin errores. Las lecturas bajan un 24% y el p95 sube de 2,62 a 3,06 ms, pero la peor petición pasa de unos 30 a 320–480 ms |

**Lo mejor para contar en la defensa: los benchmarks encontraron defectos reales**, y se corrigieron con medidas de antes y después:

1. **Inanición de escritores.** Con 4 hilos, una operación del ledger pasaba de 1,4 ms a unos 1,8 s de media. Solucionado con un candado justo: ahora son 7,9 ms.
2. **Log de SQLite sin límite.** Con réplicas, 1,3 a 1,9 GB de disco para un índice de 61 MB. Ahora está acotado: el pico es de 119 MB.
3. **Orden de escritura.** Insertar en el orden de la clave ahorra un 19% por libro con el índice de 500 libros.

En conjunto, el rendimiento pasó de **4,10 a 6,46 libros/s (+58%)**.

**Paradas puntuales.** Algunas ejecuciones son mucho más lentas que sus repeticiones (3,04 frente a 4,92 libros/s). Lo investigué con una sonda independiente: durante esas paradas, un proceso ajeno ve subir su `fsync` de unos 3 a unos 25 ms, su CPU no cambia, y el disco escribe menos. El mismo efecto apareció en un script de SQLite sin ningún servicio (publicaciones de 8–9 s). **La espera está en el disco o el sistema, no en la lógica de los servicios.** Mantuve esas ejecuciones en las medias y las tablas muestran cada valor.

## 5. Qué queda pendiente o es dudoso

### Lo tienes que hacer tú

1. **Portada del informe:** completar nombres completos y DNI/ID en `stage2/docs/REPORT.md` y regenerar con `python scripts/build_pdf.py docs/REPORT.md`. **Puse el curso 2026/2027 deduciéndolo de las fechas de Git**; compruébalo.
2. **Repositorio:** el enunciado exige `https://github.com/Calima-Data/stage_2`, y esa es la URL de la portada, pero el trabajo está en la rama `stage-2` del repo `stage_1`. No he creado ni subido nada, y no he comprobado si ya existe. Dímelo cuando quieras subirlo.
3. **Historial de Git:** el enunciado pide contribuciones de todos los miembros, y los 20 commits nuevos tienen un solo autor. Además se evalúan las contribuciones individuales en la defensa. Es algo que tenéis que resolver como grupo.
4. **Defensa oral:** este trabajo se hizo con mucha ayuda de una IA, y cada miembro debe poder explicarlo y justificarlo. Lee el informe y `DECISIONS.md`.

### Limitaciones de las medidas

- **Máquina no aislada:** un portátil con Windows Defender y otros servicios en marcha, aunque el enunciado recomienda un host inactivo. El mismo experimento varió hasta un **10%** entre dos momentos, así que las diferencias menores no significan nada.
- **El generador de carga y el sustituto de Gutenberg comparten máquina** con los servicios, y consumen hasta 5,9 núcleos.
- **Corpus repetido:** 20 libros reales que se reutilizan, sin vocabulario nuevo. El crecimiento real del índice sería peor.
- **El crecimiento de 1000 libros es una sola ejecución:** muestra la forma de la curva, no un valor preciso.
- **El JMH de búsqueda no es monótono** a pocos hilos (916 µs con 2 y 719 con 4). No lo investigué.

### Preguntas sin resolver

Están señaladas como tales en el informe, sin inventar explicaciones:

- La causa exacta de las paradas de disco (SSD, sistema o antivirus). Intenté registrar la actividad de Defender y no obtuve datos.
- Por qué 2 clientes rinden 2,6 veces más que 1. Descarté el calentamiento de la JVM como causa principal (solo +9%).
- Por qué las lecturas bajan un 24% al escribir, si la CPU del host sigue en el 66%, y por qué sube tanto la peor petición.
- Qué parte exacta de la mejora del +58% corresponde a cada cambio: se midió entre campañas distintas, no por separado.

### Limitaciones del diseño, ya documentadas

- Un solo Control Module: es punto único de fallo para el trabajo nuevo, aunque Search sigue sirviendo sin él.
- Sin gateway: los clientes reparten las peticiones entre réplicas.
- El filtro de año solo cubre los libros de la lista curada.
- Nada de esto demuestra que vaya a escalar en un clúster. Las propuestas para el Stage 3 están marcadas como propuestas, sin probar.

### Restos en tu carpeta

- `datalake/`, `control/` y `datamarts/` en la raíz del repo: los creó una primera ejecución de pruebas contra el código antiguo. Intenté borrarlos, pero los permisos lo impidieron. Están ignorados por Git y son inofensivos; puedes borrarlos a mano.
- `Contexto/` (tus archivos del enunciado y las correcciones) sigue sin añadirse a Git, a propósito.

### Otros detalles

- `working_tree_dirty: true` en `results/stage2/integration/host.json`: la comprobación mira la carpeta `stage2`, y yo editaba documentos y figuras durante la campaña. El código de los servicios es exactamente el del commit medido, `8c1bfa7`.
- Las evidencias de los defectos y de las paradas están en `results/stage2/evidence/`, y los archivos `contention_*.txt` en `results/stage2/jmh/`.
