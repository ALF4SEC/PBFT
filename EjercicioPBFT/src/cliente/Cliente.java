package cliente;

import org.glassfish.jersey.client.ClientProperties;

import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import java.util.*;

public class Cliente {

    private static final int    PUERTO     = 8080;
    private static final String APLICACION = "EjercicioPBFT/rest";

    private final List<String> ips;
    private final Client       clienteHttp;
    private int quorumProcesos = 1;
    private int quorumMaquinas = 1;

    /*********************************************************************************************
     * CONSTRUCTOR
     *
     * Referencia (ClientBuilder + ClientProperties):
     *   https://eclipse-ee4j.github.io/jersey.github.io/documentation/latest/client.html
     *   https://eclipse-ee4j.github.io/jersey.github.io/apidocs/latest/jersey/org/glassfish/jersey/client/ClientProperties.html
     * ClientBuilder.newBuilder() crea un cliente JAX-RS con configuración personalizada.
     * ClientProperties.CONNECT_TIMEOUT limita el tiempo de espera para establecer la
     * conexión (en milisegundos)
     * READ_TIMEOUT limita el tiempo de espera para recibir la respuesta una vez conectado
     *********************************************************************************************/
    public Cliente(List<String> ips, int totalProcesos) {
        this.ips = ips;
        this.clienteHttp = ClientBuilder.newBuilder()
                .property(ClientProperties.CONNECT_TIMEOUT, 3000)
                .property(ClientProperties.READ_TIMEOUT, 25000)
                .build();
        actualizarQuorum(totalProcesos);
    }

    /*********************************************************************************************
     * ACTUALIZAR QUORUM
     *********************************************************************************************/
    private void actualizarQuorum(int n) {
        if (n <= 0) return;
        int f = (n - 1) / 3;
        this.quorumProcesos = 2 * f + 1;
        // Quórum de máquinas: mayoría simple de las IPs disponibles
        this.quorumMaquinas = (ips.size() / 2) + 1;
    }

    /*********************************************************************************************
     * CONSTRUIR URL
     *********************************************************************************************/
    private String construirUrl(String ip, String endpoint) {
        return "http://" + ip.trim() + ":" + PUERTO + "/" + APLICACION + "/servicio/" + endpoint;
    }

    /*********************************************************************************************
     * ESTADO
     * Consulta el estado de todos los procesos preguntando a cada IP conocida.
     * Agrega las filas recibidas, recalcula el quórum con el total observado
     * y muestra una tabla formateada con columnas alineadas dinámicamente.
     *********************************************************************************************/
    public void estado() {
        List<String[]> filas = new ArrayList<>();
        for (String ip : ips) {
            try {
                String respuesta = clienteHttp.target(construirUrl(ip, "estado"))
                        .request(MediaType.TEXT_PLAIN).get(String.class);
                for (String linea : respuesta.split("\n")) {
                    if (linea.startsWith("id")) continue;
                    String[] columnas = linea.split("\t");
                    if (columnas.length >= 4)
                        filas.add(new String[]{
                            columnas[0].trim(), columnas[1].trim(), columnas[2].trim(), columnas[3].trim()
                        });
                }
            } catch (Exception e) {
                System.out.println("[" + ip + "] Error al obtener estado: " + e.getMessage());
            }
        }
        
        actualizarQuorum(filas.size());

        int[] anchos = {2, 3, 11, 5};
        for (String[] fila : filas)
            for (int i = 0; i < fila.length && i < anchos.length; i++)
                anchos[i] = Math.max(anchos[i], fila[i].length());

        String formato = "%-" + anchos[0] + "s  %-" + anchos[1] + "s  %-"
                       + anchos[2] + "s  %-" + anchos[3] + "s%n";
        int anchoTotal = anchos[0] + anchos[1] + anchos[2] + anchos[3] + 8;
        StringBuilder sbSeparador = new StringBuilder();
        for (int i = 0; i < anchoTotal; i++) sbSeparador.append("-");
        String separador = sbSeparador.toString();

        System.out.println();
        System.out.println(separador);
        System.out.printf(formato, "id", "var", "compromisos", "error");
        System.out.println(separador);
        for (String[] fila : filas) System.out.printf(formato, fila[0], fila[1], fila[2], fila[3]);
        System.out.println(separador);
        System.out.println();
    }

    /*********************************************************************************************
     * FALLO
     *********************************************************************************************/
    public void fallo(int id) {
        for (String ip : ips) {
            try {
                String respuesta = clienteHttp.target(construirUrl(ip, "fallo"))
                        .queryParam("id", id)
                        .request(MediaType.TEXT_PLAIN).get(String.class);
                if (!respuesta.contains("no encontrado")) {
                    System.out.println(respuesta);
                    return;
                }
            } catch (Exception ignorado) {}
        }
        System.out.println("Proceso " + id + " no encontrado en ninguna máquina.");
    }

