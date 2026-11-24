# Chiaki FireTV

Fork de la app Android de [chiaki-ng](https://github.com/chiaki-ng/chiaki-ng) pensado para Amazon Fire TV (Stick, Stick 4K, Cube) con un DualSense conectado por Bluetooth. Sin pantalla táctil y sin teclado.

El motor de streaming (`lib/`) es el de chiaki-ng, sin cambios. Solo cambia la app Android (`android/`).

## Qué cambia respecto a la app original

| Problema en Fire TV | Solución |
|---|---|
| Botón flotante y menús táctiles | Pantalla principal con una fila de botones y tarjetas de consola, todo con cruceta o stick |
| Sin teclado para el Account ID | Enlace con el móvil: la TV muestra un QR, rellenas el formulario en el móvil y llega a la TV. El Account ID se recuerda |
| PIN de 8 cifras (registro) y de 4 cifras (inicio de sesión) | Selector de cifras: ↑ ↓ cambia la cifra, ← → se desplaza, también con las teclas numéricas del mando a distancia |
| Fire OS no siempre trae el driver del DualSense (botones desordenados: Cruz = atrás…) | Detección automática y corrección del mapeo HID «en bruto». Ajuste manual y pantalla de **Prueba del mando** |
| Sin forma de salir del juego (Círculo pertenece al juego) | Menú en partida: **L1 + R1 + Create + Options** a la vez, o Atrás en el mando de la Fire TV |
| Botón PS y panel táctil a veces no llegan | El menú en partida permite enviar «botón PS» y «panel táctil» |
| Bitrate con campo de texto | Lista de valores (5 a 50 Mbps) |
| Borrar consola registrada deslizando | Cruz sobre la consola → borrar |
| Importar ajustes con un selector de archivos (no existe en Fire TV) | Importación desde el móvil (QR) |
| Vibración del teléfono | Vibración enviada al mando cuando Fire OS lo permite |
| Controles táctiles y sensores del teléfono | Desactivados por defecto si no hay pantalla táctil; 1080p por defecto |

Paquete: `com.metallic.chiaki.firetv`. Se instala junto al Chiaki original sin conflicto.

## Instalación en la Fire TV

1. En la Fire TV: *Configuración → Mi Fire TV → Opciones para desarrolladores → Instalar apps desconocidas* (o activa ADB).
2. Descarga el APK (`Chiaki-FireTV-*.apk`) de la pestaña *Releases* o *Actions* de tu fork.
3. Instálalo con la app *Downloader* o desde un PC: `adb connect <ip-fire-tv>` y luego `adb install Chiaki-FireTV-xxx.apk`.

## Emparejar el DualSense

*Configuración → Mandos y dispositivos Bluetooth → Mandos de juego → Añadir mando*. En el DualSense, mantén **Create + PS** hasta que la luz parpadee rápido.

Abre después la app → icono de mando → **Prueba del mando**. Pulsa Cruz: debe aparecer «Cross ✕». Si aparece otro botón, ve a *Ajustes → Mapeo del mando* y elige «DualSense/DS4 sin driver».

## Primer registro de la consola

1. PS5: *Ajustes → Sistema → Uso a distancia → Activar*. PS4: *Ajustes → Ajustes de conexión de Uso a distancia*.
2. En la app: deja que el descubrimiento encuentre la consola y pulsa Cruz sobre ella, o usa **Registrar**.
3. Escanea el QR con el móvil (misma wifi). En el formulario indica el tipo de consola, tu Account ID de PSN y el PIN que muestra la consola (*Vincular dispositivo*). Pulsa *Enviar a la TV*.
4. Comprueba los datos en la TV y pulsa **Registrar**.

Para obtener el Account ID, consulta la [documentación de chiaki-ng](https://chiaki-ng.github.io/chiaki-ng/setup/configuration/#registering-your-playstation).

Atajo: si ya usas Chiaki en un móvil Android, exporta los ajustes allí y envíalos con *Ajustes → Importar ajustes desde el móvil*. Ojo: el formato de chiaki-ng de escritorio (PC/Steam Deck) es distinto y no es compatible.

## Controles

| Dónde | Acción |
|---|---|
| Menús | Cruceta o stick izquierdo para moverse, Cruz para aceptar, Círculo para volver |
| Tarjeta de consola | Triángulo u Options: encender, editar, borrar |
| En partida | Todo va a la consola. **L1 + R1 + Create + Options** abre el menú (volver, PS, panel táctil, imagen, desconectar) |

## Limitaciones conocidas (honestas)

- **No probado todavía en una Fire TV real.** El mapeo «sin driver» se basa en el orden HID estándar del DualSense con `hid-generic`. Verifícalo con la Prueba del mando.
- Giroscopio, gatillos adaptativos y altavoz del DualSense: no se transmiten (Android no los expone por Bluetooth sin driver).
- Panel táctil: solo el clic, si el kernel lo expone, o mediante el menú en partida. No hay deslizamiento.
- Botón PS: Fire OS puede capturarlo para ir al inicio. Usa «Pulsar botón PS» en el menú.
- 1080p requiere PS5 o PS4 Pro. Con una PS4 normal, baja a 720p en Ajustes.
- H265 solo funciona con PS5. Las Fire TV Stick 4K/4K Max lo decodifican por hardware; los modelos básicos más antiguos pueden ir mejor con H264.
- Usa wifi de 5 GHz o, mejor, el adaptador Ethernet de Amazon. Empieza con 10-15 Mbps.

## Compilar

La parte nativa (OpenSSL, curl, nanopb…) solo compila de forma fiable en Linux. El workflow `.github/workflows/build-android-firetv.yml` genera el APK en GitHub Actions:

- cada push a `main` que toque `android/`, `lib/`…;
- cada tag `firetv-v*` crea además una Release con el APK.

En local (Linux o WSL): `git submodule update --init --recursive`, `pip install protobuf grpcio-tools`, Android SDK + NDK 28.2.13676358 + CMake 3.22.1, y luego `cd android && ./gradlew assembleRelease`.

### Firma estable (recomendado)

Sin keystore, cada build de CI se firma con una clave de debug distinta. Habría que desinstalar la app (y perder las consolas registradas) para actualizar. Crea una clave una sola vez:

```
keytool -genkeypair -v -keystore firetv.jks -alias chiaki -keyalg RSA -keysize 4096 -validity 10000
```

Añade en GitHub (*Settings → Secrets and variables → Actions*): `CHIAKI_KEYSTORE_BASE64` (contenido de `firetv.jks` en base64), `CHIAKI_KEYSTORE_PASSWORD`, `CHIAKI_KEY_ALIAS` (`chiaki`) y `CHIAKI_KEY_PASSWORD`. Guarda el `.jks` fuera del repositorio.

## Licencia

AGPL-3.0, como chiaki-ng. Si distribuyes el APK, publica también el código fuente de tu fork.
