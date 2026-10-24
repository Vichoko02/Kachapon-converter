# Versiones del formato .ipv (para el modo legacy)

Fuente: `libibispaint.so` de ibisPaint **14.1.2** x86_64 (`bin/apks/ibispaint/split_config.x86_64.apk`), con símbolos, desensamblado con capstone; textos de `base.apk` (`assets/lang/en.txt`, `es.txt`). Solo hay **un** binario: todo lo que se dice de apps más viejas se deduce de cómo el lector de la 14.1.2 maneja los archivos viejos y queda marcado **sin confirmar** cuando no se puede comprobar. Las direcciones son de esa `.so`.

## Muestra real de la 14.0.0: dónde están los 21 bytes

Comparé la cola de MetaInfo de una muestra real hecha con **ibisPaint X ver.14.0.0** (un proyecto personal en Descargas que no se copia al repo) con nuestra plantilla `META_TAIL` (14.1.2). Parseé las dos con el orden exacto de `write*` de `MetaInfoChunk::serializeClassSpecifics` (`0x25f2e90`), y en las dos todos los subchunks cierran bien (largo y trailer). La muestra mide 755 bytes y la plantilla 776, así que la plantilla tiene **21 bytes más**. Se explican así:

| Bloque | 14.0.0 | 14.1.2 | Diferencia | Qué es |
|---|---|---|---|---|
| Textos del autor/obra (5 textos tras el campo 11) y el texto antes del penúltimo subchunk opcional | con contenido | vacíos (plantilla limpia) | −61 | **Datos**, no formato. |
| Subchunk `0x30000900` (segunda lista de subchunks) | 21 bytes | 49 bytes | +28 | **Datos**: en la plantilla trae un subchunk anidado `0x30000B00` con 4 floats (un rectángulo de panel guardado) y la muestra no. La estructura es la misma. |
| **Subchunk de animación `0x01000604`** | 40 bytes | 72 bytes | **+32** | **Formato nuevo en la 14.1.x:** después de los 7 enteros, los 2 colores y el entero del frame actual, la 14.1.2 agrega el número de pistas (`0`), un double, float `0.2`, float `1.0`, int `1`, float `0` e int `0x80000000`. En el lector todo eso es opcional: `readSubChunk` con `TrackInfoSubChunkFactory` y después `canRead` en `0x25c5c94`…`0x25c5d5c`. Es el lugar de las **varias pistas de dibujo**, que nosotros no usamos. |
| **Final de MetaInfo** | termina en el tercer subchunk opcional | 22 bytes más | **+22** | **Formato nuevo en la 14.1.x:** un texto vacío, un array de int vacío, una lista de subchunks vacía, dos enteros `0` y otro array vacío (`0x25f34b9`…`0x25f36cd`). La 14.0.0 no los escribe y la 14.1.2 los lee solo si quedan bytes. |
| Total | | | **+21** | −61 + 28 + 32 + 22 = 21 |

En los valores, además de los datos personales y el tamaño del lienzo:

- **La versión aparece en cuatro lugares.** Tres son el formato de 4 cifras: el primer entero de MetaInfo, el `short` del inicio de edición (`0x10000 + versión`) y **un tercer entero en la cola, el byte 606 de `META_TAIL`** (`MetaInfoChunk+0x21c`, el entero justo antes del ancho, 1400 frente a 1410). El cuarto son los dos `NeedVersion` (140000 frente a 140100). Cuando se cambie la versión hay que cambiarla en los cuatro.
- **Otros dos campos difieren, pero no parecen depender de la versión:** el campo 14d vale 0 en la muestra y 1 en la plantilla, y el campo 16i (justo después de los tres enteros que siguen al ancho y el alto) vale un número grande en la muestra y 0 en la plantilla. Son datos que no identifiqué (**sin confirmar**).
- **Capas, RPNG (cabecera de 45 bytes) y formato de imagen 4** son iguales en la 14.0.0, como ya había visto Albañil.

**Consecuencia:** todo lo que escribimos (carpetas de frame, varias capas por frame, modos de fusión, opacidad) **ya existía en la 14.0.0, con los mismos bytes**. Lo único que la 14.1 agrega a la cola son campos opcionales que no usamos.

## Las tres versiones que hay en un .ipv