    /*********************************************************************************************
     * CONSENSO
     *
     * Referencia (Collections.synchronizedList):
     *   https://docs.oracle.com/javase/8/docs/api/java/util/Collections.html#synchronizedList-java.util.List-
     * Collections.synchronizedList envuelve una lista normal haciéndola thread-safe: cada
     * operación queda protegida por un bloqueo intrínseco. Es necesario porque varios
     * hilos añaden respuestas a la lista de forma concurrente.
     * Ver también javaThread.pdf, sección "Listas y Mapas concurrentes".
     *
     * Referencia (Map.merge + Integer::sum / Integer::max):
     *   https://docs.oracle.com/javase/8/docs/api/java/util/Map.html#merge-K-V-java.util.function.BiFunction-
     *   https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html#sum-int-int-
     *   https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html#max-int-int-
     * merge con Integer::sum cuenta cuántas máquinas reportan cada valor.
     * merge con Integer::max guarda el mayor número de confirmaciones visto para ese valor.
     *********************************************************************************************/
    public void consenso(int valor) {
        System.out.println("Iniciando consenso | valor=" + valor
                + " (quorumProcesos=" + quorumProcesos + ", quorumMaquinas=" + quorumMaquinas + ")");

        final List<String> respuestas = Collections.synchronizedList(new ArrayList<>());
        List<Thread> hilos = new ArrayList<>();

        for (String ip : ips) {
            Thread hilo = new Thread(() -> {
                try {
                    String resultado = clienteHttp.target(construirUrl(ip, "set"))
                            .queryParam("valor", valor)
                            .request(MediaType.TEXT_PLAIN).get(String.class);
                    respuestas.add(resultado);
                } catch (Exception e) {
                    System.err.println("[SET] Error -> " + ip + ": " + e.getMessage());
                }
            });
            hilos.add(hilo);
            hilo.start();
        }

        for (Thread hilo : hilos) {
            try { hilo.join(20000); } catch (InterruptedException ignorado) {}
        }

        Map<String, Integer> conteoValores          = new HashMap<>();
        Map<String, Integer> confirmacionesPorValor = new HashMap<>();

        for (String r : respuestas) {
            if (r.contains("CONSENSO ALCANZADO")) {
                String valorStr = extraerValor(r);
                conteoValores.merge(valorStr, 1, Integer::sum);
                int confirmaciones = extraerConfirmaciones(r);
                confirmacionesPorValor.merge(valorStr, confirmaciones, Integer::max);
            }
        }

        boolean huboConsenso = false;
        for (Map.Entry<String, Integer> e : conteoValores.entrySet()) {
            if (e.getValue() >= quorumMaquinas) {
                int procesosConfirmaron = confirmacionesPorValor.getOrDefault(e.getKey(), 0);
                System.out.println("► CONSENSO ALCANZADO: valor=" + e.getKey()
                        + " (" + procesosConfirmaron + "/" + quorumProcesos + " procesos confirmaron)\n");
                huboConsenso = true;
                break;
            }
        }

        if (!huboConsenso) {
            System.out.println("► SIN CONSENSO: demasiados fallos bizantinos o timeout.\n");
        }
    }

    /*********************************************************************************************
     * EXTRAER VALOR
     *********************************************************************************************/
    private String extraerValor(String respuesta) {
        int indice = respuesta.indexOf("valor=");
        if (indice < 0) return "?";
        int inicio = indice + 6;
        int fin    = inicio;
        while (fin < respuesta.length() && Character.isDigit(respuesta.charAt(fin))) fin++;
        return respuesta.substring(inicio, fin);
    }

    /*********************************************************************************************
     * EXTRAER CONFIRMACIONES
     *********************************************************************************************/
    private int extraerConfirmaciones(String respuesta) {
        int indice = respuesta.indexOf("confirmaciones=");
        if (indice < 0) return 0;
        int inicio = indice + 15;
        int fin    = inicio;
        while (fin < respuesta.length() && Character.isDigit(respuesta.charAt(fin))) fin++;
        try {
            return Integer.parseInt(respuesta.substring(inicio, fin));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /*********************************************************************************************
     * MOSTRAR AYUDA
     *********************************************************************************************/
    public void mostrarAyuda() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           Cliente PBFT               ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.println("║  s      estado de todos los procesos ║");
        System.out.println("║  sX     proponer consenso valor X    ║");
        System.out.println("║  fN     alternar fallo proceso N     ║");
        System.out.println("║  h      ayuda                        ║");
        System.out.println("║  q      salir                        ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
        System.out.println("Ejemplos:  s42  f3  s");
        System.out.println();
    }

    /*********************************************************************************************
     * MAIN
     *********************************************************************************************/
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Uso: java cliente.Cliente ip1,ip2 [totalProcesos]");
            System.out.println("  Ejemplo: java cliente.Cliente 192.168.1.34,192.168.1.78 6");
            return;
        }

        List<String> ips = Arrays.asList(args[0].split(","));

        int totalProcesos = 0;
        if (args.length >= 2) {
            try {
                totalProcesos = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.out.println("Argumento totalProcesos inválido, se calibrará con 's'.");
            }
        }

        Cliente cliente = new Cliente(ips, totalProcesos);
        cliente.mostrarAyuda();

        try (Scanner lector = new Scanner(System.in)) {
            boolean salir = false;
            while (!salir && lector.hasNextLine()) {
                System.out.print("> ");
                System.out.flush();
                String comando = lector.nextLine().trim();
                if (comando.isEmpty()) continue;
                try {
                    switch (comando.charAt(0)) {
                        case 'q':
                            System.out.println("Saliendo.");
                            salir = true;
                            break;
                        case 'h':
                            cliente.mostrarAyuda();
                            break;
                        case 'f':
                            cliente.fallo(Integer.parseInt(comando.substring(1).trim()));
                            break;
                        case 's':
                            String resto = comando.substring(1).trim();
                            if (resto.isEmpty()) {
                                cliente.estado();
                            } else {
                                try {
                                    cliente.consenso(Integer.parseInt(resto));
                                } catch (NumberFormatException e) {
                                    System.out.println("Formato: sX  (ej: s42)");
                                }
                            }
                            break;
                        default:
                            System.out.println("Comando desconocido. Escribe 'h' para ayuda.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Error de formato numérico: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        }
    }
}