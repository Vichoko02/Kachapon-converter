# Rendimiento y limpieza

## Cómo se midió

- **Escena:** `escena 1.kra` (2084x1469, 123 frames, 56 capas de pintura, 1.618 dibujos).
- **Máquina:** Ryzen 7 5700G (8 núcleos y 16 hilos), 32 GB de RAM, Krita 5.3.3.
- **Krita:** cronometrado con `Measure-Command`, siempre sobre copias en el scratchpad.
- **Java:** el `core` actual (compilado aparte, sin tocar `build/`), con JDK 25 y perfiles JFR (`settings=profile`, muestras de CPU agregadas por método).
- **Puente usado:** el ya extraído de escena 1 (1.618 PNG, 120 MB).
- **"Exacto"** quiere decir comparado byte a byte o píxel a píxel contra lo que exporta Krita. **"Sin confirmar"** quiere decir que no lo medí.

## Dónde se va el tiempo hoy

| Conversión | Medido | Dónde se va |
|---|---|---|
| .kra → puente | 744–988 s (dato de Albañil) | **Krita:** 27 capas quietas × ≈5,7 s (`--export`) + 29 capas con máscaras × ≈13,3 s (`--export-sequence`) ≈ 540 s. **Java:** copiar el .kra entero por capa, ≈2 s × 56 ≈ 112 s; `blank()` decodifica cada dibujo, ≈1.618 × 20 ms ≈ 33 s. |
| puente → .ipv | **95 s** (JFR) | `Png.decode` 67 %, `IbisConverter.rpng` 28 % (de eso, el 13,6 % del total es meter enteros en `ArrayList<Integer>`: `Integer.valueOf`, `add`, `grow`), `Png.encode` 2 %. Un solo hilo. |
| puente → .clip | **302 s** (JFR) | `Converter.flatten` de toda la animación **solo para la miniatura**: 46 %. `ClipWriter.shrink` (mipmaps): 23 %. `Composite.over`: 17 %. |
| puente → .kra | **163 s** (JFR) | `Converter.flatten` **solo para la miniatura**: 73 %. `KritaConverter.tiles`: 6 %. |

**Una exportación de Krita, por partes** (capa `craneo`, animada):

| Paso | Tiempo |
|---|---|
| Arrancar el proceso (`--version`) | 1,6 s |
| Cargar la copia de 67 MB y exportar 1 frame | 5,7 s |
| Los 123 frames con `--export-sequence` | 13,3 s |
| La misma secuencia de una capa quieta | 8,9 s |

Una sola exportación ya usa en promedio **7,1 núcleos** (79,5 s de CPU en 11,2 s reales).

**Memoria:** el pico de heap usado fue de **2,8 GB** (.ipv), **3,5 GB** (.clip) y **2,9 GB** (.kra). Los 14 GB eran el `-Xmx` de las pruebas, no lo que se usó.

## Recomendaciones, ordenadas por ganancia / esfuerzo / riesgo

