# Arquitectura del conversor

Convierte animaciones entre **Krita (`.kra`)**, **Clip Studio Paint (`.clip`)**, **ibisPaint (`.ipv`)** y **secuencias PNG**, conservando capas, carpetas, modos de fusión, opacidad, visibilidad y tiempos de exposición (holds).

## Flujo

```
origen ──extract──▶ puente ──build──▶ destino
```

1. **`Orchestrator`** elige el conversor de origen y el de destino según la extensión, crea un puente propio en la carpeta temporal (uno por conversión, para poder hacer varias a la vez), llama a `extract` y después a `build`, informa el avance y los avisos (`ConversionResult.warnings`), y al terminar borra el puente. El destino se escribe primero en un `.tmp` y solo al final reemplaza al archivo existente.
2. **El puente** (`Bridge` + `Manifest`) es el estado neutro:
   - `manifest.json`: resolución, fps, duración, pistas (cada una es una capa con su línea de tiempo), el árbol de capas y carpetas (modo de fusión, opacidad, visibilidad, paso) y cada dibujo con su frame y su hold;
   - `frames/`: un PNG RGBA por dibujo (`pista_0001.png`);
   - `timesheet.xdts`: hoja de tiempos.
3. **Combinar capas** cuando el destino lo necesita: `Planner` resuelve qué se ve en cada tramo de la animación (abre las carpetas que se pueden separar y funde las que no) y `Composite` aplica los modos de fusión. `Blend` traduce los nombres de modos entre Krita, Clip Studio e ibisPaint.

Ningún formato conoce a otro: agregar uno es escribir un `Converter` con `extract` y `build`.

## Conversores (`core/.../converters`)

| Formato | Leer | Escribir |
|---|---|---|
| `.kra` (`KritaConverter`) | Las capas sin máscaras se leen de los bloques LZF del ZIP (`KraTiles`). Las demás, con Krita por detrás: una copia reducida del documento con solo esa capa visible y `--export-sequence`, vigilada con un límite de tiempo. | Documento Krita nativo: una capa de pintura animada por pista, dentro de su árbol de carpetas. |
| `.clip` (`ClipConverter`, `ClipWriter`) | El archivo `CSFCHUNK`: la base SQLite (`Sqlite`) con capas, carpetas y línea de tiempo, y los bloques de píxeles. | A partir de la plantilla `clip_template.clip`: las capas con varios dibujos pasan a ser carpetas de animación (un cel por dibujo) y las quietas, capas normales (`SqliteWriter`, `Binc` para las curvas de tiempo). |
| `.ipv` (`IbisConverter`) | La lista de chunks de ibisPaint: cada carpeta de frame es un dibujo, con su duración. | Siempre en el formato de ibisPaint 11 (`docs/IBIS_VERSIONES.md`): una carpeta de frame por tramo, con las capas que el `Planner` deja separadas. |
| Secuencia PNG (`PngSequenceConverter`) | Una carpeta de PNG en orden. | Una carpeta `nombre_Nfps/` con un PNG por frame. |

## Módulos (Gradle)

| Módulo | Tecnología | Propósito |
|---|---|---|
| `core` | Java 11 + Gson | Todo lo anterior. Compila igual para Desktop y Android. |
| `desktop` | JavaFX 21 | Ventana con arrastrar y soltar, varias conversiones a la vez con su barra de avance y sus avisos. |
| `android` | Android SDK (minSdk 24) | App con Storage Access Framework; convierte en un hilo aparte. |

Más detalle: `docs/RENDIMIENTO.md` (mediciones y verificación) y `docs/IBIS_VERSIONES.md` (formato `.ipv`).
