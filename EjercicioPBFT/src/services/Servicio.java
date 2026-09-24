package services;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.*;
import java.net.InetAddress;
import java.util.*;

@Singleton
@Path("servicio")
public class Servicio {

    private final List<Proceso> procesos = new ArrayList<>();
    private final List<String> ips = new ArrayList<>();
    private final Map<String, List<Integer>> ipAIds  = new LinkedHashMap<>();
    private String  miIp   = "";
    private int totalProcesos = 0;

    private final Object bloqueoConfirmacion = new Object();
    private final List<Integer> valoresConfirmacion = new ArrayList<>();
    private final Set<Integer>  emisoresConfirmacion = new HashSet<>();
    private Integer valorConfirmado = null;
    private boolean consensoListo = false;
    private long rondaActual = 0;

    private Client clienteHttp;

    /*********************************************************************************************
     * CONSTRUCTOR
     * 
     * Referencia (Properties + FileInputStream):
     *   https://docs.oracle.com/javase/8/docs/api/java/util/Properties.html
     *   https://docs.oracle.com/javase/8/docs/api/java/io/FileInputStream.html
     * Properties es una tabla clave=valor pensada para ficheros de configuración.
     * load(InputStream) rellena la tabla leyendo el fichero línea a línea.
     * FileInputStream abre el fichero en disco a partir de su ruta absoluta.
     * System.getProperty("user.home") devuelve el directorio personal del usuario
     * de forma portable entre sistemas operativos.
     *
     * Referencia (InetAddress):
     *   https://docs.oracle.com/javase/8/docs/api/java/net/InetAddress.html
     * InetAddress.getLocalHost() devuelve el objeto InetAddress de la máquina local.
     * getHostName() obtiene de él el nombre de host configurado en el sistema operativo,
     * que usamos para identificar qué entrada del config.txt corresponde a esta máquina.
     *
     * Referencia (ClientBuilder):
     *   https://eclipse-ee4j.github.io/jersey.github.io/documentation/latest/client.html
     * ClientBuilder.newClient() crea un cliente JAX-RS listo para hacer peticiones HTTP.
     * Es el punto de entrada de la API cliente de Jersey; a partir de él se construyen
     * targets (URLs) y se encadenan parámetros, cabeceras y el tipo de respuesta esperado.
     *********************************************************************************************/
    public Servicio() {
        Properties propiedades = new Properties();
        try {
            String rutaEscritorio = System.getProperty("user.home") + "/Escritorio/config.txt";
            System.out.println("[INIT] Leyendo config.txt desde: " + rutaEscritorio);
            propiedades.load(new java.io.FileInputStream(rutaEscritorio));
        } catch (Exception e) {
            System.err.println("[INIT] Error leyendo config.txt del escritorio: " + e.getMessage());
            return;
        }

        totalProcesos = Integer.parseInt(propiedades.getProperty("totalProcesos").trim());
        String[] entradas = propiedades.getProperty("maquinas").split(",");

        int idActual = 1;
        for (String entrada : entradas) {
            String[] partes     = entrada.trim().split(":");
            String   ip         = partes[1].trim();
            int      nProcesos  = Integer.parseInt(partes[2].trim());
            ips.add(ip);
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < nProcesos; i++) ids.add(idActual++);
            ipAIds.put(ip, ids);
        }

        String miHostname;
        try {
            miHostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            System.err.println("[INIT] Error obteniendo hostname: " + e.getMessage());
            return;
        }

        int     procesosLocales = 0;
        int     idInicio        = 1;
        boolean encontrado      = false;

        for (String entrada : entradas) {
            String[] partes   = entrada.trim().split(":");
            String   hostname = partes[0].trim();
            String   ip       = partes[1].trim();
            int      nProc    = Integer.parseInt(partes[2].trim());
            if (hostname.equals(miHostname)) {
                miIp            = ip;
                procesosLocales = nProc;
                encontrado      = true;
                break;
            }
            idInicio += nProc;
        }

        if (!encontrado) {
            System.err.println("[INIT] Hostname '" + miHostname + "' no encontrado en config.txt.");
            return;
        }