| Campo | Dónde | Formato | Qué hace la app con él |
|---|---|---|---|
| **Versión del formato** | MetaInfo (0x1000600), primer entero → `MetaInfoChunk+0x1c` | app × 100: `1410` = 14.1.0 | El lector decide con ella qué campos leer (cortes en 210, 250, 302, 340, 370, 410, 419). El reproductor/restaurador/subida se niegan si es **mayor que 1410** (`Canvas::startup` `0x1a18ee6`: `cmp r13d, 0x582; jg` → alerta `VectorPlayer_NewFileVersion` / `Restorer_NewFileVersion` / `Uploading_NewFileVersion`, texto: "es más reciente que la versión admitida, actualiza la aplicación"). Al guardar, la app la sube a `max(v, 1410)` (`PaintVectorFile::updateFormatVersion` `0x2810040`). |
| **NeedVersionForPlay** | MetaInfo, `+0x1ac` = **byte 60 de `META_TAIL`** | 6 cifras: `140100` = 14.1.0 | Galería: si `versión de la app < NeedVersionForPlay` → `displayLowerVersionErrorAlert` ("Para reproducir esta obra, se requiere ver. X") (`ArtListView::onCanvasViewSucceedOpenIpvFile` `0x167eefb`; también `YuifyArtProvider::validateArtworkForExport` `0x2918258`). |
| **NeedVersionForEdit** | MetaInfo, `+0x1b0` = **byte 64 de `META_TAIL`** | 6 cifras: `140100` | Igual al abrir para **editar** (modos 0 y 3, `0x167ec1e`) → "Para editar esta obra, se requiere ver. X". Si faltan en el archivo valen `10000` (1.0.0) (`0x25f49d4`). La app las sube a ≥ 140100 al guardar (`pmaxsd` con la constante `{140100, 140100}` en `0xea9840`). |

Conclusión: **lo que bloquea abrir el archivo en una app vieja son NeedVersionForEdit/Play (140100)**, no los números del inicio de edición ni la cabecera RPNG.

## A. Archivo más nuevo o más viejo que la app

- **Más nuevo** (comprobado en la 14.1.2):
  - Si `NeedVersionForEdit/Play` es mayor que la versión de la app, la galería se niega a editar o reproducir y pide actualizar.
  - Si la versión del formato es mayor que 1410, se niegan el reproductor, el restaurador y la subida. Editar no mira este número, solo `NeedVersionForEdit`.
  - Ninguna de las dos se ignora en silencio.
- **Más viejo:** lo acepta. Los campos que no están se rellenan con valores por defecto, por dos mecanismos:
  - comparaciones con la versión del formato (`cmp [r12+0x1c], 0xd2/0xfa/…` en `MetaInfoChunk::deserializeClassSpecifics` `0x25f3ed0`);
  - `ChunkInputStream::canRead*`, que lee el campo solo si todavía quedan bytes en el chunk.

  Al guardar, la app sube las tres versiones a la suya.
- **Partes desconocidas:** un subchunk de un tipo que no conoce se salta hasta el final de su chunk (`readSubChunk<T>`: `startReadChunk`, compara el tipo y si no es el esperado va a `endReadChunk`; ejemplo en `0x2480c30`). Los campos que sobran al final de un chunk tampoco se leen, porque cada chunk lleva su largo.

## B. Cada campo que escribimos

