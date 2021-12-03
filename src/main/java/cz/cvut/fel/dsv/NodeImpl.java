package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class NodeImpl extends UnicastRemoteObject implements Node, Runnable {

    public enum State {
        WANTED,
        HELD,
        RELEASED
    }

    private ID id;

    private int myClock;
    private int maxClock;
    private int respCount;
    private State state;

    private volatile Integer requestedVariable;
    private Integer sharedVariable;

    private final Map<ID, Node> remotes = new TreeMap<>();
    private final List<Boolean> reqs = new ArrayList<>();
    private Registry registry;

    // create node without contacting gateway - create new network
    public NodeImpl() throws RemoteException, UnknownHostException {
        super();
        init(1);
    }

    // create node with known gateway to the existing network
    public NodeImpl(ID gatewayID) throws RemoteException, UnknownHostException {
        super();

        // find gateway node to fetch network info from
        try {
            var gatewayRegistry = LocateRegistry.getRegistry(gatewayID.getIp(), gatewayID.getPort());
            Node gateway = (Node) gatewayRegistry.lookup(gatewayID.toString());
            init(gateway.signIn(gatewayID));
            log.info("signed in to gateway");
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting gateway registry at {}", gatewayID, e);
        }
    }

    private void init(int n) throws UnknownHostException {
        id = new ID(n, InetAddress.getLocalHost().getHostAddress(), Config.REGISTRY_PORT);

        log.info("new node: {}", id);

        // register node as remote object on given port
        try {
            registry = LocateRegistry.createRegistry(id.getPort());
            registry.rebind(id.toString(), this);
            log.info("created registry on port: {}", id.getPort());
        } catch (RemoteException e) {
            log.error("error creating registry");
        }

        // init algo
        maxClock = 0;
        myClock = 0;
        state = State.RELEASED;

        log.info("initialized {}", this);
    }

    public ID getIdentifier() {
        return id;
    }

    @Override
    public int signIn(ID id) throws RemoteException {
        Node newNode;
        try {
            var gatewayRegistry = LocateRegistry.getRegistry(id.getIp(), id.getPort());
            newNode = (Node) gatewayRegistry.lookup(id.toString());
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting registry at {}", id, e);
            return -1;
        }

        // skip if known
        if (remotes.containsKey(id)) {
            log.info("node is already in the network");
            return -1;
        }

        // add node to all remotes except the one that's signing in
        for (Node node : remotes.values()) {
            node.signIn(id);
        }

        remotes.put(id, newNode);
        reqs.add(false);
        log.info("added {} to the network", newNode);

        log.info("{}", this);
        return remotes.size();
    }

    @Override
    public void signOut(ID id) throws RemoteException {
        if (!remotes.containsKey(id)) {
            log.info("node is removed already");
            return;
        }

        remotes.remove(id);
        log.info("removed {} from the network", id);

        for (Node node : remotes.values()) {
            node.signOut(id);
        }
        log.info("{}", this);
    }

    @Override
    public void receiveRequest(Request request) throws RemoteException {
        maxClock = Math.max(maxClock, request.getFromClock());

        Node remoteNode = remotes.stream().filter(node -> {
            try {
                return node.getIdentifier().equals(fromIdentifier);
            } catch (RemoteException e) {
                log.error("cant get identifier", e);
            }
            return false;
        }).collect(Collectors.toList()).get(0);
        final var nodeId = remotes.indexOf(remoteNode);

        final var delay = reqs.get(id.getN()) && (request.getFromClock() > myClock || request.getFromClock() == myClock && nodeId > myId);

        if (delay) {
            reqs.set(nodeId, true);
            log.info("received request and waiting ...");
        } else {
            remotes.get(nodeId).receiveResponse(new Response());
            log.info("received request and responding empty ...");
        }
    }

    @Override
    public void receiveResponse(Response response) throws RemoteException {
        respCount++;

        if (respCount < remotes.size() - 1)
            return;

        // received all responses needed to enter critical section
        // CRITICAL SECTION
        state = State.HELD;

        log.info("HELD and changing variable {} to {} ...", sharedVariable, requestedVariable);
        synchronized (this) {
            sharedVariable = requestedVariable;
        }

        try {
            var sleepTime = Utils.getRandomLong(Config.CRITICAL_WAIT_MIN_MS, Config.CRITICAL_WAIT_MAX_MS);
            log.info("sleeping for {} ms", sleepTime);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.error("failed to sleep ...", e);
        }

        state = State.RELEASED;
        log.info("RELEASED and replying to requests ...");

        final var i = remotes.indexOf(this);
        for (var j = 0; j < remotes.size(); ++j) {
            if (i == j)
                continue;

            remotes.get(j).receivePayload(sharedVariable);

            if (reqs.get(j)) {
                reqs.set(j, false);
                remotes.get(j).receiveResponse(new Response(sharedVariable));
            }
        }
    }

    @Override
    public void receivePayload(Integer payload) {
        sharedVariable = payload;
    }

    @Override
    public void run() {
        final var scanner = new Scanner(System.in);

        while (true) {
            // get input from command line
            final var line = scanner.nextLine();

            switch (line) {
                case "print":
                    log.info(this.toString());
                    continue;
                case "exit":
                    try {
                        remotes.get(0).signOut(this);
                    } catch (RemoteException e) {
                        log.error("failed to sign out", e);
                    }
                    continue;
                default:
                    break;
            }

            try {
                requestedVariable = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                log.error("failed to parse input string to int", e);
            }

            // change state to wanted, request access to critical section
            state = State.WANTED;
            log.info("WANTED and requesting access ...");

            myClock++;

            respCount = 0;
            for (var j = 0; j < remotes.size(); ++j) {
                try {
                    remotes.get(j).receiveRequest(new Request(myClock, ip, port));
                } catch (RemoteException e) {
                    log.error("failed request to node {}", j, e);
                }
            }
        }
    }

    @Override
    public String toString() {
        var builder = new StringBuilder();

        // print in format: 192.168.56.101:2010 - 2 peers: [192.168.56.102:2010, 192.168.56.103:2013]
        builder.append(String.format("%s - %d peers: [", getIdentifier(ip, port), remotes.size() - 1));
        for (var j = 0; j < remotes.size(); ++j) {
            try {
                builder.append(String.format(j == remotes.size() - 1 ? "%s" : "%s, ", remotes.get(j).getIdentifier()));
            } catch (RemoteException e) {
                log.error("can't get identifier for node {}", j);
            }
        }
        builder.append("]");

        return builder.toString();
    }
}