| # | Cambio | Ganancia medida | Esfuerzo | ¿Cambia el resultado? | Evidencia |
|---|---|---|---|---|---|
| 1 | **Miniatura sin combinar toda la animación.** En `ClipConverter.build` (línea 46) y `KritaConverter.build` (línea 279), en lugar de `flatten(m, bridge)`, usar `Planner.compose(planner.states().get(0))` y un `Png.encode`. | **−106 s en .clip** (302 → ≈196 s) y **−106 s en .kra** (163 → ≈57 s). Solo la miniatura: 1,4 s; los 86 tramos: 107,9 s. | 2 líneas en cada archivo | **No**: la miniatura ya era el primer tramo de `flatten`. Si no hay capas, se mantiene el PNG vacío. | Arnés `Preview` sobre el puente; JFR: `flatten` = 46 % y 73 %. |
| 2 | **Leer sin Krita las capas sin máscaras activas** (ni propias ni en sus carpetas): decodificar las teselas LZF de 64x64 del `.kra` (`VERSION 2`, BGRA en planos, el primer byte dice si va comprimida). | **27 de 56 capas** de escena 1 sin abrir Krita: ≈27 × 5,7 s ≈ **−154 s**. El prototipo en Python tarda 0,03–0,7 s por capa; en Java sería menos (sin confirmar). | Medio: un lector de teselas y de `keyframes.xml` | **No** en lo medido: `Layer16` (634 píxeles visibles) y `Capa de pintura 30` (3.291) dan **0 píxeles distintos** frente a la exportación aislada de Krita. | `perf/kranative.py` contra `krita --export`. En escena 1 esas 27 capas tienen **un solo keyframe**; los holds de una capa con varios keyframes leída así están **sin confirmar** (habría que probarlo con `kritaKeepsLayers`). |
| 3 | **Copia reducida para Krita:** en lugar de copiar el .kra entero con una capa visible, copiar solo la capa, sus carpetas y sus máscaras, **sin `reference_images/` (33 MB que se cargan siempre), sin `mergedimage.png` y sin los archivos de las otras capas**. | La copia pasa de 67 MB a ≈1 MB. Krita baja de **13,3 a 10,5 s** con secuencia (−21 %) y de **5,7 a 3,4 s** con un frame. Java deja de copiar 63 MB (≈2 s por capa). En total ≈ 29 × 2,8 + 56 × 2 ≈ **−190 s**. | Bajo: cambiar `isolate` y `writeCopy` (quitar nodos y entradas) | **No**: los 123 frames son **idénticos byte a byte** y la imagen suelta también. | `perf/strip.py`, `cmp` de seq2 contra seq3. |
| 4 | **Saber si un dibujo está vacío sin decodificarlo entero:** guardar los bytes del primer PNG vacío de Krita y comparar con `Arrays.equals`. Para los demás, decodificar y cortar al encontrar el primer píxel con alfa. | Hasta **−33 s** (1.618 × 20 ms). | Bajo | No | Medido el costo de `Png.decode` (20,3 ms por dibujo); el ahorro exacto está **sin confirmar**. |
| 5 | **`rpng` con `int[]` en lugar de `ArrayList<Integer>`** (`runs`, `visible`). | ≈ **−13 s** en .ipv (el 13,6 % de 95 s es boxing y crecimiento de listas). | Bajo | No: los mismos números, en un arreglo. | JFR del .ipv. Estimado a partir del perfil, no implementado. |
| 6 | **No volver a decodificar.** El `Planner` guarda unas 24 imágenes (300 MB / 12 MB), así que en el .ipv cada dibujo se decodifica ≈1,9 veces (≈3.090 decodificaciones para 1.618 dibujos). Hay que dimensionar la caché según la memoria libre (el pico es de 2,8 GB) o recorrer los tramos por pista. | ≈ **−30 s** en .ipv | Bajo o medio | No | 63 s de `Png.decode` / 20,4 ms por dibujo. |
| 7 | **Decodificar en paralelo** (prefetch de los planos de cada tramo con `parallelStream`, o un `ExecutorService` del tamaño de los núcleos). | Decodificar los 1.618 dibujos: **32,9 s en 1 hilo, 5,8 s en 16**. En .ipv, del orden de −40 a −50 s junto con el #6 (sin confirmar sobre el total). | Medio (la caché tiene que ser segura entre hilos) | No | `DecBench`. |
| 8 | **Bucle de filtros PNG especializado** (un bucle por tipo de filtro, sin copiar cada fila). | Decodificar todo: **33 → 25 s** (−24 %). | Bajo | **No: 0 distintos de 1.618** | `FastDec`. zlib (`Inflater`) es 12,4 s y no se puede bajar con este cambio. |
| 9 | **`ClipWriter.shrink`:** precalcular por columna y fila los límites `x0/x1` y `y0/y1` (hoy hace divisiones `long` por cada píxel). | Parte del 23 % de 302 s (≈69 s); el ahorro real está **sin confirmar**. | Bajo | No, si se usan los mismos límites | JFR del .clip. |
| 10 | **Dos Kritas a la vez como máximo**, y solo junto con el #3. | 1 instancia: 10,8 s por capa; **2: 9,4 s (−13 %)**; 4: 9,2 s. | Bajo (subir el `Semaphore` de 1 a 2) | No | Prueba con 1, 2, 4 y 8 instancias de la copia reducida. **Riesgo:** con 8 a la vez una instancia **terminó sin exportar nada** y su `krita.com` quedó colgado sin hijo. Hoy el conversor esperaría ahí 10 minutos. Escala poco porque cada Krita ya usa 7 núcleos. |
| — | Otro formato de salida de Krita (BMP, TIFF, PPM) | 10,4 s con PNG; 10,0 (BMP), 9,6 (TIFF), 15,2 (PPM) | — | — | **No vale la pena:** 1 GB por capa en disco para ahorrar menos de 1 s. |
| — | Un solo Krita con Python (`kritarunner`) que abra el documento una vez y exporte todas las capas | — | — | — | **No funciona en esta instalación:** el Python de `kritarunner` no encuentra su biblioteca estándar, y con `PYTHONHOME` tampoco importa el script (el `python313._pth` ignora `PYTHONPATH`, y la carpeta `pykrita` del usuario tampoco funcionó). Ahorraría la carga por capa, pero obligaría a instalar archivos en el perfil de Krita del usuario. |

