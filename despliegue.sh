#!/bin/bash
# ****************************************************************************
# Anotaciones importantes:
#
# Para el despliegue de la practica hemos definido como directorio de
# uso el ~/Escritorio, por lo que este script como otros componentes
# deberan estar en el este directorio
#
# Es importante que este el tomcat en el escritorio de la version que
# usamos en nuestra implementacion es la 9.0.115
#
# Lo que tiene que encontrar el fichero en el Escritorio es:
# - despliegue.sh
# - config.txt (Con el formato que indicamos en el fichero)
# - Cliente.jar
# - EjercicioPBFT.war
#
# ****************************************************************************


# Referencia 1: https://www.gnu.org/software/bash/manual/bash.html#The-Set-Builtin
# set da robustez ante los fallos que se puedan dar en el script
#
# -e : salir si un comando falla
# -u : error si se usa variable no definida
# -o pipefail : el pipe falla si falla cualquier comando de la cadena

set -euo pipefail

# Fin Referencia 1


# Referencia 2: https://www.gnu.org/software/bash/manual/bash.html#Bash-Variables
# ${BASH_SOURCE[0]} da la ruta del script actual (mas fiable que $0)
# cd ... && pwd resuelve rutas relativas y symlinks

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="$SCRIPT_DIR/config.txt"
WAR="$SCRIPT_DIR/EjercicioPBFT.war"
CLIENTE_JAR="$SCRIPT_DIR/Cliente.jar"

# Fin Referencia 2


# Referencia 3: https://www.gnu.org/software/coreutils/manual/coreutils.html#whoami-invocation
# whoami devuelve el nombre del usuario efectivo actual
# Se usa para construir la cadena de conexion SSH usuario@ip

USUARIO="$(whoami)"
TOMCAT_HOME="$HOME/Escritorio/apache-tomcat-9.0.115"
TOMCAT_WEBAPPS="$TOMCAT_HOME/webapps"

# Nota: Si el equipo esta en Ingles hay que cambiar a Desktop
ESCRITORIO_REMOTO="Escritorio"

PUERTO_APP=8080
APP_PATH="EjercicioPBFT/rest"

# Fin Referencia 3


# Referencia 4: https://en.wikipedia.org/wiki/ANSI_escape_code#Colors
# Usamos las cadenas de escape ANSI para colorear la salida de la terminal
# NC (No Color) resetea el color al final de cada mensaje

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()   { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERR ]${NC}  $*"; }

# Fin Referencia 4


# Referencia 5: https://www.gnu.org/software/bash/manual/bash.html#Bash-Conditional-Expressions
# [ ! -f FILE ] comprueba que el fichero NO existe o no es un fichero regular
# Si falta cualquiera de los ficheros necesarios, se muestra el error y se aborta

[ ! -f "$CONFIG"      ] && err "No se encuentra config.txt en $SCRIPT_DIR"        && exit 1
[ ! -f "$WAR"         ] && err "No se encuentra EjercicioPBFT.war en $SCRIPT_DIR" && exit 1
[ ! -f "$CLIENTE_JAR" ] && err "No se encuentra Cliente.jar en $SCRIPT_DIR"       && exit 1

# Fin Referencia 5


# Referencia 6: https://man7.org/linux/man-pages/man1/hostname.1.html
#               https://www.gnu.org/software/gawk/manual/gawk.html
# hostname devuelve el nombre del host local
# hostname -I devuelve todas las IPs asociadas, awk {print $1} toma la primera

MI_HOSTNAME=$(hostname)
MI_IP=$(hostname -I | awk '{print $1}')

# Fin Referencia 6

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║       Despliegue PBFT con Tomcat         ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════╝${NC}"
echo ""
log "Hostname local : $MI_HOSTNAME"
log "IP local       : $MI_IP"
log "Directorio     : $SCRIPT_DIR"
echo ""


