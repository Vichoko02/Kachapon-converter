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
| 2 | **Leer sin Krita las capas sin máscaras activas** (ni propias ni en sus carpetas): decodificar las teselas LZF de 64x64 del `.kra` (`VERSION 2`, BGRA en planos, el primer byte dice si va comprimida). | **27 de 56 capas** de escena 1 sin abrir Krita: ≈27 × 5,7 s ≈ **−154 s**. El prototipo en Python tarda 0,03–0,7 s por capa; en Java sería menos (sin confirmar). | Medio: un lector de teselas y de `keyframes.xml` | **No** en lo medido: `Layer16` (634 píxeles visibles) y `Capa de pintura 30` (3.291) dan **0 píxeles distintos** frente a la exportación aislada de Krita. | `perf/kranative.py` contra `krita --export`. En escena 1 esas 27 capas tienen **un solo keyframe**. **Varios keyframes, confirmado (25-oct):** `color base` del .kra generado desde escena 1 (73 keyframes, con holds) da **0 píxeles distintos en los 123 frames**. Lo probé en dos versiones: la que guardó Krita (teselas LZF por planos) y la nuestra sin comprimir (`kraseq.py` contra `--export-sequence`). |
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

## Verificación de lo implementado (commits `c2903a5` y `4178a81`)

**Método:**
- **Versiones:** compilé la versión de antes (`4a3d309`) y la actual (`4178a81`) fuera del repo.
- **Escritores:** los cuatro escribieron desde **el mismo puente** de escena 1, y las dos salidas se releyeron con el mismo lector.
- **Lectura del .kra:** `escena 1.kra` se leyó con las dos versiones, una después de la otra.
- **Comparador** (`Kc compare`): pistas, árbol de capas y carpetas (nombre, ruta, visibilidad, opacidad, modo de fusión, paso), cada dibujo con su frame y su hold, y los píxeles. Ignora el RGB de los píxeles totalmente transparentes.
- **Prueba negativa:** se cambia un dibujo del puente y el comparador tiene que decir DIFIERE. Lo dijo.

| Camino | Antes | Ahora | Contenido |
|---|---|---|---|
| puente → .ipv | 73 s | 67 s | **Idéntico** (86 imágenes por frame releídas) |
| puente → .ipv legacy | 74 s | 69 s | **Idéntico** |
| puente → .clip | 224 s | 128 s | **Idéntico** (56 pistas, 68 capas y carpetas, 1.618 dibujos) |
| puente → .kra | 119 s | 37 s | **Idéntico** (56 pistas, 68 capas y carpetas, 1.618 dibujos) |
| `escena 1.kra` → puente | 671 s | 387–400 s | Misma estructura (56 pistas, 68 capas y carpetas, 1.618 dibujos con los mismos frames y holds). Píxeles: 16 dibujos difieren (1.940 píxeles en total) **por el propio Krita** (ver abajo). |
| Contra los 123 frames de Krita | 0,26 % (981.455 píxeles > 3) | 0,26 % (981.775) | Igual. La diferencia es de 320 píxeles en 376 millones. |

**Krita no es determinista en las capas con máscaras de transformación animadas.** Esto se comprobó de tres formas:
- Dos lecturas de escena 1 con **la misma versión nueva** difieren en 6 dibujos, en las mismas capas y frames que entre la versión vieja y la nueva (`color base_2` en los frames 49–54, `Capa de pintura 56` en el 54).
- Dos `--export-sequence` de **la misma copia completa** de `color base` (2ª) difieren en el frame 67 (64 píxeles).
- La copia reducida contra la completa no agrega diferencias propias (0 píxeles distintos en la corrida b).

