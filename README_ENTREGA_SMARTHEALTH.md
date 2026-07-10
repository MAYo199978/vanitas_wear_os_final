# SmartHealth Sync - Ejercicio Final con wearOS

## Descripción de la solución
SmartHealth Sync es una solución entre smartwatch Wear OS y teléfono Android. El reloj obtiene datos de sensores físicos y ambientales, y los sincroniza con la aplicación móvil usando DataClient. Además, desde el reloj se puede activar la cámara del celular mediante MessageClient para preparar y capturar una fotografía.

## Sensores incluidos
- Contador de pasos
- Presión atmosférica
- Temperatura ambiente
- Ritmo cardiaco
- Latido cardiaco
- Batería
- Humedad relativa
- Proximidad
- Luz ambiental
- Campo magnético

## Funcionalidad adicional requerida por la rúbrica
- Cámara del celular controlada desde el reloj.
- Intercambio de datos Wear OS -> Mobile.
- Confirmaciones Wear <-> Mobile con MessageClient.
- Icono personalizado de la aplicación.

## Flujo de operación
1. El usuario abre la app en el smartwatch.
2. El reloj lee sensores disponibles del dispositivo.
3. El usuario pulsa "Enviar al móvil".
4. Los datos se envían al celular mediante la ruta `/steps`.
5. La app móvil recibe y muestra la información en un panel de sensores.
6. Desde el reloj, el usuario puede cambiar a la pantalla de cámara.
7. Primer toque: el reloj envía `/take_photo` y el móvil abre la cámara.
8. Segundo toque: el reloj envía `/capture_photo`, el móvil toma la foto y la envía al reloj como Asset.

## Diagrama de contexto
```text
+------------------------+          DataClient / MessageClient          +-----------------------+
| Smartwatch Wear OS     | -------------------------------------------> | Teléfono Android      |
|                        |                                              |                       |
| - Sensores             | ---- datos de pasos, temperatura, etc. ----> | - Panel de sensores   |
| - Botón sincronizar    |                                              | - Cámara CameraX      |
| - Botón cámara         | <---- confirmación / imagen capturada ------ | - Envío de fotografía |
+------------------------+                                              +-----------------------+
```

## Capturas recomendadas para el documento
1. Pantalla principal del reloj mostrando sensores.
2. Reloj después de pulsar "Enviar al móvil".
3. App móvil mostrando los datos recibidos.
4. Reloj en pantalla de cámara con botón "Activar cámara".
5. Celular con vista previa de CameraX abierta.
6. Reloj mostrando la fotografía recibida.
7. Evidencia de Git: commits, ramas o historial.

## Git sugerido
```bash
git init
git add .
git commit -m "Inicio del proyecto SmartHealth Sync"
git commit -m "Personalizacion de interfaz mobile y wear"
git commit -m "Integracion de sensores y camara entre Wear OS y Android"
```

## Antes de comprimir
Ya se omitieron carpetas pesadas como `build`, `.gradle`, `.idea` y `.kotlin`.

## Nomenclatura sugerida
Cambia el nombre final según tus datos:

`DPDI9A_MendezLaraJose_U2ActFinal.zip`
