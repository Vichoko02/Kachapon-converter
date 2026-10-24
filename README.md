# KachaponConverter

Motor de conversión de animación indie para pasar proyectos entre **Krita (.kra)**, **Clip Studio Paint (.clip)** e **ibisPaint (.ipv)** preservando capas, tiempos de exposición y estructura de cels.

Toda conversión pasa por un **puente universal** (carpeta `kachapon_seq` dentro de la carpeta temporal del sistema): una secuencia de frames PNG + `manifest.json` + `timesheet.xdts`.

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
3. El script verificará Java, compilará el programa si es necesario y abrirá la ventana gráfica.

### Cómo convertir
1. **Arrastra y suelta** un archivo `.kra`, `.clip` o `.ipv` sobre la zona de "Archivo origen" (o haz clic y elígelo con "Examinar...").
2. El formato se **detecta automáticamente**.
3. Elige la **carpeta de salida** donde se crearán los archivos.
4. El archivo destino se **autonombra**: `nombre_base_del_origen.ext_destino`.
   - Ejemplo: `mi_animacion.kra` → carpeta salida → `mi_animacion.clip`
5. Marca el formato destino (se detecta solo al elegir el archivo, pero puedes cambiarlo con los checkboxes).
6. Pulsa **Convertir archivo**.

### Formatos soportados
- **Entrada:** `.kra`, `.clip`, `.ipv`
- **Salida:** `.kra`, `.clip`, `.ipv`
- No se puede convertir un formato a sí mismo (origen == destino bloqueado).

---

## 📱 2. Versión Android

### Requisitos
- **Android Studio** instalado (con SDK Android)
- Dispositivo Android o emulador (API 24 / Android 7.0 o superior)

### Instalación
1. Abre **Android Studio**.
2. Selecciona **"Open"** y elige la carpeta `android/` de este proyecto.
3. Espera a que Gradle sincronice y descale las dependencias.
4. Conecta tu dispositivo Android o inicia un emulador.
5. Pulsa **"Run"** (▶) para instalar la app.

### Uso en Android
1. Pulsa **"Seleccionar archivo"** y elige un `.kra`, `.clip` o `.ipv` de tu dispositivo.
2. El formato origen se detecta automáticamente.
3. Elige el formato destino con los botones de radio (el formato origen se desactiva como destino).
4. Pulsa **"Convertir"**.
5. Elige dónde guardar el archivo resultante.

---

## 🧠 Arquitectura interna (resumen)

```
Archivo origen (.kra / .clip / .ipv)
        │
        ▼
  [ Puente universal: kachapon_seq/ ]
  ├─ manifest.json   (resolución, FPS, pistas, frames, holds)
  ├─ frames/         (secuencia PNG RGBA: pista_frame.png)
  └─ timesheet.xdts  (hoja de tiempos estándar)
        │
        ▼
Archivo destino (.kra / .clip / .ipv)
```

- **Krita (.kra):** ZIP nativo con `maindoc.xml` + `layers/*.png`.
- **Clip Studio (.clip) / ibisPaint (.ipv):** ZIP que empaqueta el puente universal (manifest + frames + timesheet).

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
- **`.kra`** se escribe como documento Krita real (una capa animada por pista). Para **leer** `.kra` se usa Krita por detrás (`--export-sequence`), así que en desktop hace falta Krita instalado (o `KRITA_PATH`). El documento se lee por capas: cada capa de pintura es una pista, salvo los grupos que no se pueden separar sin cambiar la imagen (modo de fusión distinto de normal, opacidad, máscaras de filtro), que van enteros; máscaras de transformación, opacidad y filtros quedan aplicados en los píxeles. Cada pista es una exportación de Krita (unos 15 s por capa).
- **`.ipv` reales** de ibisPaint se leen y se escriben directamente, sin la app: cada carpeta de frame es un dibujo, con su duración y los fps de la animación, conservando la transparencia. Al leer, los modos de fusión y el recorte se combinan como "normal". Para abrir un `.ipv` generado, cópialo a `Android/data/jp.ne.ibis.ibispaintx.app/files` en el teléfono y abre "Mi galería" en ibisPaint.
- **Secuencia PNG**: como destino crea la carpeta `nombre_Nfps/` con un PNG por frame (holds repetidos, huecos transparentes); como origen se elige uno de sus PNG y se usa toda la carpeta.
- Los scripts de Frida en `hooks/` son placeholders para intercepción de memoria avanzada (requiere dispositivo con Frida Server).
- `bin/ghidra/`, `bin/platform-tools/` y `bin/apks/` están vacíos (placeholders). Coloca allí tus herramientas de REA si las usas.

---

## 📄 Licencia

Uso libre para proyectos personales y educativos.
