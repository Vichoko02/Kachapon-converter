# KachaponConverter

Motor de conversión de animación indie para pasar proyectos entre **Krita (.kra)**, **Clip Studio Paint (.clip)** e **ibisPaint (.ipv)**, además de **secuencias PNG**, preservando capas, tiempos de exposición y estructura de cels.

Toda conversión pasa por un **puente universal** (una carpeta propia por conversión, `kachapon_<uuid>`, dentro de la carpeta temporal del sistema, que se borra al terminar): una secuencia de frames PNG + `manifest.json` + `timesheet.xdts`.

---

## 📦 Versiones disponibles

| Versión | Plataforma | Cómo instalar |
|---------|-----------|---------------|
| **Desktop** | Windows | `run.bat` (doble clic) |
| **Móvil** | Android | Importar módulo `android/` en Android Studio |

---

## 🖥️ 1. Versión Desktop (Windows)

### Requisitos
- **JDK 21 o superior** instalado y en el PATH
  - Descarga: https://adoptium.net/

### Instalación y uso
1. Descarga o clona esta carpeta en tu PC.
2. Haz doble clic en **`run.bat`**.
3. El script verificará Java, compilará el programa y abrirá la ventana gráfica.

### Cómo convertir
1. **Arrastra y suelta** un archivo `.kra`, `.clip`, `.ipv` o un PNG de una secuencia sobre la zona de "Archivo origen" (o haz clic y elígelo con "Examinar...").
2. El formato se **detecta automáticamente**.
3. Elige la **carpeta de salida** donde se crearán los archivos.
4. Elige el destino en el desplegable **Formato destino**.
5. El archivo destino se **autonombra** con el origen y la conversión, para no pisar el original.
   - Ejemplo: `mi_animacion.kra` → carpeta salida → `mi_animacion_KritaToClip.clip`
6. Pulsa **Convertir archivo**. Puedes añadir varias conversiones: cada una tiene su tarjeta con barra de avance.
7. Cada tarjeta tiene un botón **Cancelar**, que también sirve mientras la conversión espera en cola.

### Formatos soportados
- **Entrada:** `.kra`, `.clip`, `.ipv` y secuencia PNG (se elige uno de sus PNG; solo en escritorio)
- **Salida:** `.kra`, `.clip`, `.ipv` y secuencia PNG (carpeta `nombre_Nfps/`; solo en escritorio)
- No se puede convertir un formato a sí mismo (origen == destino bloqueado).

---

## 📱 2. Versión Android

### Requisitos
- **Android Studio** instalado (con SDK Android)
- Dispositivo Android o emulador (API 24 / Android 7.0 o superior)

### Instalación
1. Abre **Android Studio**.
2. Selecciona **"Open"** y elige la carpeta raíz de este proyecto (el módulo `android` depende de `core`).
3. Espera a que Gradle sincronice y descargue las dependencias.
4. Conecta tu dispositivo Android o inicia un emulador.
5. Pulsa **"Run"** (▶) para instalar la app.

### Uso en Android
1. Pulsa **"Elegir archivo"** y elige un `.kra`, `.clip` o `.ipv` de tu dispositivo.
2. El formato origen se detecta automáticamente.
3. Elige el formato destino con los botones de radio (el formato origen se desactiva como destino).
4. Pulsa **"Convertir y guardar…"** y elige dónde guardar el archivo resultante.

---

## 🧠 Arquitectura interna (resumen)

```
Archivo origen (.kra / .clip / .ipv / PNG)
        │
        ▼
  [ Puente universal: kachapon_<uuid>/ ]
  ├─ manifest.json   (resolución, FPS, pistas, frames, holds)
  ├─ frames/         (secuencia PNG RGBA: pista_0001.png)
  └─ timesheet.xdts  (hoja de tiempos estándar)
        │
        ▼
Archivo destino (.kra / .clip / .ipv / PNG)
```

Cada formato se lee y se escribe en su forma nativa (detalle en `ARQUITECTURA_PIPELINE.md`):

- **Krita (.kra):** las capas sin máscaras se leen de los bloques del ZIP; las demás, con Krita por detrás. Se escribe un documento Krita nativo.
- **Clip Studio (.clip):** archivo `CSFCHUNK` con su base SQLite y sus bloques de píxeles; se escribe a partir de una plantilla.
- **ibisPaint (.ipv):** lista de chunks de ibisPaint; se escribe siempre en el formato de ibisPaint 11.
- **Secuencia PNG:** una carpeta con un PNG por frame.

El ZIP del puente de las primeras versiones ya no se escribe; solo se sigue leyendo si llega un archivo antiguo.

---

## 🛠️ Para desarrolladores (Gradle)

El proyecto usa Gradle con 3 módulos: `core`, `desktop`, `android`.

```bash
# Compilar todo
./gradlew build

# Ejecutar desktop
./gradlew :desktop:run

# Compilar Android APK
./gradlew :android:assembleDebug
```

Los JARs de JavaFX y Gson ya están precargados en `libs/` para que `run.bat` funcione sin conexión.

---

## ⚠️ Notas importantes

- **`.clip` reales** de Clip Studio Paint se leen directamente: el árbol de carpetas y capas con su modo de fusión, opacidad y visibilidad; cada carpeta de animación es una pista con sus tiempos y cada capa suelta, una pista de un solo dibujo. Al escribir `.clip` se genera un documento nativo (parte de la plantilla `core/src/main/resources/com/kachapon/core/clip_template.clip`): las capas con varios dibujos pasan a ser carpetas de animación (un cel por dibujo, con su propia curva de tiempos) y las quietas, capas normales, dentro de las mismas carpetas y con los mismos modos de fusión que el origen. Los modos de fusión usan la tabla medida de clipparse (`Blend.java`). Sin el papel del origen. Límite de Clip Studio: la línea de tiempo solo lista las carpetas de animación que están en la raíz; las que quedan dentro de una carpeta normal se animan igual con el cursor de tiempo (y se ven en el panel de capas), pero no tienen fila en la línea de tiempo.
- **`.kra`** se escribe como documento Krita real (una capa animada por pista). El documento se lee por capas: cada capa de pintura es una pista, dentro de su árbol de carpetas. Las capas sin máscaras se leen directamente de los bloques del `.kra`, sin Krita; las demás son una exportación de Krita cada una (`--export-sequence`, unos 10 s por capa con máscara animada), con la máscara ya aplicada en los píxeles, así que para esas capas hace falta Krita instalado (o `KRITA_PATH`).
- **`.ipv` reales** de ibisPaint se leen y se escriben directamente, sin la app: al leer, en cada carpeta de frame las capas y carpetas se conservan y se reconocen por nombre entre un frame y otro, y un mismo dibujo seguido pasa a ser un hold. Se escribe siempre en el formato de ibisPaint 11 (versión 1100, ver `docs/IBIS_VERSIONES.md`): una carpeta de frame por tramo con el árbol completo, sin fundir nada, así que una escena grande pesa mucho porque cada capa se repite en cada frame. Para abrir un `.ipv` generado, cópialo a `Android/data/jp.ne.ibis.ibispaintx.app/files` en el teléfono y abre "Mi galería" en ibisPaint.
- **Secuencia PNG**: como destino crea la carpeta `nombre_Nfps/` con un PNG por frame (holds repetidos, huecos transparentes); como origen se elige uno de sus PNG y se usa toda la carpeta.
- `bin/platform-tools/` y `bin/apks/` están vacíos (placeholders) y fuera de git: ahí van ADB y los APK de ibisPaint si se usan para investigar el formato.

---

## 📄 Licencia

Uso libre para proyectos personales y educativos.
