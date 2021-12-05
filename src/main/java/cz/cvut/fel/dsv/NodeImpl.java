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

@Slf4j
public class NodeImpl extends UnicastRemoteObject implements Node, Runnable {

    public enum State {
        REQUESTING,
        HELD,
        RELEASED
    }

    private ID myId;

    private int myClock;
    private int clockMax;
    private int responseCount;
    private State state;
    private Registry myRegistry;

    private volatile Integer requestedVariable;
    private Integer sharedVariable;

    private int nMax;
    private final TreeMap<ID, Node> remotes = new TreeMap<>();
    private final TreeMap<ID, Boolean> remotesReqFlags = new TreeMap<>();

    // create node without contacting gateway - create new network
    public NodeImpl() throws RemoteException, UnknownHostException {
        super();
        init();
    }

    // create node with known gateway to the existing network
    public NodeImpl(String gatewayIp, int gatewayPort) throws RemoteException, UnknownHostException {
        super();
        init();

        // find gateway node to fetch network info from
        Node gateway = tryLocateRemoteNode(new ID(gatewayIp, gatewayPort));
        if (Objects.isNull(gateway))
            return;

        nMax = gateway.addNode(myId.getIp(), myId.getPort());
        if (nMax < 0) {
            log.error("failed getting node number");
            return;
        }

        myId.setN(nMax);
        log.info("signed in to gateway and got nMax={}", nMax);
    }

    private void init() throws UnknownHostException {
        nMax = 1;
        myId = new ID(InetAddress.getLocalHost().getHostAddress(), Config.REGISTRY_PORT, nMax);
        log.info("new node: {}", myId);

        // register node as remote object on given port
        try {
            myRegistry = LocateRegistry.createRegistry(myId.getPort());
            myRegistry.rebind(myId.getName(), this);
            log.info("created registry on port: {}", myId.getPort());
        } catch (RemoteException e) {
            log.error("error creating registry");
            return;
        }

        // init algo
        clockMax = 0;
        myClock = 0;
        state = State.RELEASED;

        log.info("initialized {}", this);
    }

    /**
     * Adds a node to the remote node and its neighbors
     * @param newNodeIp ip of new node
     * @param newNodePort port of new node
     * @return number of the node being added, -1 if error occurs
     * @throws RemoteException
     */
    @Override
    public int addNode(String newNodeIp, int newNodePort) throws RemoteException {
        nMax += 1;
        final var newNodeId = new ID(newNodeIp, newNodePort, nMax);

        // skip if known
        if (remotes.containsKey(newNodeId)) {
            log.info("node {} is already in the network", newNodeId);
            return -1;
        }

        Node newNode = tryLocateRemoteNode(newNodeId);
        if (Objects.isNull(newNode))
            return -1;

        // add new node to remotes
        remotes.put(newNodeId, newNode);
        remotesReqFlags.put(newNodeId, false);
        log.info("added {} to the network", newNodeId);

        // multicast updated remotes set to all remotes including the new node
        final var idSet = new HashSet<>(remotes.keySet());
        idSet.add(myId); // include this node
        for (Node remote : remotes.values()) {
            remote.updateRemotes(idSet);
        }

        log.info("{}", this);
        return nMax;
    }

    @Override
    public void removeNode(ID nodeId) throws RemoteException {
        if (!remotes.containsKey(nodeId)) {
            log.info("node is removed already");
            return;
        }

        remotes.remove(nodeId);
        remotesReqFlags.remove(nodeId);
        log.info("removed {} from the network", nodeId);

        var idSet = new HashSet<>(remotes.keySet());
        for (Node remote : remotes.values()) {
            remote.updateRemotes(idSet);
        }
        log.info("{}", this);
    }

    @Override
    public void updateRemotes(HashSet<ID> updatedRemotes) throws RemoteException {
        // add new records from updated remotes
        for (ID newRemoteId : updatedRemotes) {
            if (remotes.containsKey(newRemoteId) || newRemoteId.equals(myId)) // skip existing or myself
                continue;

            final var newNode  = tryLocateRemoteNode(newRemoteId);
            if (Objects.isNull(newNode))
                continue;

            remotes.put(newRemoteId, newNode);
            remotesReqFlags.put(newRemoteId, false);
        }

        // remove old remotes
        for (ID remoteId : remotes.keySet()) {
            if (updatedRemotes.contains(remoteId))
                continue;

            remotes.remove(remoteId);
            remotesReqFlags.remove(remoteId);
        }
        log.info("updated remotes list {}", this);
    }

