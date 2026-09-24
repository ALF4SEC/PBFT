package services;

import java.util.*;

public class Proceso extends Thread {

    private final int      identificador;
    private final Servicio servicio;

    private Integer variable          = null;
    private boolean esBizantino       = false;
    
    private long    rondaActual       = 0;

    private Map<Integer, Integer> mapaAleatorio = null;

    private final List<Integer> listaCompromisos      = new ArrayList<>();
    private final Set<Integer>  emisoresCompromiso     = new HashSet<>();

    private final List<Integer> listaComisiones        = new ArrayList<>();
    private final Set<Integer>  emisoresComision        = new HashSet<>();

    private boolean comisionEmitida     = false;
    private boolean confirmacionEmitida = false;

    public Proceso(int identificador, Servicio servicio) {
        this.identificador = identificador;
        this.servicio      = servicio;
    }

    @Override
    public void run() {
        //Reciben llamadas REST del Servicio.
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int                  getIdentificador()  { return identificador; }
    public synchronized Integer getVariable()       { return variable; }
    public synchronized boolean esBizantino()       { return esBizantino; }

    /*********************************************************************************************
     * ALTERNAR FALLO
     *********************************************************************************************/
    public synchronized void alternarFallo() {
        esBizantino = !esBizantino;
        System.out.println("[PROCESO-" + identificador + "] esBizantino=" + esBizantino);
    }

    /*********************************************************************************************
     * OBTENER COMPROMISOS COMO CADENA
     *********************************************************************************************/
    public synchronized String getCompromisosComoTexto() {
        if (listaCompromisos.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < listaCompromisos.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(listaCompromisos.get(i));
        }
        return sb.toString();
    }

    /*********************************************************************************************
     * OBTENER COMPROMISOS COMO MAPA
     * 
     * Referencia: https://docs.oracle.com/javase/8/docs/api/java/util/Map.html#merge-K-V-java.util.function.BiFunction-
     *             https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html#sum-int-int-
     * Map.merge(clave, 1, Integer::sum) actúa como contador de frecuencias: inserta 1 si
     * la clave no existe, o suma 1 al valor actual si ya existe. Integer::sum es una
     * referencia a método equivalente a (a, b) -> a + b.
     *********************************************************************************************/
    public synchronized Map<Integer, Integer> getCompromisosComoMapa() {
        Map<Integer, Integer> mapa = new HashMap<>();
        for (int v : listaCompromisos) mapa.merge(v, 1, Integer::sum);
        return mapa;
    }

    /*********************************************************************************************
     * OBTENER COMISIONES COMO MAPA
     *
     * Referencia: https://docs.oracle.com/javase/8/docs/api/java/util/Map.html#merge-K-V-java.util.function.BiFunction-
     *             https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html#sum-int-int-
     * Mismo patrón que getCompromisosComoMapa(): contador de frecuencias con merge + Integer::sum.
     *********************************************************************************************/
    public synchronized Map<Integer, Integer> getComisionesComoMapa() {
        Map<Integer, Integer> mapa = new HashMap<>();
        for (int v : listaComisiones) mapa.merge(v, 1, Integer::sum);
        return mapa;
    }

    /*********************************************************************************************
     * REINICIAR
     *********************************************************************************************/
    public synchronized void reiniciar(int valor, long nuevaRonda) {
        rondaActual         = nuevaRonda;
        variable            = null;
        listaCompromisos.clear();
        emisoresCompromiso.clear();
        listaComisiones.clear();
        emisoresComision.clear();
        comisionEmitida     = false;
        confirmacionEmitida = false;

        // Voto propio: siempre el valor real del cliente
        listaCompromisos.add(valor);
        emisoresCompromiso.add(identificador);

        if (esBizantino) {
            // Preparar mapa de valores aleatorios para la difusión byzantina
            mapaAleatorio = new HashMap<>();
            for (int pid : servicio.obtenerTodosLosIds()) {
                int valorAleatorio;
                do { valorAleatorio = (int)(Math.random() * 100); } while (valorAleatorio == valor);
                mapaAleatorio.put(pid, valorAleatorio);
            }
        } else {
            mapaAleatorio = null;
        }

        System.out.println("[PROCESO-" + identificador + "] Reinicio ronda=" + nuevaRonda
                + " valor=" + valor + " esBizantino=" + esBizantino);
    }

    /*********************************************************************************************
     * DIFUNDIR COMPROMISO
     *********************************************************************************************/
    public void difundirCompromiso() {
        Map<Integer, Integer> mapa;
        int   valor;
        long  ronda;
        synchronized (this) {
            mapa  = mapaAleatorio;
            valor = listaCompromisos.get(0);
            ronda = rondaActual;
        }
        if (mapa != null) {
            servicio.multicastCompromiso(identificador, mapa, ronda);
        } else {
            servicio.multicastCompromiso(identificador, valor, ronda);
        }
    }

    /*********************************************************************************************
     * COMPROMISO
     *********************************************************************************************/
    public void compromiso(int valor, int idEmisor, long rondaMensaje) {
        // Ignorar mensajes propios: ya añadimos el nuestro en reiniciar()
        if (idEmisor == this.identificador) return;

        int     valorComision  = -1;
        boolean emitirComision = false;
        boolean esBiz;
        long    ronda;

        synchronized (this) {
            // Descartar mensajes de rondas distintas a la actual
            if (rondaMensaje != rondaActual) return;

            if (emisoresCompromiso.contains(idEmisor)) return;
            emisoresCompromiso.add(idEmisor);
            listaCompromisos.add(valor);
            esBiz = esBizantino;
            ronda = rondaActual;

            int quorum = servicio.getQuorum();
            System.out.println("[PROCESO-" + identificador + "] Fase1b emisor=" + idEmisor
                    + " valor=" + valor + " total=" + listaCompromisos.size() + "/" + quorum);

            if (!comisionEmitida) {
                for (Map.Entry<Integer, Integer> e : getCompromisosComoMapa().entrySet()) {
                    if (e.getValue() >= quorum) {
                        comisionEmitida = true;
                        valorComision   = e.getKey();

                        if (!esBiz) {
                            emitirComision = true;
                        } else {
                            System.out.println("[PROCESO-" + identificador
                                    + "] Byzantino: suprime emision de comision");
                        }
                        break;
                    }
                }
            }
        }

        if (emitirComision) {
            System.out.println("[PROCESO-" + identificador + "] Quorum compromisos -> comision valor=" + valorComision);
            servicio.multicastComision(identificador, valorComision, ronda);
        }
    }

    /*********************************************************************************************
     * COMISION
     *********************************************************************************************/
    public void comision(int valor, int idEmisor, long rondaMensaje) {
        int     valorFinal   = -1;
        boolean emitirConf   = false;
        long    ronda        = 0;

        synchronized (this) {
            // Descartar mensajes de rondas anteriores
            if (rondaMensaje != rondaActual) return;

            if (emisoresComision.contains(idEmisor)) return;
            emisoresComision.add(idEmisor);
            listaComisiones.add(valor);

            int quorum = servicio.getQuorum();
            System.out.println("[PROCESO-" + identificador + "] Fase2a emisor=" + idEmisor
                    + " valor=" + valor + " total=" + listaComisiones.size() + "/" + quorum);

            if (!confirmacionEmitida) {
                for (Map.Entry<Integer, Integer> e : getComisionesComoMapa().entrySet()) {
                    if (e.getValue() >= quorum) {
                        confirmacionEmitida = true;
                        variable    = e.getKey();
                        valorFinal  = variable;
                        ronda       = rondaActual;
                        // Byzantino: suprime la confirmación igual que suprime la comisión
                        if (!esBizantino) {
                            emitirConf = true;
                        } else {
                            System.out.println("[PROCESO-" + identificador
                                    + "] Byzantino: suprime emision de confirmacion");
                        }
                        break;
                    }
                }
            }
        }

        if (emitirConf) {
            System.out.println("[PROCESO-" + identificador + "] Quorum comisiones -> multicast confirmacion valor=" + valorFinal);
            servicio.multicastConfirmacion(identificador, valorFinal, ronda);
        }
    }
}