# Limpieza del código

Lista comprobada contra `main` en `565bfc4`. Va ordenada por beneficio. Cada punto dice qué tocar, cómo saber que quedó bien y si cambia algún archivo de salida.

**Regla para todos los puntos:** `./gradlew :core:test --offline` en verde, y que la ventana (`run.bat`) y Android (`:android:compileDebugJavaWithJavac`) compilen. Si un punto puede cambiar la salida, se compara con escena 1 contra el commit anterior (`Kc build|extract|compare`, como en `docs/RENDIMIENTO.md`).

## 1. Un solo .ipv (el legacy) — **corrige una diferencia real**

**Problema:**
- La ventana escribe el .ipv legacy (`MainApp`: `.legacyIbis(true)`).
- **Android escribe el .ipv normal (14.1)**, porque `Orchestrator.legacyIbis` vale `Boolean.getBoolean("kachapon.ibis.legacy")`, que es `false`.
- El mismo origen da archivos distintos según la app, y se decidió que haya un solo .ipv.

**Qué hacer:**
- **`IbisConverter`:** quitar el parámetro `legacy` y dejar siempre el camino legacy:
  - `FORMAT_VERSION_LEGACY`;
  - `legacyTail(tail)`;
  - `rpng(..., true)`: cabecera de 41 bytes, banderas 1 y sin el entero de color;
  - el texto `"ver.11.0.0"`.

  Se borran `FORMAT_VERSION` 1410 y las ramas del normal. Los constructores quedan en `IbisConverter(bridge)` e `IbisConverter(bridge, keepAnimated)`.
- **`Orchestrator`:** borrar el campo `legacyIbis`, el método `legacyIbis(boolean)`, la propiedad `kachapon.ibis.legacy` y `converterFor(ext, bridge, legacyIbis)`. Queda `converterFor(ext, bridge)`.
- **`MainApp`:** quitar `.legacyIbis(true)`.
- **Tests:** los que pasan `legacy` o comparan normal contra legacy (hay 21 líneas con "legacy" en `core/src/test`) quedan con un solo modo. El que compruebe la cabecera tiene que esperar 41.
- **`docs/IBIS_VERSIONES.md`:** agregar una línea al principio: "desde `565bfc4+1` solo se escribe el nivel legacy".

**Cómo verificar:**
- Con escena 1, el .ipv nuevo tiene que ser **idéntico en contenido** al legacy de `565bfc4` (los dos tardaron lo mismo, 51–52 s, y dieron las mismas 1.362 capas).
- Android tiene que compilar.

**¿Cambia la salida?** En la ventana no. En Android sí: pasa del .ipv normal al legacy, que es lo buscado.

## 2. `Converter`: código muerto y envoltorios

- **`Converter.over` (línea 145) solo llama a `Composite.over`:** se borra. Su único llamador (línea 103) pasa a usar `Composite.over`.
- **`flatten(m, bridge, tracks)` (línea 81):** el único llamador (`ClipConverter`:59) le pasa **una sola pista**, así que la rama que combina varias (`Png.decode` + `over` + `Png.encode`, líneas 99–105) no se ejecuta nunca. Se reemplaza por `drawings(m, bridge, track)`: los dibujos de una pista con sus huecos (el PNG vacío) y los holds, sin decodificar nada.
- **Cómo verificar:** con escena 1, el .clip tiene que dar contenido **idéntico** contra `565bfc4`.

## 3. Funciones duplicadas

- **`blank(byte[])` y `read(File)`** están dos veces, en `KritaConverter` (líneas 548 y 554) y en `PngSequenceConverter` (92 y 97). Quedan como `Png.blank(byte[])` y `Bridge.readFile(File)` (este último ya existe como privado: se hace público) y se borran las copias.
- **¿Cambia la salida?** No, es el mismo código. Basta con los tests.

## 4. Archivos que sobran en la raíz (se borran con `git rm`)

| Archivo | Por qué sobra |
|---|---|
| `ConvertAndSave.java`, `TestKraToClip.java` | Pruebas de una sesión, con rutas fijas de tu PC (`Downloads\...`, `temp_seq`). Gradle no las compila. |
| `config/settings.json` | Ningún código lo lee (solo lo nombran el README y `setup_ibis_container.sh`). |
| `hooks/ibis_extractor.js`, `hooks/clip_extractor.js` | Marcadores vacíos de Frida. Ese camino se abandonó: hoy se leen los formatos directamente. |
| `docker-compose.ibis.yml`, `setup_ibis_container.sh`, `bin/ghidra/Dockerfile.ibis` | Para el contenedor de ibisPaint, que también se abandonó. |
| `setup_rea_agents.sh` | Configuración de herramientas de análisis, no del programa. |
| `estructura_proyecto.txt` | Lista de archivos desactualizada (habla de `temp_seq` y de archivos que ya no existen). |

En `.gitignore`, las líneas `temp_seq/` y `temp_seq2/` se pueden dejar: no molestan y protegen carpetas viejas que puedan quedar en otros equipos.

## 5. Documentación desactualizada

- **`Bridge.java`, javadoc de la clase:** dice "Puente universal (temp_seq/)" y "`[pista]_[layerId]_[frame].png`". Hoy es una carpeta temporal propia por conversión y los nombres son `pista_0001.png`.
- **`ARQUITECTURA_PIPELINE.md`:** habla de hooks de Frida, del ZIP puente para .clip/.ipv y de `temp_seq/`. Hay que reescribirlo con el flujo actual:
  - lectores nativos (Krita con lectura directa de teselas y aislando capas, Clip Studio por su SQLite, ibisPaint por sus chunks);
  - `Planner` y `Composite` para combinar capas;
  - escritores nativos.
- **`README.md` línea 112:** borrar la frase de los scripts de Frida.

## 6. Decidir: leer el ZIP puente de versiones viejas (`SequenceZipConverter`)

`ClipConverter` e `IbisConverter` heredan de él solo para leer los .clip/.ipv que eran un ZIP con el puente adentro, de las primeras versiones del programa. Ningún test lo cubre.
- **Si ya no hay archivos así:** se borra la clase, los dos conversores heredan de `Converter`, y el mensaje para un archivo no nativo pasa a ser "no es un .clip/.ipv válido".
- **Si se mantiene:** falta un test que lea un ZIP puente.

Esta decisión la tienes que tomar tú.

## Fuera de la limpieza: el bug que queda

Dos conversiones al **mismo** archivo de destino comparten `salida.tmp` (`Orchestrator`, líneas 67 y 82) y se pisan. La ventana lo evita con `activeOutputs`, pero Android y cualquier otro uso del `core` no. Se arregla con un `.tmp` único (por ejemplo `salida.<uuid>.tmp`) y moviéndolo al final como ahora.

## Lo que no conviene tocar ahora

Nada pendiente en esta lista: `IbisConverter` ya se separó en lector (`IbisConverter`) y escritor (`IbisWriter`).
