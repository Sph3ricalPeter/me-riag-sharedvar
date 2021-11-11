package cz.cvut.fel.dsv;

import lombok.Builder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.Random;

@Slf4j
public class SimpleNode extends UnicastRemoteObject implements Node, Runnable {

    private final int id;
    private int lc;
    private int[] rq;
    private int[] ts;

    private Registry registry;
    private Node[] nodes;

    public SimpleNode(int id) throws RemoteException {
        super();
        this.id = id;
        init();
    }

    public int getId() {
        return id;
    }

    private void init() throws RemoteException {
        lc = 0;
        rq = new int[Config.N_NODES];
        Arrays.fill(rq, Integer.MAX_VALUE);
        ts = new int[Config.N_NODES];
        Arrays.fill(ts, 0);

        // register node as remote object on given port
        registry = LocateRegistry.createRegistry(Config.REGISTRY_PORT + id);
        registry.rebind(String.format("N%d", id), this);

        nodes = new Node[Config.N_NODES];
    }

    @Override
    public void receiveRequest(int tsOther, int idOther) throws NotBoundException, RemoteException, InterruptedException {
        discover(idOther);
        updateClock(tsOther);

        rq[idOther] = tsOther;
        ts[idOther] = tsOther;

        nodes[idOther].receiveResponse(lc, idOther);
    }

    @Override
    public void receiveResponse(int tsOther, int idOther) throws InterruptedException, RemoteException {
        discover(idOther);
        updateClock(tsOther);

        ts[idOther] = tsOther;

        // check if can enter critical section
        for (int i = 0; i < Config.N_NODES; ++i) {
            if (id == idOther) {
                continue;
            }

            // at least one node has not responded
            if (rq[id] >= rq[idOther] || rq[id] >= ts[idOther]) {
                return;
            }
        }

        log.info("N{} entered critical section", id);
        Thread.sleep(randomRangeInt(1000, 3000));
        log.info("N{} exited critical section", id);
    }

    private void discover(int id) {
        try {
            registry = LocateRegistry.getRegistry("localhost", Config.REGISTRY_PORT + id);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (nodes[id] == null) {
            try {
                nodes[id] = (Node) registry.lookup(String.format("N%d", id));
            } catch (Exception e) {
                log.warn("can't bind N{}: {}", id, e.getMessage());
            }
        }
    }

    private void updateClock(int ts) {
        lc = Math.max(lc, ts);
        lc++;
    }

    public int randomRangeInt(int min, int max) {
        Random random = new Random();
        return random.ints(min, max)
                .findFirst()
                .getAsInt();
    }

    @Override
    public void run() {
        // repeatedly try to request access to the critical section every 1 to 3 seconds
        while (true) {
            rq[id] = lc;
            ts[id] = lc;
            lc++;

            // look for nodes in registry, add them to the list
            for (var i = 0; i < nodes.length; ++i) {
                discover(i);
            }

            // request from all nodes
            for (var i = 0; i < nodes.length; ++i) {
                if (nodes[i] == null || nodes[i].equals(this)) {
                    continue;
                }
                try {
                    nodes[i].receiveRequest(ts[id], id);
                } catch (Exception e) {
                    log.warn("N{} can't receive request: {}", id, e.getMessage());
                }
            }

            try {
                Thread.sleep(randomRangeInt(1000, 3000));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