**Estimación para escena 1 (.kra → puente)**, combinando #2, #3 y #4: 27 capas sin Krita (≈0) + 29 × 10,5 s ≈ 305 s + Java ≈ 10 s ≈ **320 s, contra 744–988 s**. Con el #10 bajaría a ≈280 s. La suma está **sin confirmar**: cada parte se midió por separado.

## Conversiones simultáneas: qué se comparte

- **No hay estado compartido peligroso en `core`.**
  - `Blend` (`TO_CLIP`, `FROM_CLIP`, `TO_IBIS`, `FROM_IBIS`) y `Binc.SIZES` solo se llenan en su bloque `static`.
  - `Composite.CLIP_MODE` es un `ConcurrentHashMap` con `computeIfAbsent`.
  - `Bridge.GSON` (Gson) se puede usar desde varios hilos.
  - La caché del `Planner` es de cada instancia.
  - `KritaConverter` ya serializa Krita con `Semaphore(1)`.
  - La carpeta temporal de Krita (`krita_capa.kra`, `krita_export_N`) está dentro del puente de cada conversión.
- **Riesgos que sí hay:**
  1. **Memoria:** cada conversión usa entre 2,8 y 3,5 GB, y `MainApp` permite `MAX_AT_ONCE = 3` → **≈10 GB**. `run.bat` no pasa `-Xmx`, y el heap por defecto es 1/4 de la RAM (8 GB aquí, 4 GB en una PC de 16 GB). Tres conversiones grandes pueden terminar en `OutOfMemoryError`. Conviene un `-Xmx` explícito o limitar el número de conversiones según `Runtime.maxMemory()`.
  2. **Mismo destino:** dos conversiones al mismo archivo de salida usan el mismo `salida.tmp` (`Orchestrator`, líneas 67 y 82) y se pisan. Conviene un `.tmp` único (por ejemplo con el UUID del puente).
  3. **Krita colgado:** si `krita.com` o `krita.exe` se cuelgan, el semáforo queda tomado hasta 10 minutos (`waitFor(10, MINUTES)`) y bloquea **todas** las conversiones de .kra. Hay que llamar siempre a `krita.exe` directo (el colgado que vi fue el envoltorio `krita.com`), y poner un límite según el tamaño (por ejemplo 3 minutos por capa) que también mate el árbol de procesos.
  4. **Configuración de Krita:** varias instancias comparten `%APPDATA%\krita` (por ejemplo `resourcecache.sqlite`). Es la sospecha principal para la instancia fallida con 8 a la vez (**sin confirmar**).
  5. **Android:** `MainActivity` usa siempre `cache/temp_seq` y `cache/output/resultado.ext`. Como trabaja con un solo hilo no se pisa, pero **el puente nunca se borra** (`Orchestrator(path)` deja `ownsBridge = false`), así que la caché del teléfono se queda con los PNG de la última conversión (cientos de MB en una escena grande).

