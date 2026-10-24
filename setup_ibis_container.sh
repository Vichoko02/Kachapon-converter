#!/bin/bash
# setup_ibis_container.sh - Levanta el contenedor Redroid + ibisPaint y configura ADB

set -e

APK_PATH="bin/apks/ibispaint_frozen.apk"
CONTAINER_NAME="kachapon-ibis"
ADB_PORT=5555

echo "=== KachaponConverter - Setup contenedor ibisPaint (Redroid + Docker) ==="

# 1. Verificar que existe el APK
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: No se encontró $APK_PATH"
    echo "Coloca el APK congelado de ibisPaint v12 en bin/apks/ibispaint_frozen.apk"
    exit 1
fi

# 2. Verificar Docker
if ! docker info > /dev/null 2>&1; then
    echo "ERROR: Docker no está ejecutándose"
    exit 1
fi

# 3. Detener contenedor anterior si existe
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

# 4. Construir y levantar el contenedor
echo "Construyendo contenedor ibis-container..."
docker-compose -f docker-compose.ibis.yml up -d --build

# 5. Esperar a que ADB esté disponible
echo "Esperando a que ADB esté listo en el puerto $ADB_PORT..."
for i in $(seq 1 30); do
    if adb connect localhost:$ADB_PORT 2>/dev/null; then
        echo "ADB conectado en localhost:$ADB_PORT"
        break
    fi
    echo "Intento $i/30..."
    sleep 2
done

# 6. Verificar dispositivo
adb devices

echo ""
echo "=== Contenedor listo ==="
echo "Para usar: python extract_ibis_docker.py  (o la clase Java DockerIbisExtractor)"
echo "El APK de ibisPaint debe estar instalado en el contenedor."