# Referencia 7: https://www.gnu.org/software/grep/manual/grep.html
#               https://www.gnu.org/software/coreutils/manual/coreutils.html#cut-invocation
#               https://www.gnu.org/software/coreutils/manual/coreutils.html#tr-invocation
# grep "^maquinas=" filtra la linea que empieza por "maquinas="
# cut -d= -f2 divide por = y toma el segundo campo (el valor)
# tr -d [:space:] elimina espacios y saltos de linea sobrantes

LINEA_MAQUINAS=$(grep "^maquinas=" "$CONFIG" | cut -d= -f2)
TOTAL_PROCESOS=$(grep "^totalProcesos=" "$CONFIG" | cut -d= -f2 | tr -d '[:space:]')

# Fin Referencia 7


# Referencia 8: https://www.gnu.org/software/bash/manual/bash.html#index-IFS
#               https://www.gnu.org/software/bash/manual/bash.html#index-read
#               https://www.gnu.org/software/bash/manual/bash.html#index-declare
# IFS=',' hace que read separe por comas en lugar de espacios
# read -ra ARRAY lee la cadena en un array indexado
# declare -a declara explicitamente arrays indexados en bash

IFS=',' read -ra NODOS <<< "$LINEA_MAQUINAS"

declare -a HOSTNAMES MAQUINA_IPS
for nodo in "${NODOS[@]}"; do
    parts=($(echo "$nodo" | tr ':' ' '))
    HOSTNAMES+=("${parts[0]}")
    MAQUINA_IPS+=("${parts[1]}")
done

# Fin Referencia 8


# Referencia 9: https://www.gnu.org/software/coreutils/manual/coreutils.html#seq-invocation
# seq START #END genera una secuencia de numeros enteros para iterar con for

