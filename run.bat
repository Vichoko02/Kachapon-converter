@echo off
setlocal enabledelayedexpansion

:: ================================================================
::  KachaponConverter - Lanzador para Windows (Versión Desktop)
::  Convierte animaciones entre Krita (.kra), Clip Studio (.clip)
::  e ibisPaint (.ipv) usando un puente universal de frames PNG.
:: ================================================================
title KachaponConverter

echo ========================================
echo   KachaponConverter - Desktop (Windows)
echo   Animacion Indie: Krita / Clip / Ibis
echo ========================================
echo.

:: --- Rutas de dependencias pre-bajadas (librs/) ---
set "CP=libs\gson-2.10.1.jar;libs\javafx_javafx-base-21-win.jar;libs\javafx_javafx-controls-21-win.jar;libs\javafx_javafx-fxml-21-win.jar;libs\javafx_javafx-graphics-21-win.jar"

:: --- Verificar que Java exista ---
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java no esta instalado o no esta en el PATH.
    echo.
    echo Instala JDK 21 o superior:
    echo   https://adoptium.net/
    echo.
    pause
    exit /b 1
)

:: --- Compilar siempre: un build viejo no incluiria los cambios del motor ---
if not exist "build\classes" mkdir "build\classes"
(
    echo [1/3] Compilando motor - core y desktop...
    javac -encoding UTF-8 -cp "%CP%" -d build\classes ^
        core\src\main\java\com\kachapon\core\*.java ^
        core\src\main\java\com\kachapon\core\converters\*.java ^
        core\src\main\java\com\kachapon\core\orchestrator\*.java ^
        desktop\src\main\java\com\kachapon\desktop\MainApp.java
    if %errorlevel% neq 0 (
        echo.
        echo ERROR: Fallo la compilacion. Revisa los mensajes arriba.
        pause
        exit /b 1
    )
    echo Compilacion OK.
)

:: --- Copiar recursos (CSS) ---
if exist "desktop\src\main\java\com\kachapon\desktop\app.css" (
    copy "desktop\src\main\java\com\kachapon\desktop\app.css" "build\classes\com\kachapon\desktop\app.css" >nul 2>&1
)

:: --- Copiar la plantilla de .clip (la usa el escritor de Clip Studio) ---
copy "core\src\main\resources\com\kachapon\core\clip_template.clip" "build\classes\com\kachapon\core\clip_template.clip" >nul 2>&1

:: --- Crear carpetas de trabajo si faltan ---
if not exist "logs" mkdir "logs" 2>nul

:: --- Ejecutar ---
echo [2/3] Iniciando interfaz grafica...
echo.
java --enable-native-access=javafx.graphics --module-path "%CP%" -cp "build\classes;libs\gson-2.10.1.jar" --add-modules javafx.controls,javafx.fxml,javafx.graphics com.kachapon.desktop.MainApp

if %errorlevel% neq 0 (
    echo.
    echo ERROR: La aplicacion cerro inesperadamente, codigo %errorlevel%.
) else (
    echo.
    echo La aplicacion se cerro normalmente.
)
echo [3/3] Listo.
pause