| # | Campo (lo que escribe `IbisConverter.build`) | Versión mínima | Evidencia | Diseño antes de esa versión |
|---|---|---|---|---|
| 1a | MetaInfo: versión `1410` | El lector de la 14.1.2 solo tiene cortes hasta **419**. Cualquier valor de 419 a 1410 hace que lea **exactamente los mismos bytes**. | Lista completa de cortes en `0x25f3ed0`: 210, 250, 302, 340, 370, 410, 419. Lo posterior se agrega con `canRead`, no con la versión. | Con menos de 419, la 14.1.2 dejaría de leer campos que nosotros sí escribimos y se desalinearía. Con menos de 211 también cambian los textos del principio (`serializeClassSpecifics` `0x25f2ece`: `cmp [rbx+0x1c], 0xd3`). |
| 1b | MetaInfo: `META_TAIL` | Es el diseño de la 14.1.2. Los campos nuevos van al final y se leen con `canRead`. | Muchos `canRead`/`canReadString` después de los cortes en `0x25f3ed0`. | Una app vieja lee el principio que conoce e ignora el resto (**sin confirmar**: no hay binario viejo). |
| 1c | `META_TAIL` bytes 60 y 64: `140100` | Esto es lo que **exige la versión**. | Ver la tabla de arriba. | Si faltan, valen 10000. |
| 2a | Inicio de edición (0x1000200): `0x00010582` | Ninguna comprobación al abrir | `StartEditChunk::deserializeClassSpecifics` `0x2614d60` lo lee como dos `short`: `1` y `1410` (`+0x1c`). | — |
| 2b | Textos "ibisPaint X" / "ver.14.1.2" / "KachaponConverter" | Ninguna al abrir | "ver.14.1.2" solo lo interpreta `StartEditChunk::getAppVersionNumber` `0x26ecf20` (cada número ×100), y lo usan solo `VectorPlayer::*` y `fixDamage*`. Nosotros no escribimos historial. | — |
| 3a | Capa/carpeta (0x3000402/0x3000403): id, byte `1`, float de opacidad, entero de fusión, entero | **Base**: se leen siempre, sin `canRead` | `LayerSubChunk::deserializeClassSpecifics` `0x2535d40` (readInt, readByte, readFloat, readInt `+0x2c`, readInt `+0x50`) | Era así desde el principio (deducido: es la parte que no está protegida). |
| 3b | Capa: `MIN_VALUE`, nombre, `-1`, `flag2`, padre, `0`, frame actual, duración | Agregados después (cada uno con `canRead`) | Mismo lector: `+0x30`, nombre, `+0x54`, `+0x24` (`flag2`, donde va `0x200` = carpeta de frame), `+0x58` (padre), subchunk opcional, `+0x68`, `+0x6c` (frames) | Una app sin estos campos los ignora: sin nombre, sin carpetas ni animación (**sin confirmar**). |
| 4a | Imagen (0x1000500): `int 1`, byte `4`, byte `0` | Sin cortes | `ImageChunk::deserializeClassSpecifics` `0x25e6ec0`: el `int 1` se lee como **4 bytes sueltos**, y después vienen 2 bytes más y los datos. | — |
| 4b | RPNG: cabecera de 45 bytes, banderas `3` | **41** es el diseño viejo y 45 agrega el color de transparencia (bandera `2`) | `glape::ImageIO::loadRlePng` `0x2c89c77`: `cmp headerSize, 0x2d`. Con ≥ 45 lee el color, si no lo deja en `0xffffff`, y salta `headerSize − 45` o `headerSize − 41`. El byte de versión **tiene que ser 0** (si no: "Unknown version … of RLEPNG. Supported versions are at most …", `0x2c899e4`). | Sin el entero del color. |
| — | Lienzo (0x1000100) | Base: tiempo, ancho, alto, id. El byte `1` final va con `canRead`. | `AddCanvasChunk::deserializeClassSpecifics` `0x25c47e0` | Sin el byte final. |
| — | Animación (0x1000604, dentro de `META_TAIL`) | Base: 7 enteros, 2 colores, 1 entero y las pistas. Lo demás con `canRead`. | `AnimationSettingsSubChunk::deserializeClassSpecifics` `0x25c5b50` | Una app sin animación salta el subchunk entero (ver A). |

## C. Desde qué versión existe cada cosa

- **Carpetas de frame (animación):** la función de animación llegó en la **11.0.0** según las notas de versión publicadas en APKMirror ("Added Animation function"). Es **fuente web, no el binario**. En el formato, todo lo de animación (`flag2 & 0x200`, duración de cada frame, el subchunk 0x1000604) va en campos opcionales, así que un archivo leído por una app sin animación mostraría las carpetas como carpetas normales (**sin confirmar**).
- **Varias capas por carpeta de frame:** el formato no tiene un campo aparte para esto (cada capa solo apunta a su padre), así que no hay versión de formato que lo habilite. Si la animación de la 11.0.0 ya lo admitía está **sin confirmar**. En la 14.1.2 funciona (probado en el teléfono).
- **Modos de fusión:** el entero de fusión es parte de la base de la capa (existió siempre). **Sin confirmar** qué ids son más recientes: la 14.1.2 no guarda en qué versión apareció cada modo. Lo prudente para el modo legacy es usar solo 0 (normal) o aplanar las capas con fusión (el `Planner` ya puede fundir).
- **Opacidad como float:** es parte de la base de la capa (el tercer campo, siempre presente): existe desde la primera versión del diseño.

## D. Versión mínima de MetaInfo

- **Sin cambiar nada más:** cualquier valor entre **419 y 1410**. La 14.1.2 lee los mismos bytes con 419 que con 1410, porque no tiene cortes por encima de 419 (comprobado en `0x25f3ed0`).
- **Por debajo de 419** habría que **reescribir `META_TAIL`**, quitando los campos de cada corte que quede por encima de la versión (y por debajo de 211 cambian también los textos del principio). No vale la pena: no hace que ninguna app más vieja abra el archivo.

## E. ¿Cambiar solo el número sirve?

