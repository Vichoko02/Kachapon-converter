# Arquitectura del Motor de Conversión Indie (Worker Headless)

Este documento define la infraestructura para convertir proyectos de animación entre **ibisPaint (`.ipv`)**, **Clip Studio Paint (`.clip`)** y **Krita (`.kra`)** preservando capas, estructura de carpetas (cels) y tiempos de exposición (X-Sheet / Timeline).

---

## 1. Visión General del Sistema

El sistema utiliza una **Arquitectura de Puente Universal Intermedio** dividida en tres etapas:

1. **Ingesta y Extracción:** El archivo entrante se abre y sus frames de imagen se descomprimen al puente universal. Para `.kra` se lee el ZIP nativo con `maindoc.xml`. Para `.clip`/`.ipv` se lee el contenido puente ZIP si existe; de lo contrario se requiere la app nativa o hooks de memoria (Frida).

2. **Puente Universal Intermedio (`temp_seq/`):** Estado neutro compuesto por:
   - `manifest.json`: resolución, FPS, árbol de pistas y duración de cada fotograma (holds).
   - `frames/`: secuencia de imágenes `.png` nombradas por `[pista]_[frame].png`.
   - `timesheet.xdts`: hoja de tiempos estándar Toei/Celsys para sincronización.

3. **Reconstrucción:** El motor destino toma el puente y empaqueta el archivo final en el formato elegido.

---

## 2. Topología de Componentes

```
                        +---------------------------------------+
                        |   Interfaz GUI (JavaFX Desktop)       |
                        |   o App nativa (Android)              |
                        +-------------------+-------------------+
                                            |
                                            v
                        +---------------------------------------+
                        |     Orquestador Central (Java)        |
                        +-----+-------------+-------------+-----+
                              |             |             |
         +--------------------+             |             +--------------------+
         |                                  |                                  |
         v                                  v                                  v
+-------+---------------+          +-------+---------------+          +-------+---------------+
| MOTOR 1: KRITA (.kra) |          | MOTOR 2: CLIP (.clip) |          | MOTOR 3: IBIS (.ipv)  |
| - ZIP nativo + XML    |          | - ZIP puente          |          | - ZIP puente          |
| - maindoc.xml         |          | - manifest + frames   |          | - manifest + frames   |
| - 100% Nativo Java    |          | - timesheet.xdts      |          | - timesheet.xdts      |
+-------+---------------+          +-----------------------+          +-----------------------+
         |                                  |                                  |
         +--------------------+             |             +--------------------+
                              |             |             |
                              v             v             v
                        +---------------------------------------+
                        |       PUENTE UNIVERSAL TEMPORAL       |
                        |  [PNG Sequence] + [manifest.json]     |
                        |  + [timesheet.xdts]                   |
                        +---------------------------------------+
```

---

## 3. Módulos del proyecto (Gradle)

| Módulo | Tecnología | Propósito |
|--------|-----------|-----------|
| `core` | Java 11 (Gson) | Lógica compartida: Bridge, Manifest, Converters, Orchestrator. Compilable en Desktop y Android. |
| `desktop` | JavaFX 21 | Ventana gráfica Windows con drag-and-drop, autodetección y carpeta de salida. |
| `android` | Android SDK (minSdk 24) | App móvil con Storage Access Framework y conversión en hilo worker. |

---

## 4. Formatos soportados

- **Entrada / Salida:** `.kra`, `.clip`, `.ipv`
- **Restricción:** origen y destino no pueden ser iguales.
