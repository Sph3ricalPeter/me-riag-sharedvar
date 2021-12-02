package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RiAgNode implements Node, Runnable {

    private String ip;
    private int port;

    private boolean myRq;
    private long maxRq;
    private final List<Boolean> req = new ArrayList<>();

    private final List<Node> network = new ArrayList<>();
    private Registry registry;

    // create node without contacting gateway - create new network
    public RiAgNode() {
        init();
    }

    // create node with known gateway to the existing network
    public RiAgNode(String gatewayIP, int gatewayPort) {
        init();

        // find gateway node to fetch network info from
        try {
            var gatewayRegistry = LocateRegistry.getRegistry(gatewayIP, gatewayPort);
            Node gateway = (Node) gatewayRegistry.lookup(String.format("%s:%d", gatewayIP, gatewayPort));
            gateway.signIn(this);
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting gateway registry");
        }
    }

    private void init() {
        port = Config.REGISTRY_PORT;

        // get local ip address
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
            log.info("new node: {}:{}", ip, port);
        } catch (UnknownHostException e) {
            log.error("error getting local ip address");
        }

        // register node as remote object on given port
        try {
            registry = LocateRegistry.createRegistry(port);
            registry.rebind(String.format("%s:%d", ip, port), this);
            log.info("created registry on port: {}", port);
        } catch (RemoteException e) {
            log.error("error creating registry");
        }
    }

    @Override
    public void signIn(Node newNode) {
        // skip if known
        if (network.contains(newNode)) {
            log.info("node is already in the network");
            return;
        }

        // add and propagate
        network.add(newNode);
        for (Node node : network) {
            node.signIn(newNode);
        }
        log.info("added {} to the network, total nodes: {}", newNode, network.size());
    }

    @Override
    public void signOut(Node newNode) {
        // skip if now known
        if (!network.contains(newNode)) {
            log.info("node is removed already");
            return;
        }

        // add and propagate
        network.remove(newNode);
        for (Node node : network) {
            node.signOut(newNode);
        }
        log.info("removed {} from the network, total nodes: {}", newNode, network.size());
    }

    @Override
    public void receiveRequest(int ts, int id) throws RemoteException {

    }

    @Override
    public void receiveResponse(int lc, int id) throws RemoteException {

    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.info("sleeping error");
            }
        }
    }
}