- **Subir el número** (por encima de la app): nunca ayuda. Una versión de formato mayor que la de la app bloquea reproducir, restaurar y subir, y un NeedVersion mayor bloquea editar o reproducir. Es justo lo que hay que evitar.
- **Bajar el número** sin cambiar el diseño (NeedVersion, y la versión del formato dentro de 419–1410): la 14.1.2 lo acepta igual (comprobado en el lector). Una app vieja pasa la comprobación de la galería y lee el principio que conoce, saltando lo nuevo. Que eso alcance para mostrar la animación depende de que esa app ya tuviera animación (≥ 11.0.0, **sin confirmar** en el teléfono).

## Recomendación concreta para el modo legacy

Hay dos niveles. El primero está respaldado por una muestra real; el segundo no.

### Nivel 1 (recomendado): el diseño exacto de la 14.0.0

Es el que tiene evidencia completa: escribir la cola como la escribe la 14.0.0. Es nuestra plantilla con estos cambios:

| Qué | Hoy (14.1.2) | Legacy 14.0 |
|---|---|---|
| MetaInfo, primer entero | `1410` | `1400` |
| `META_TAIL` bytes 60–67 (NeedVersionForPlay y NeedVersionForEdit) | `140100`, `140100` | `140000`, `140000` |
| `META_TAIL` bytes 606–609 (la tercera versión, `+0x21c`) | `1410` | `1400` |
| Subchunk de animación (en `META_TAIL`: número de subchunks en el 648, tipo `0x01000604` en el 652, largo en el 656, cuerpo desde el 660 y trailer en el 732) | largo `72`, trailer `-80` | **largo `40`, trailer `-48`**, y **se borran los bytes 700–731** (32 bytes, el final del cuerpo); el trailer pasa al 700 |
| Final de `META_TAIL` | 776 bytes | **se borran los últimos 22 bytes** (754–775) |
| Inicio de edición | `0x00010582`, `"ver.14.1.2"` | `0x00010578`, `"ver.14.0.0"` |

Los offsets que parchea el código (`TAIL_WIDTH` 610, `TAIL_HEIGHT` 614, `TAIL_FPS` 664, `TAIL_CURRENT_FRAME` 696) **no se mueven**, porque los dos recortes quedan después del 700. Queda una cola de 722 bytes con la misma estructura que la de la muestra real (lo comprobé: armé esa cola y la parseé con la gramática del serializador; todos los subchunks cierran bien y termina en el mismo campo que la 14.0.0). La 14.1.2 la lee porque es así como lee las obras hechas con la 14.0.0. Capas, imágenes y RPNG no cambian.

### Nivel 2 (sin confirmar): apuntar a la 11.0.0

Objetivo: ibisPaint **11.0.0**, la primera con animación según APKMirror. Se aplica encima del nivel 1, cambiando solo números: no hay ninguna muestra anterior a la 14.0.0 para comparar el diseño.

| Qué | Hoy | Modo legacy |
|---|---|---|
| MetaInfo, primer entero (versión del formato) | `1410` | **`1100`** (dentro de 419–1410: misma lectura en la 14.1.2, y no supera la versión de una app 11.x) |
| `META_TAIL` bytes 60–63 (NeedVersionForPlay) | `140100` | **`110000`** |
| `META_TAIL` bytes 64–67 (NeedVersionForEdit) | `140100` | **`110000`** |
| `META_TAIL` bytes 606–609 (la tercera versión) | `1410` | **`1100`** |
| Inicio de edición: `short` 1 + `short` versión | `0x0001`, `0x0582` | `0x0001`, **`0x044C`** (1100). No es necesario, solo para que sea coherente. |
| Inicio de edición: texto de versión | `"ver.14.1.2"` | **`"ver.11.0.0"`** (solo es cosmético, nadie lo valida al abrir) |
| RPNG: largo de cabecera / banderas / color | `45` / `3` / `0` | **`41` / `1` / sin el entero** (el diseño viejo; la 14.1.2 lo acepta y el color solo afecta el RGB de los píxeles transparentes) |
| Byte de versión de RPNG | `0` | `0` (obligatorio) |
| Modos de fusión | ids de `Blend.toIbis` | **solo `0`**: fundir el resto con el `Planner` (**sin confirmar** qué ids conoce la 11.x) |
| Capas, imagen y `META_TAIL` aparte de esos 8 bytes | sin cambios | sin cambios |

**Cómo probarlo:**
1. En la 14.1.2 (PC o teléfono), el archivo legacy tiene que abrir y reproducirse igual que el actual.
2. La prueba de verdad es abrirlo en una app más vieja (un teléfono con ibisPaint < 14.1, idealmente 11.x–13.x). No tengo binarios ni APK viejos, y no descargué ninguno.