## Auditoría de limpieza (ordenada por beneficio)

1. **`flatten` completo para la miniatura** (`ClipConverter`:46, `KritaConverter`:279). Es el #1 de arriba: un costo de 106 s que no aporta nada.
2. **`Converter.flatten(m, bridge, tracks)` con varias pistas ya no se usa.** El único llamador (`ClipConverter`:61) pasa una sola pista, así que la rama que decodifica y combina (`Converter`, líneas 83–89) es código muerto. Se puede reducir a "los dibujos de una pista con sus huecos".
3. **`Converter.over` solo envuelve a `Composite.over`** (`Converter`:130). Borrarlo y llamar a `Composite.over`.
4. **`Converter.bridge` (campo) no se usa:** todos los métodos reciben el puente como parámetro y lo tapan. Confunde y obliga a pasar el puente dos veces. Lo mejor es quitar el campo y los `super(bridge)`, o quitar el parámetro de los métodos (es un cambio de API para los tests).
5. **`blank()` y `read(File)` están duplicados** en `KritaConverter` (418/424) y `PngSequenceConverter` (92/97). Conviene una sola versión en `Png`/`Bridge`, que además es donde iría el #4.
6. **`SequenceZipConverter` solo sirve para leer los ZIP puente de versiones viejas** (`ClipConverter` e `IbisConverter` heredan de él por `super.extract`). Si ya no quedan archivos así, se pueden borrar la clase y las dos ramas. Si se quedan, falta un test que lo cubra.
7. **`System.err` como registro:** el avance ya pasa por `Bridge.progress`, pero las advertencias de `Blend`, `ClipConverter` y `KritaConverter` van a `System.err`, que la ventana no muestra. Que pasen por el mismo canal que el avance, para que la persona vea "esta capa se omitió".
8. **`IbisConverter` (585 líneas) mezcla lector, escritor, RPNG y la plantilla `META_TAIL`.** Separar por tarea (`IbisReader`, `IbisWriter` y `Rpng` con `rpng`/`place`) lo haría más fácil de probar. No es urgente.
9. **Rutas fijas:** `KritaConverter.kritaExecutable` usa `C:\Program Files\Krita (x64)` (está bien como respaldo de `KRITA_PATH`). Android fija `temp_seq` (ver el riesgo 5).
10. **Atajos:** todos llevan su comentario `shortcut:` con el límite (`Composite`, `ClipConverter` ×3, `ClipWriter`, `IbisConverter` ×3, `Png`, `Sqlite`, `SqliteWriter`). No encontré atajos sin documentar.
11. **Errores ignorados:** los tres `catch` que ignoran (`Orchestrator`:52 al limpiar la carpeta temporal, `KritaConverter`:398 al vaciar la salida de Krita, `MainActivity`:170 al borrar un documento vacío) tienen su motivo escrito y no pierden datos.

## Qué no medí

- La suma de todas las mejoras en una sola conversión. Cada una se midió aparte.
- Una capa animada de varios keyframes leída sin Krita: escena 1 no tiene ninguna sin máscaras.
- `.clip → puente` y la lectura de `.ipv` (23–37 s según Albañil): no los perfilé.
- El ahorro real de los puntos #4, #5, #6, #7 y #9 dentro de una conversión completa: sale del perfil, no de una implementación.
- Android: nada (ni tiempos ni memoria en el teléfono).

Los scripts y arneses están en el scratchpad de Arquitecto, en la carpeta `perf` (`strip.py`, `kranative.py`, `pngdec.py`, `java/bench/*.java`). Son solo de esta sesión.