NUM_MAQUINAS=${#HOSTNAMES[@]}
log "Máquinas detectadas en config.txt ($NUM_MAQUINAS):"
for i in $(seq 0 $((NUM_MAQUINAS - 1))); do
    echo "    [$(($i+1))] ${HOSTNAMES[$i]}  →  ${MAQUINA_IPS[$i]}"
done
echo ""

# Fin Referencia 9


# Fase 0: Generacion y distribucion de claves SSH
echo -e "${BOLD}=== Fase 0: Configurando acceso SSH sin contraseña ===${NC}"
echo ""


# Referencia 10: https://man.openbsd.org/ssh-keygen
# ssh-keygen -t rsa genera un par de claves RSA
# -b 4096 establece el tamaño de la clave en 4096 bits (mas seguro que el defecto de 2048)
# -f indica el fichero de salida donde se guarda la clave
# -N "" indica passphrase vacia, necesario para que SSH no pida contrasena al usarla

CLAVE_SSH="$HOME/.ssh/id_rsa"

if [ ! -f "$CLAVE_SSH" ]; then
    log "Generando par de claves RSA en $CLAVE_SSH ..."
    ssh-keygen -t rsa -b 4096 -f "$CLAVE_SSH" -N ""
    ok "Clave RSA generada correctamente"
else
    log "Ya existe una clave RSA en $CLAVE_SSH, se reutiliza"
fi

# Fin Referencia 10


# Referencia 11: https://man.openbsd.org/ssh-copy-id
#                https://www.openssh.com/manual.html
# ssh-copy-id copia la clave publica local (~/.ssh/id_rsa.pub) al fichero
# ~/.ssh/authorized_keys de la maquina remota, permitiendo el acceso sin contrasena
# -i indica explicitamente que clave publica copiar
# -o StrictHostKeyChecking=no acepta automaticamente el fingerprint del host remoto
#    la primera vez, evitando el prompt interactivo de confirmacion

for i in $(seq 0 $((NUM_MAQUINAS - 1))); do
    h="${HOSTNAMES[$i]}"
    ip="${MAQUINA_IPS[$i]}"

    # Las maquinas locales no necesitan intercambio de claves SSH
    if [ "$h" == "$MI_HOSTNAME" ] || [ "$ip" == "$MI_IP" ]; then
        log "[$h] Maquina local, se omite el intercambio de clave SSH"
        continue
    fi

    log "[$h] Copiando clave publica a $ip (se pedira la contrasena una ultima vez)..."
    if ssh-copy-id -i "$CLAVE_SSH.pub" \
        -o StrictHostKeyChecking=no \
        "$USUARIO@$ip" 2>/dev/null; then
        ok "[$h] Clave SSH instalada correctamente en $ip"
    else
        warn "[$h] No se pudo instalar la clave en $ip — el despliegue puede fallar"
    fi
done

# Fin Referencia 11

echo ""


# Funcion de despliegue remoto en las distintas maquinas,
# solo desplegamos el servicio con los procesos
desplegar_remoto() {
    local hostname=$1
    local ip=$2

    log "[$hostname] Comprobando SSH..."

    # Referencia 12: https://man.openbsd.org/ssh
    #                https://www.openssh.com/manual.html
    # ssh -o ConnectTimeout=5 limita el tiempo de espera de conexion a 5 segundos
    # -o BatchMode=yes desactiva prompts interactivos (contrasenas, confirmaciones)
    # Tras la Fase 0 la conexion ya no requiere contrasena gracias a la clave RSA

    if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "$USUARIO@$ip" "exit" 2>/dev/null; then
        err "[$hostname] No se puede conectar por SSH a $ip"
        err "            Ejecuta en esa máquina: sudo apt install openssh-server && sudo systemctl enable --now ssh"
        return 1
    fi

    # Fin Referencia 12

    if ! ssh "$USUARIO@$ip" "[ -d '$TOMCAT_WEBAPPS' ]" 2>/dev/null; then
        err "[$hostname] No se encuentra Tomcat en $TOMCAT_HOME"
        err "            Instálalo o cambia TOMCAT_HOME en este script"
        return 1
    fi

    log "[$hostname] Copiando WAR y config..."

    # Referencia 13: https://man.openbsd.org/scp
    #                https://tomcat.apache.org/tomcat-9.0-doc/deployer-howto.html
    # scp -q copia ficheros por SSH en modo silencioso (sin barra de progreso)
    # Tomcat despliega automaticamente el WAR al detectarlo en la carpeta webapps/

    scp -q "$WAR"    "$USUARIO@$ip:$TOMCAT_WEBAPPS/EjercicioPBFT.war"
    scp -q "$CONFIG" "$USUARIO@$ip:$ESCRITORIO_REMOTO/config.txt"

    # Fin Referencia 13


    log "[$hostname] Reiniciando Tomcat..."


    # Referencia 14: https://tomcat.apache.org/tomcat-9.0-doc/RUNNING.txt
    # shutdown.sh detiene Tomcat, || true evita que el script aborte si ya estaba parado
    # Se espera 3 segundos para que el proceso termine antes de volver a arrancarlo

    ssh "$USUARIO@$ip" "
        $TOMCAT_HOME/bin/shutdown.sh 2>/dev/null || true
        sleep 3
        $TOMCAT_HOME/bin/startup.sh
    "

    # Fin Referencia 14


    ok "[$hostname] Tomcat arrancado"
}
# Fin Despliegue Remoto


# Funcion de despliegue local en las distintas maquinas,
# solo desplegamos el servicio con los procesos
desplegar_local() {
    local hostname=$1

    log "[$hostname] Desplegando localmente..."

    # Referencia 15: https://www.gnu.org/software/bash/manual/bash.html#Bash-Conditional-Expressions
    # [ ! -d DIR ] comprueba que el directorio NO existe
    # Si no se encuentra Tomcat en la ruta esperada se informa del error y se aborta

    if [ ! -d "$TOMCAT_WEBAPPS" ]; then
        err "[$hostname] No se encuentra Tomcat en $TOMCAT_HOME"
        err "            Instálalo: sudo apt install tomcat9"
        err "            O cambia TOMCAT_HOME en este script"
        return 1
    fi

    # Fin Referencia 15


    log "[$hostname] Copiando WAR y config..."


    # Referencia 16: https://www.gnu.org/software/coreutils/manual/coreutils.html#cp-invocation
    # cp copia ficheros de forma local
    # El WAR se copia a webapps/ y Tomcat lo despliega automaticamente

    cp "$WAR"    "$TOMCAT_WEBAPPS/EjercicioPBFT.war"
    cp "$CONFIG" "$HOME/$ESCRITORIO_REMOTO/config.txt"

    # Fin Referencia 16


    log "[$hostname] Reiniciando Tomcat local..."


    # Referencia 17: https://tomcat.apache.org/tomcat-9.0-doc/RUNNING.txt
    # Igual que en el despliegue remoto: shutdown primero y startup despues
    # || true evita abortar si Tomcat ya estaba detenido

    "$TOMCAT_HOME/bin/shutdown.sh" 2>/dev/null || true
    sleep 3
    "$TOMCAT_HOME/bin/startup.sh"

    # Fin Referencia 17


    ok "[$hostname] Tomcat local arrancado"
}
# Fin Funcion del despliegue local


# Despliegue de los WAR
echo -e "${BOLD}=== Fase 1: Desplegando WAR en cada máquina ===${NC}"
echo ""

IPS_LISTA=""
for i in $(seq 0 $((NUM_MAQUINAS - 1))); do
    h="${HOSTNAMES[$i]}"
    ip="${MAQUINA_IPS[$i]}"

    if [ "$h" == "$MI_HOSTNAME" ] || [ "$ip" == "$MI_IP" ]; then
        desplegar_local "$h" || warn "[$h] Falló el despliegue local"
    else
        desplegar_remoto "$h" "$ip" || warn "[$h] Falló el despliegue remoto"
    fi

    IPS_LISTA="${IPS_LISTA}${ip},"
done


# Referencia 18: https://www.gnu.org/software/bash/manual/bash.html#Shell-Parameter-Expansion
# ${VAR%,} elimina el sufijo mas corto que coincide con ',' (la coma final)
# Evita pasar una lista malformada al cliente Java

IPS_LISTA="${IPS_LISTA%,}"

# Fin Referencia 18

# Fin Despliegue del WAR


# Esperamos respuesta de los servicios
echo ""
echo -e "${BOLD}=== Fase 2: Esperando que los servidores estén listos ===${NC}"
echo ""

ESPERA=30
for ip in $(echo "$IPS_LISTA" | tr ',' ' '); do
    printf "    Esperando %-15s " "$ip"
    for t in $(seq 1 $ESPERA); do


        # Referencia 19: https://curl.se/docs/manpage.html
        # curl -sf hace una peticion HTTP en modo silencioso; devuelve error si falla
        # --connect-timeout 1 limita la espera de conexion a 1 segundo por intento
        # Se consulta el endpoint REST /servicio/estado para verificar que la app esta activa

        if curl -sf --connect-timeout 1 \
            "http://$ip:$PUERTO_APP/$APP_PATH/servicio/estado" > /dev/null 2>&1; then
            echo -e "${GREEN}listo${NC} (${t}s)"
            break
        fi

        # Fin Referencia 19


        printf "."
        sleep 1
        if [ $t -eq $ESPERA ]; then
            echo -e " ${YELLOW}timeout — puede que Tomcat tarde más${NC}"
        fi
    done
done
# Fin espera de los servicios


# Lanzamiento del cliente
echo ""
echo -e "${BOLD}=== Fase 3: Iniciando cliente ===${NC}"
echo ""
log "Conectando a: $IPS_LISTA (totalProcesos=$TOTAL_PROCESOS)"
echo ""


# Referencia 20: https://docs.oracle.com/en/java/technologies/javase/
# java -jar ejecuta un fichero JAR ejecutable
# Se pasan como argumentos la lista de IPs separadas por comas y el total de procesos PBFT

java -jar "$CLIENTE_JAR" "$IPS_LISTA" "$TOTAL_PROCESOS"

# Fin Referencia 20

# Fin Lanzamiento del cliente
