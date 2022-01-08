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
        var gateway = tryLocateRemoteNode(new ID(gatewayIp, gatewayPort));
        if (gateway.isEmpty())
            return;

        nMax = gateway.get().addNode(myId.getIp(), myId.getPort());
        if (nMax < 0) {
            log.error("rejected by gateway");
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

    @Override
    public void receivePoke() throws RemoteException {
        log.debug("received a poke, I'm still here");
    }

    /**
     * Adds a node to the remote node and its neighbors
     *
     * @param newNodeIp   ip of new node
     * @param newNodePort port of new node
     * @return number of the node being added, -1 if error occurs
     * @throws RemoteException
     */
    @Override
    public int addNode(String newNodeIp, int newNodePort) throws RemoteException {
        nMax += 1;
        final var newNodeId = new ID(newNodeIp, newNodePort, nMax);

        // skip if known
        if (hasRemote(newNodeId)) {
            log.info("node {} is already in the network", newNodeId);
            return -1;
        }

        var newNode = tryLocateRemoteNode(newNodeId);
        if (newNode.isEmpty())
            return -1;

        // add new node to remotes
        remotes.put(newNodeId, newNode.get());
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
        if (!hasRemote(nodeId)) {
            log.info("node is removed already");
            return;
        }

        remotes.remove(nodeId);
        remotesReqFlags.remove(nodeId);
        log.info("removed {} from the network", nodeId);

        var idSet = new HashSet<>(remotes.keySet());
        for (ID id : idSet) {
            log.info("idset val: {}", id);
        }
        for (Node remote : remotes.values()) {
            remote.updateRemotes(idSet);
        }
        log.info("{}", this);
    }

    @Override
    public void updateRemotes(HashSet<ID> updatedRemotes) throws RemoteException {
        // add new records from updated remotes
        for (ID newRemoteId : updatedRemotes) {
            if (hasRemote(newRemoteId) || newRemoteId.equals(myId)) // skip existing or myself
                continue;

            final var newNode = tryLocateRemoteNode(newRemoteId);
            if (newNode.isEmpty())
                continue;

            remotes.put(newRemoteId, newNode.get());
            remotesReqFlags.put(newRemoteId, false);
        }

        // remove old remotes
        /*var it = remotes.keySet().iterator();
        while (it.hasNext()) {
            var remoteId = it.next();*/

        for (ID id : updatedRemotes) {
            log.info("updated remote: {}", id);
        }

        var copy = new HashSet<>(remotes.keySet());
        for (ID remoteId : copy) {
            if (updatedRemotes.contains(remoteId))
                continue;

            remotes.remove(remoteId);
            remotesReqFlags.remove(remoteId);
            log.info("removed {} from remotes", remoteId);
        }
        log.info("updated remotes list {}", this);
    }

    @Override
    public void receiveRequest(Request request) throws RemoteException {
        try {
            final var sleepTime = Utils.getRandomLong(Config.REMOTE_CALL_DELAY_MIN_MS, Config.REMOTE_CALL_DELAY_MAX_MS);
            log.debug("receiving request takes {} ...", sleepTime);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.error("failed sleeping ...", e);
        }

        clockMax = Math.max(clockMax, request.getSenderClock());

        final var isBusy = !state.equals(State.RELEASED);
        final var isIncomingClockAhead = request.getSenderClock() > myClock;
        final var isClockSameAndReqNumHigher = (request.getSenderClock() == myClock && request.getSenderId().getN() > myId.getN());

        final var delay = isBusy && (isIncomingClockAhead || isClockSameAndReqNumHigher);

        log.debug("delay = stateEqualsRequesting={} && (isReqClockAhead={} || isClockSameAndReqNumHigher={}", state.equals(State.REQUESTING), isIncomingClockAhead, isClockSameAndReqNumHigher);

        // delay response until not busy, response happens after critical section
        if (delay) {
            remotesReqFlags.put(request.getSenderId(), true);
            log.info("{} received request and waiting ...", strclk());
            return;
        }

        var responseSent = sendResponse(request.getSenderId(), new Response());
        log.info("At {} received request from {}", strclk(), request.getSenderId());
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

        var copy = new HashSet<>(remotes.keySet());
        for (ID remoteId : copy) {
            var node = tryGetRemoteOrRemoveInactive(remoteId);
            if (node.isEmpty())
                continue;

            node.get().updateVariable(sharedVariable);

            // reply to requests
            if (remotesReqFlags.get(remoteId)) {
                remotesReqFlags.replace(remoteId, false);
                node.get().receiveResponse(new Response());
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
                    // find any remote that can remove me
                    var foundAndRemoved = false;
                    for (ID id : remotes.keySet()) {
                        var node = tryGetRemoteOrRemoveInactive(id);
                        if (node.isPresent()) {
                            try {
                                node.get().removeNode(myId);
                                foundAndRemoved = true;
                            } catch (RemoteException e) {
                                log.error("failed to sign out", e);
                            }
                            break;
                        }
                    }
                    if (!foundAndRemoved) {
                        log.info("there's no peer to sign out from or removal failed, just leaving ...");
                    }
                    remotes.clear();
                    remotesReqFlags.clear();
                    continue;
                case "login":
                    if (args.length < 3) {
                        log.warn("invalid arguments");
                        continue;
                    }
                    final var gateway = tryLocateRemoteNode(new ID(args[1], Integer.parseInt(args[2])));
                    if (gateway.isEmpty()) {
                        return;
                    }

                    try {
                        nMax = gateway.get().addNode(myId.getIp(), myId.getPort());
                        myId.setN(nMax);
                    } catch (RemoteException e) {
                        log.error("failed to add this node");
                    }
                    continue;
                case "exit":
                    System.exit(0);
                default:
                    break;
            }

            if (state == State.REQUESTING || state == State.HELD) {
                log.warn("can't request while requesting or while in CS...");
                continue;
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

            // more than one remote, wait for responses
            /*var it = remotes.keySet().iterator();
            while (it.hasNext()) {
                var remoteId = it.next();*/
            var copy = new HashSet<>(remotes.keySet());
            for (ID remoteId : copy) {
                var requestSuccess = sendRequest(remoteId, new Request(myClock, myId));
                log.info(this.toString());
                if (!requestSuccess) {
                    sendResponse(myId, new Response());
                }
                log.info("sent request to {} success={}", remoteId, requestSuccess);
            }

            // no remotes, respond to self to ender CS
            // check happens here, because first receiveRequest needs to fail in order for the non-existent node
            // to be removed
            if (remotes.size() < 1) {
                log.info("no peers in the network, self-requesting CS ...");
                try {
                    receiveResponse(new Response());
                } catch (RemoteException e) {
                    log.error("can't receive self-response");
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

    private boolean sendRequest(ID remoteId, Request request) {
        try {
            Optional<Node> remote = tryGetRemoteOrRemoveInactive(remoteId);
            if (remote.isEmpty()) {
                return false;
            }
            remote.get().receiveRequest(request);
        } catch (RemoteException e) {
            log.error("failed to send request to node {}", remoteId, e);
            return false;
        }
        return true;
    }

    private boolean sendResponse(ID remoteId, Response response) {
        if (remoteId.equals(myId)) {
            try {
                receiveResponse(response);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return true;
        }
        try {
            Optional<Node> remote = tryGetRemoteOrRemoveInactive(remoteId);
            if (remote.isEmpty()) {
                return false;
            }
            remote.get().receiveResponse(response);
        } catch (RemoteException e) {
            log.error("failed to send response to node {}", remoteId, e);
            return false;
        }
        return true;
    }

    private Optional<Node> tryLocateRemoteNode(ID id) {
        try {
            final var remoteRegistry = LocateRegistry.getRegistry(id.getIp(), id.getPort());
            return Optional.of((Node) remoteRegistry.lookup(id.getName()));
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting registry at {}", id.getName(), e);
        }
        return Optional.empty();
    }

    // returns node only if its in remotes and is poke-able
    private Optional<Node> tryGetRemoteOrRemoveInactive(ID remoteId) {
        if (!isActiveElseTryRemove(remoteId)) {
            return Optional.empty();
        }
        return Optional.of(remotes.get(remoteId));
    }

    private boolean isActiveElseTryRemove(ID remoteId) {
        if (!hasRemote(remoteId)) {
            return false;
        }
        try {
            // poke node to see if it's still there
            remotes.get(remoteId).receivePoke();
            return true;
        } catch (RemoteException e) {
            // it's not
            log.warn("node {} is not active", remoteId);
        }

        try {
            // try to remove it
            removeNode(remoteId);
        } catch (RemoteException e) {
            // can't remove, sigh ...
            log.error("failed to remove inactive node {}", remoteId);
        }

        // remote is not poke-able, so it's technically not there...
        return false;
    }

    // returns true if node with given remoteId is in remotes and is poke-able
    private boolean hasRemote(ID remoteId) {
        var copy = new HashSet<>(remotes.keySet());
        for (ID id : copy) {
            if (id.equals(remoteId)) {
                return true;
            }
        }
        // it's not even in remotes, lol
        return false;
    }

    private String strclk() {
        return String.format("[clK:%s", myClock);
    }
}
