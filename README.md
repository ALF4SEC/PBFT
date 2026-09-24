# PBFT

Práctica de **Sistemas Distribuidos**: implementación del algoritmo de consenso tolerante a fallos bizantinos **PBFT** (*Practical Byzantine Fault Tolerance*) con servicios REST en Java (Jersey) desplegados en Tomcat.

La práctica está pensada para ejecutarse en **2 o más ordenadores**. Cada ordenador aloja varios procesos PBFT dentro de su Tomcat, y los procesos de todas las máquinas se intercambian mensajes por HTTP para ponerse de acuerdo en un valor.

## Funcionamiento

- Con `N` procesos en total se toleran `f = (N - 1) / 3` procesos bizantinos, y el quórum es `2f + 1`.
- El cliente propone un valor y el consenso avanza por fases: **compromiso** (fase 1), **comisión** (fase 2a) y **confirmación** (fase 2b).
- Cualquier proceso puede marcarse como **bizantino** desde el cliente para comprobar que el sistema sigue llegando a consenso mientras no se superen `f` fallos.

## Requisitos

En **cada ordenador**:

- Linux con Java instalado.
- Apache Tomcat 9.0.115 en `~/Escritorio/apache-tomcat-9.0.115`.
- Servidor SSH (`sudo apt install openssh-server`).
- El mismo nombre de usuario en todas las máquinas y conexión de red entre ellas.

> Si el sistema está en inglés, cambia `Escritorio` por `Desktop` en `despliegue.sh`.

## Configuración

Edita `config.txt` con las máquinas que vas a usar, con el formato `hostname:ip:procesosLocales` separado por comas:

```properties
maquinas=fonsi:192.168.1.78:2,alfasec:192.168.1.34:2
totalProcesos=4
```

## Despliegue

Copia `despliegue.sh`, `config.txt`, `Cliente.jar` y `EjercicioPBFT.war` al Escritorio de uno de los ordenadores y ejecuta:

```bash
./despliegue.sh
```

El script:

1. Genera una clave SSH y la copia a las demás máquinas (pide la contraseña una sola vez).
2. Copia el WAR y la configuración a cada máquina y reinicia Tomcat.
3. Espera a que todos los servicios respondan.
4. Arranca el cliente conectado a todas las máquinas.

## Uso del cliente

| Comando | Acción |
|---|---|
| `s` | Muestra el estado de todos los procesos |
| `sX` | Propone un consenso con el valor `X` (ej. `s42`) |
| `fN` | Activa o desactiva el fallo bizantino del proceso `N` (ej. `f3`) |
| `h` | Ayuda |
| `q` | Salir |

El cliente también se puede lanzar a mano:

```bash
java -jar Cliente.jar 192.168.1.34,192.168.1.78 4
```

## Estructura

- `EjercicioPBFT/src/services/Servicio.java`: servicio REST (`/EjercicioPBFT/rest/servicio/...`) que aloja los procesos locales y difunde los mensajes.
- `EjercicioPBFT/src/services/Proceso.java`: lógica de cada proceso PBFT.
- `EjercicioPBFT/src/cliente/Cliente.java`: cliente de consola.
- `despliegue.sh`: script de despliegue en varias máquinas.
- `config.txt`: configuración de máquinas y procesos.
- `EjercicioPBFT.war` y `Cliente.jar`: artefactos compilados listos para desplegar.
