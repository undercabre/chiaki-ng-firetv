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
| Botón PS y panel táctil a veces no llegan | El touchpad se captura como puntero (dedos, deslizamientos y clic). Si Fire OS solo entrega el clic como tecla desconocida, *Ajustes → Aprender clic del panel táctil*. Alternativa: Create o L3+R3 como panel táctil. El menú en partida también envía PS y panel táctil |
| Bitrate con campo de texto | Valor libre de 2 a 100 Mbps con el mando (← → ±1, L1/R1 ±10, teclas numéricas). También se cambia desde el menú en partida, que reconecta al momento |
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
- Panel táctil: en Fire OS 7 sin driver lo normal es que solo llegue el clic (apréndelo en Ajustes) o nada (usa Create o L3+R3 como alternativa). Los deslizamientos solo funcionan si la Prueba del mando muestra «Dedos» al mover un dedo.
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

## Fire TV Stick Lite

Fire OS 7 (Android 9), 1 GB de RAM, salida máxima 1080p y wifi 5 GHz.

- Resolución: 1080p con PS5, 720p con PS4 normal. No pidas más de lo que la tele recibe.
- Códec: H265 con PS5 (menos ancho de banda a igual calidad), H264 con PS4.
- Bitrate: empieza en 10-15 Mbps por wifi 5 GHz. Si hay cortes o artefactos, baja de 2 en 2 desde el menú en partida. Por encima de ~25 Mbps la Lite no suele ganar calidad.
- El kernel no trae el driver del DualSense: comprueba el mapeo en la Prueba del mando y configura el panel táctil en Ajustes.

## Latencia mínima

Activado por defecto:

- **Decodificador de baja latencia**: pide al chip el modo tiempo real (claves estándar de Android y de los fabricantes conocidos) y muestra siempre el fotograma más reciente, descartando los atrasados. Si el chip rechaza la configuración, la app vuelve sola al modo normal. En el chip MediaTek de la Lite no hay clave documentada: lo que más ayuda es el descarte de fotogramas atrasados.
- **Fotogramas sin trocear**: búferes de entrada del tamaño de un fotograma completo.
- **60 Hz en la tele**: si la Fire TV emite a 50 Hz, cambia a 60 Hz durante la partida (evita tirones y un fotograma de retraso).
- El mando se envía a la consola en cuanto cambia (sin intervalos).

Para medir: menú en partida → **Mostrar estadísticas**. Lo que conviene ver:

| Dato | Bueno | Qué hacer si es malo |
|---|---|---|
| Decodificación | < 15 ms | Prueba H264 o 720p |
| FPS | 58-60 | Baja el bitrate |
| Pérdida | < 1 % | Baja el bitrate o acerca el router / usa 5 GHz |
| Perdidos/s | 0 | Igual que pérdida |
| RTT | < 10 ms | Problema de red: wifi 5 GHz o Ethernet |

Fuera de la app, lo que más cuenta: **modo juego en la tele** (puede ahorrar 50-100 ms) y la consola conectada por cable al router.