        clienteHttp = ClientBuilder.newClient();
        for (int i = 0; i < procesosLocales; i++)
            procesos.add(new Proceso(idInicio + i, this));

        System.out.println("[INIT] Servicio listo en " + miIp + " (" + miHostname + ")");
        System.out.println("[INIT] totalProcesos=" + totalProcesos + "  quorum=" + getQuorum());
        System.out.println("[INIT] Procesos locales: IDs " + idInicio + "-" + (idInicio + procesosLocales - 1));
    }

    /*********************************************************************************************
     * OBTENER QUORUM
     * Calcula el quórum PBFT: tolera f fallos byzantinos con n >= 3f+1 nodos.
     * Quórum = 2f+1, donde f = (totalProcesos - 1) / 3.
     *********************************************************************************************/
    public int getQuorum() {
        int f = (totalProcesos - 1) / 3;
        return 2 * f + 1;
    }

    /*********************************************************************************************
     * OBTENER TODOS LOS IDS
     * Devuelve una lista con los identificadores de todos los procesos del sistema,
     * recorriendo el mapa ip -> lista de IDs de todas las máquinas.
     *********************************************************************************************/
    public List<Integer> obtenerTodosLosIds() {
        List<Integer> todos = new ArrayList<>();
        for (List<Integer> ids : ipAIds.values()) todos.addAll(ids);
        return todos;
    }

    //EndPoints

    /*********************************************************************************************
     * ESTABLECER (Fase 1a)
     *
     * Referencia (Thread.currentThread().interrupt()):
     *   https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html#interrupt--
     * Cuando un hilo es interrumpido mientras espera en wait(), la excepción
     * InterruptedException borra la bandera de interrupción del hilo. 
     * interrupt() la restaura para que el código que llame después pueda detectar que el hilo
     * fue interrumpido, siguiendo el contrato estándar de Java.
     *********************************************************************************************/
    @GET 
    @Path("set") 
    @Produces(MediaType.TEXT_PLAIN)
    public String establecer(@QueryParam("valor") int valor) {

        synchronized (bloqueoConfirmacion) {
            // 1) Reset del estado de confirmaciones del Servicio
            valoresConfirmacion.clear();
            emisoresConfirmacion.clear();
            valorConfirmado = null;
            consensoListo   = false;
            rondaActual++;
            final long miRonda = rondaActual;

            // 2) Reiniciar TODOS los procesos locales primero (síncrono),
            //    antes de lanzar cualquier multicast, para que no descarten
            //    mensajes de la nueva ronda por tener aún la ronda anterior.
            for (Proceso p : procesos) p.reiniciar(valor, miRonda);

            // 3) Lanzar los multicasts en hilos una vez que todos están listos
            for (Proceso p : procesos) {
                new Thread(() -> p.difundirCompromiso()).start();
            }

            // 4) Esperar quórum de confirmaciones
            long limite = System.currentTimeMillis() + 25_000;
            while (!consensoListo) {
                long tiempoRestante = limite - System.currentTimeMillis();
                if (tiempoRestante <= 0) break;
                try {
                    bloqueoConfirmacion.wait(tiempoRestante);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            int quorum      = getQuorum();
            int confirmadas = emisoresConfirmacion.size();
            if (valorConfirmado != null)
                return "CONSENSO ALCANZADO: valor=" + valorConfirmado
                        + " confirmaciones=" + confirmadas + "/" + totalProcesos;
            return "SIN CONSENSO: timeout o demasiados fallos bizantinos"
                    + " (" + valoresConfirmacion.size() + "/" + quorum + " confirmaciones)";
        }
    }

    /*********************************************************************************************
     * CONFIRMACION (Fase 2b)
     *********************************************************************************************/
    @GET @Path("confirmacion") @Produces(MediaType.TEXT_PLAIN)
    public String endpointConfirmacion(@QueryParam("valor")  int  valor,
                                       @QueryParam("emisor") int  emisor,
                                       @QueryParam("ronda")  long ronda) {
        registrarConfirmacion(emisor, valor, ronda);
        return "OK";
    }

    /*********************************************************************************************
     * REGISTRAR CONFIRMACION
     *
     * Referencia (Map.merge + Integer::sum):
     *   https://docs.oracle.com/javase/8/docs/api/java/util/Map.html#merge-K-V-java.util.function.BiFunction-
     *   https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html#sum-int-int-
     * Map.merge(clave, 1, Integer::sum) actúa como contador de frecuencias: inserta 1 si
     * la clave no existe, o suma 1 al valor actual si ya existe. Integer::sum es una
     * referencia a método equivalente a a + b.
     *********************************************************************************************/
    public void registrarConfirmacion(int idEmisor, int valor, long ronda) {
        synchronized (bloqueoConfirmacion) {
            // Descartar si pertenece a una ronda antigua o el emisor ya fue contado
            if (ronda != rondaActual || consensoListo || emisoresConfirmacion.contains(idEmisor)) return;

            emisoresConfirmacion.add(idEmisor);
            valoresConfirmacion.add(valor);

            int quorum = getQuorum();
            Map<Integer, Integer> conteo = new HashMap<>();
            for (int v : valoresConfirmacion) conteo.merge(v, 1, Integer::sum);

            System.out.println("[CONFIRMACION] emisor=" + idEmisor
                    + " total=" + valoresConfirmacion.size() + "/" + quorum
                    + " conteo=" + conteo);

            for (Map.Entry<Integer, Integer> e : conteo.entrySet()) {
                if (e.getValue() >= quorum) {
                    valorConfirmado = e.getKey();
                    consensoListo   = true;
                    bloqueoConfirmacion.notifyAll();
                    System.out.println("[CONFIRMACION] Quorum alcanzado, valor=" + valorConfirmado);
                    break;
                }
            }
        }
    }

    /*********************************************************************************************
     * MULTICAST CONFIRMACION (Fase 2b)
     *
     * Referencia (async InvocationCallback):
     *   https://eclipse-ee4j.github.io/jersey.github.io/documentation/latest/async.html
     * async().get(InvocationCallback) lanza la petición HTTP en un hilo separado gestionado
     * por Jersey, sin bloquear el hilo actual. 
     * completed() se invoca si la respuesta llega correctamente
     * failed() se invoca si hay un error de red o timeout.
     *********************************************************************************************/
    public void multicastConfirmacion(int idEmisor, int valor, long ronda) {
        for (String ip : ips) {
            if (ip.equals(miIp)) {
                final int  v = valor;
                final int  e = idEmisor;
                final long r = ronda;
                new Thread(() -> registrarConfirmacion(e, v, r)).start();
            } else {
                clienteHttp.target(construirUrl(ip, "confirmacion"))
                        .queryParam("valor",  valor)
                        .queryParam("emisor", idEmisor)
                        .queryParam("ronda",  ronda)
                        .request(MediaType.TEXT_PLAIN)
                        .async().get(new InvocationCallback<String>() {
                            public void completed(String r) {}
                            public void failed(Throwable t) {
                                System.err.println("[MCAST-CONFIRMACION] Error -> " + ip + ": " + t.getMessage());
                            }
                        });
            }
        }
    }

    /*********************************************************************************************
     * COMPROMISO (Fase 1b, normal)
     *********************************************************************************************/
    @GET 
    @Path("compromiso") 
    @Produces(MediaType.TEXT_PLAIN)
    public String endpointCompromiso(@QueryParam("valor")  int  valor,
                                     @QueryParam("emisor") int  emisor,
                                     @QueryParam("ronda")  long ronda) {
        compromisoLocal(valor, emisor, ronda);
        return "OK";
    }

    /*********************************************************************************************
     * COMPROMISO BIZANTINO (Fase 1b, byzantino)
     *********************************************************************************************/
    @GET 
    @Path("compromisoByzantine") 
    @Produces(MediaType.TEXT_PLAIN)
    public String endpointCompromisoBizantino(@QueryParam("mapa")   String mapaStr,
                                              @QueryParam("emisor") int    emisor,
                                              @QueryParam("ronda")  long   ronda) {
        Map<Integer, Integer> mapa = deserializarMapa(mapaStr);
        for (Proceso p : procesos) {
            int valor = mapa.getOrDefault(p.getIdentificador(), (int)(Math.random() * 100));
            p.compromiso(valor, emisor, ronda);
        }
        return "OK";
    }

    /*********************************************************************************************
     * COMISION (Fase 2a, normal)
     *********************************************************************************************/
    @GET 
    @Path("comision") 
    @Produces(MediaType.TEXT_PLAIN)
    public String endpointComision(@QueryParam("valor")  int  valor,
                                   @QueryParam("emisor") int  emisor,
                                   @QueryParam("ronda")  long ronda) {
        comisionLocal(valor, emisor, ronda);
        return "OK";
    }

    /*********************************************************************************************
     * COMISION BIZANTINA (Fase 2a, byzantino)
     *********************************************************************************************/
    @GET 
    @Path("comisionByzantine")
    @Produces(MediaType.TEXT_PLAIN)
    public String endpointComisionBizantina(@QueryParam("mapa")   String mapaStr,
                                            @QueryParam("emisor") int    emisor,
                                            @QueryParam("ronda")  long   ronda) {
        Map<Integer, Integer> mapa = deserializarMapa(mapaStr);
        for (Proceso p : procesos) {
            int valor = mapa.getOrDefault(p.getIdentificador(), (int)(Math.random() * 100));
            p.comision(valor, emisor, ronda);
        }
        return "OK";
    }

    /*********************************************************************************************
     * FALLO
     *********************************************************************************************/
    @GET 
    @Path("fallo") 
    @Produces(MediaType.TEXT_PLAIN)
    public String fallo(@QueryParam("id") int id) {
        for (Proceso p : procesos) {
            if (p.getIdentificador() == id) {
                p.alternarFallo();
                return "Proceso " + id + ": esBizantino=" + p.esBizantino();
            }
        }
        return "Proceso " + id + " no encontrado en " + miIp;
    }

    /*********************************************************************************************
     * ESTADO
     *********************************************************************************************/
    @GET 
    @Path("estado") 
    @Produces(MediaType.TEXT_PLAIN)
    public String estado() {
        StringBuilder sb = new StringBuilder("id\tvar\tcompromisos\terror\n");
        for (Proceso p : procesos) {
            sb.append(p.getIdentificador()).append("\t")
              .append(p.getVariable() != null ? p.getVariable() : "-").append("\t")
              .append(p.getCompromisosComoTexto()).append("\t")
              .append(p.esBizantino()).append("\n");
        }
        return sb.toString();
    }

    //Funciones multicast sin error

    /*********************************************************************************************
     * MULTICAST COMPROMISO
     *********************************************************************************************/
    public void multicastCompromiso(int idEmisor, int valor, long ronda) {
        for (String ip : ips) {
            if (ip.equals(miIp)) {
                new Thread(() -> compromisoLocal(valor, idEmisor, ronda)).start();
            } else {
                clienteHttp.target(construirUrl(ip, "compromiso"))
                        .queryParam("valor",  valor)
                        .queryParam("emisor", idEmisor)
                        .queryParam("ronda",  ronda)
                        .request(MediaType.TEXT_PLAIN)
                        .async().get(new InvocationCallback<String>() {
                            public void completed(String r) {}
                            public void failed(Throwable t) {
                                System.err.println("[MCAST-COMPROMISO] Error -> " + ip + ": " + t.getMessage());
                            }
                        });
            }
        }
    }

    /*********************************************************************************************
     * MULTICAST COMISION
     *********************************************************************************************/
    public void multicastComision(int idEmisor, int valor, long ronda) {
        for (String ip : ips) {
            if (ip.equals(miIp)) {
                new Thread(() -> comisionLocal(valor, idEmisor, ronda)).start();
            } else {
                clienteHttp.target(construirUrl(ip, "comision"))
                        .queryParam("valor",  valor)
                        .queryParam("emisor", idEmisor)
                        .queryParam("ronda",  ronda)
                        .request(MediaType.TEXT_PLAIN)
                        .async().get(new InvocationCallback<String>() {
                            public void completed(String r) {}
                            public void failed(Throwable t) {
                                System.err.println("[MCAST-COMISION] Error -> " + ip + ": " + t.getMessage());
                            }
                        });
            }
        }
    }

    //Funciones Multidifusion con error Byzantino
    /*********************************************************************************************
     * MULTICAST COMPROMISO (byzantino)
     *********************************************************************************************/
    public void multicastCompromiso(int idEmisor, Map<Integer, Integer> mapaValores, long ronda) {
        for (String ip : ips) {
            if (ip.equals(miIp)) {
                new Thread(() -> {
                    for (Proceso p : procesos) {
                        int valor = mapaValores.getOrDefault(p.getIdentificador(), (int)(Math.random() * 100));
                        p.compromiso(valor, idEmisor, ronda);
                    }
                }).start();
            } else {
                clienteHttp.target(construirUrl(ip, "compromisoByzantine"))
                        .queryParam("mapa",   serializarMapa(mapaValores))
                        .queryParam("emisor", idEmisor)
                        .queryParam("ronda",  ronda)
                        .request(MediaType.TEXT_PLAIN)
                        .async().get(new InvocationCallback<String>() {
                            public void completed(String r) {}
                            public void failed(Throwable t) {
                                System.err.println("[MCAST-COMPROMISO-BYZ] Error -> " + ip + ": " + t.getMessage());
                            }
                        });
            }
        }
    }

    /*********************************************************************************************
     * MULTICAST COMISION (byzantino)
     *********************************************************************************************/
    public void multicastComision(int idEmisor, Map<Integer, Integer> mapaValores, long ronda) {
        for (String ip : ips) {
            if (ip.equals(miIp)) {
                new Thread(() -> {
                    for (Proceso p : procesos) {
                        int valor = mapaValores.getOrDefault(p.getIdentificador(), (int)(Math.random() * 100));
                        p.comision(valor, idEmisor, ronda);
                    }
                }).start();
            } else {
                clienteHttp.target(construirUrl(ip, "comisionByzantine"))
                        .queryParam("mapa",   serializarMapa(mapaValores))
                        .queryParam("emisor", idEmisor)
                        .queryParam("ronda",  ronda)
                        .request(MediaType.TEXT_PLAIN)
                        .async().get(new InvocationCallback<String>() {
                            public void completed(String r) {}
                            public void failed(Throwable t) {
                                System.err.println("[MCAST-COMISION-BYZ] Error -> " + ip + ": " + t.getMessage());
                            }
                        });
            }
        }
    }

    //Funciones auxiliares
    /*********************************************************************************************
     * COMPROMISO LOCAL
     *********************************************************************************************/
    private void compromisoLocal(int valor, int emisor, long ronda) {
        for (Proceso p : procesos)
            if (p.getIdentificador() != emisor) p.compromiso(valor, emisor, ronda);
    }

    /*********************************************************************************************
     * COMISION LOCAL
     *********************************************************************************************/
    private void comisionLocal(int valor, int emisor, long ronda) {
        for (Proceso p : procesos) p.comision(valor, emisor, ronda);
    }

    /*********************************************************************************************
     * CONSTRUIR URL
     *********************************************************************************************/
    private String construirUrl(String ip, String ruta) {
        return "http://" + ip + ":8080/EjercicioPBFT/rest/servicio/" + ruta;
    }

    /*********************************************************************************************
     * SERIALIZAR MAPA
     *********************************************************************************************/
    private String serializarMapa(Map<Integer, Integer> mapa) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : mapa.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append(":").append(e.getValue());
        }
        return sb.toString();
    }

    /*********************************************************************************************
     * DESERIALIZAR MAPA
     *********************************************************************************************/
    private Map<Integer, Integer> deserializarMapa(String mapaStr) {
        Map<Integer, Integer> mapa = new HashMap<>();
        if (mapaStr == null || mapaStr.isEmpty()) return mapa;
        for (String par : mapaStr.split(",")) {
            String[] claveValor = par.split(":");
            if (claveValor.length == 2) {
                try {
                    mapa.put(Integer.parseInt(claveValor[0].trim()), Integer.parseInt(claveValor[1].trim()));
                } catch (NumberFormatException ignorado) {}
            }
        }
        return mapa;
    }
}