    @Override
    public void receiveRequest(Request request) throws RemoteException {
        try {
            final var sleepTime = 3_000;
            log.info("receiving request takes {} ...", sleepTime);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.error("failed sleeping ...", e);
        }

        clockMax = Math.max(clockMax, request.getSenderClock());

        final var isReqClockAhead = request.getSenderClock() > myClock;
        final var isClockSameAndReqNumHigher = (request.getSenderClock() == myClock && request.getSenderId().getN() > myId.getN());

        final var delay = state.equals(State.REQUESTING) && (isReqClockAhead || isClockSameAndReqNumHigher);

        log.info("delay = state={} && (isReqClockAhead={} || isClockSameAndReqNumHigher={}", state, isReqClockAhead, isClockSameAndReqNumHigher);

        if (delay) {
            remotesReqFlags.put(request.getSenderId(), true);
            log.info("{} received request and waiting ...", strclk());
            return;
        }

        remotes.get(request.getSenderId()).receiveResponse(new Response());
        log.info("{} received request and responding empty ...", strclk());
    }

    @Override
    public void receiveResponse(Response response) throws RemoteException {
        responseCount++;

        if (responseCount < remotes.size())
            return;

        // received all responses needed to enter critical section
        // CRITICAL SECTION
        state = State.HELD;

        log.info("{} HELD and changing variable {} to {} ...", strclk(), sharedVariable, requestedVariable);
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
        log.info("{} RELEASED and replying to requests ...", strclk());

        for (ID remoteId : remotes.keySet()) {
            remotes.get(remoteId).updateVariable(sharedVariable);

            if (remotesReqFlags.get(remoteId)) {
                remotesReqFlags.replace(remoteId, false);
                remotes.get(remoteId).receiveResponse(new Response());
            }
        }
    }

    @Override
    public void updateVariable(Integer payload) {
        sharedVariable = payload;
    }

    @Override
    public void run() {
        final var scanner = new Scanner(System.in);

        while (true) {
            // get input from command line
            final var line = scanner.nextLine();
            final var args = line.split(" ");

            switch (args[0]) {
                case "print":
                    log.info(this.toString());
                    continue;
                case "logout":
                    if (remotes.size() < 1) {
                        log.warn("can't log out, this is only node in the network");
                        continue;
                    }
                    try {
                        // tell any node to remove this node
                        remotes.get(remotes.keySet().iterator().next()).removeNode(myId);
                    } catch (RemoteException e) {
                        log.error("failed to sign out", e);
                    }
                    remotes.clear();
                    remotesReqFlags.clear();
                    continue;
                case "login":
                    if (args.length < 3) {
                        log.warn("invalid arguments");
                        continue;
                    }
                    try {
                        final var gateway = tryLocateRemoteNode(new ID(args[1], Integer.parseInt(args[2])));
                        nMax = gateway.addNode(myId.getIp(), myId.getPort());
                        myId.setN(nMax);
                    } catch (RemoteException e) {
                        log.error("failed to log in", e);
                    }
                    continue;
                case "exit":
                    System.exit(0);
                default:
                    break;
            }

            try {
                requestedVariable = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                log.error("failed to parse input string to int", e);
                return;
            }

            // change state to wanted, request access to critical section
            state = State.REQUESTING;
            myClock = clockMax + 1;

            log.info("{} WANTED and requesting access ...", strclk());


            responseCount = 0;
            for (ID remoteId : remotes.keySet()) {
                try {
                    remotes.get(remoteId).receiveRequest(new Request(myClock, myId));
                } catch (RemoteException e) {
                    log.error("failed request to node {}", remoteId, e);
                }
            }
        }
    }

    @Override
    public String toString() {
        var builder = new StringBuilder();

        // print in format: 192.168.56.101:2010 - 2 peers: [192.168.56.102:2010, 192.168.56.103:2013]
        builder.append(String.format("%s %s | var=%d | %d peers: {", strclk(), myId, sharedVariable, remotes.size()));
        for (Iterator<ID> it = remotes.keySet().iterator(); it.hasNext(); ) {
            ID remoteId = it.next();
            builder.append(String.format(it.hasNext() ? "%s, " : "%s", remoteId));
        }
        builder.append("}");

        return builder.toString();
    }

    private String strclk() {
        return String.format("[clK:%s", myClock);
    }

    private Node tryLocateRemoteNode(ID id) {
        Node remoteNode = null;
        try {
            final var remoteRegistry = LocateRegistry.getRegistry(id.getIp(), id.getPort());
            remoteNode = (Node) remoteRegistry.lookup(id.getName());
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting registry at {}", id.getName(), e);
        }
        return remoteNode;
    }
}