Por eso **no se puede pedir que .kra → puente sea idéntico byte a byte entre corridas** mientras esas capas pasen por Krita. Las diferencias aparecen y desaparecen de una corrida a otra, en los mismos lugares. La lectura directa de teselas (#2) sí es determinista, pero solo sirve para las capas sin máscaras.

### Lectura directa de teselas (`KraTiles`, 25-oct, sin commit al verificar)

Hice dos extracciones de escena 1 con la lectura directa y las comparé capa por capa (`ByLayer`) contra las dos extracciones anteriores de `4178a81`, y entre sí.

| Comparación | Pistas directas (28) | Pistas por Krita (28) |
|---|---|---|
| directa #1 contra `4178a81` #1 y #2 | **28 idénticas** | 2–3 difieren |
| directa #2 contra directa #1, `4178a81` #1 y #2 | **28 idénticas** | 3 difieren |
| `4178a81` #1 contra `4178a81` #2 (la misma versión dos veces) | 28 idénticas | 3 difieren |

- Las que difieren por Krita son siempre las mismas capas con máscara de transformación animada: `color base_2`, `Capa de pintura 56`, `color 2`, `color_2`. La primera diferencia cae en frames distintos en cada corrida (16, 49, 50, 54, 75, 77, 80). Es el ruido conocido de Krita, no la lectura directa.
- Son 28 pistas y no 27 porque dos capas `kira/Capa de pintura 31` tienen la misma ruta, y las dos salen iguales.
- **Tiempo:** 345–353 s, contra 387–400 s (−45 s). Es menos que los ≈150 s estimados al principio, porque la copia reducida ya había bajado estas capas quietas de 5,7 a 3,4 s cada una.

### Decodificación en paralelo (`db10361`), legacy con modos de fusión y vigilante de Krita (26-oct)

| Cambio | Contra | Contenido | Tiempo | Pico de heap |
|---|---|---|---|---|
| Planner en paralelo (`db10361`) | `7858d61` | **Idéntico** en .ipv, .ipv legacy, .clip y .kra | .ipv 75 → 47 s; legacy 80 → 61 s; .clip 140 → 130 s; .kra 44 → 61 s (*) | 2.560 → 1.843 MB (.ipv); 928 → 977 MB (.clip) |
| Legacy que conserva los modos de fusión (sin commit al verificar) | .ipv normal y legacy de `db10361` | **Idéntico**, y además el mismo archivo: 1.362 capas, 86 carpetas, todas en modo 0 | Normal 52 s, legacy 51–52 s en medición limpia | 1.843 MB |
| Vigilante de Krita (sin commit al verificar) | Krita falso que se cuelga (`KRITA_PATH` → `.cmd` con un hijo `ping`) | — | Corta a los 183 s (60 + 123 frames), reintenta una vez y termina con "Krita se quedó colgado exportando la capa …, dos veces": 369 s en total | No quedan procesos vivos |

(*) El .kra más lento y la diferencia entre normal y legacy de esa corrida coincidieron con los tests de Albañil corriendo en la misma máquina. En la medición limpia, normal y legacy tardan lo mismo. El .kra hay que volver a medirlo.

**En escena 1, conservar los modos de fusión no cambia el archivo:** las capas con multiplicar u otro modo de fusión están dentro de carpetas que no se pueden separar sin cambiar la imagen, así que se componen enteras y llegan a ibisPaint en modo normal. El cambio solo se nota en escenas con capas con modo de fusión sueltas.

## Qué no medí

- La suma de todas las mejoras en una sola conversión. Cada una se midió aparte.
- Una capa animada de varios keyframes leída sin Krita: escena 1 no tiene ninguna sin máscaras.
- `.clip → puente` y la lectura de `.ipv` (23–37 s según Albañil): no los perfilé.
- El ahorro real de los puntos #4, #5, #6, #7 y #9 dentro de una conversión completa: sale del perfil, no de una implementación.
- Android: nada (ni tiempos ni memoria en el teléfono).

Los scripts y arneses están en el scratchpad de Arquitecto, en la carpeta `perf` (`strip.py`, `kranative.py`, `pngdec.py`, `java/bench/*.java`). Son solo de esta sesión.
