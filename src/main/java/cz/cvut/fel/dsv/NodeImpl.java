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
    private State state;
    private int myClock;
    private int clockMax;
    private int responseCount;
    private Registry myRegistry;

    private volatile Integer requestedVariable;
    private volatile Integer sharedVariable;

    private int nMax;
    private Map<ID, Remote> remotes = new TreeMap<>();

    // create node without contacting gateway - create new network
    public NodeImpl(int port) throws RemoteException, UnknownHostException {
        super();
        init(port);
    }

    // create node with known gateway to the existing network
    public NodeImpl(int port, String gatewayIp, int gatewayPort) throws RemoteException, UnknownHostException {
        super();
        init(port);

        // find gateway node to fetch network info from
        var gateway = tryLocateRemoteNode(new ID(gatewayIp, gatewayPort));
        if (gateway.isEmpty())
            return;

        // add login into gateway
        nMax = gateway.get().addRemote(myId.getIp(), myId.getPort());
        if (nMax < 0) {
            log.error("rejected by gateway");
            return;
        }

        myId.setN(nMax);
        log.info("signed in to gateway and got nMax={}", nMax);
    }

    private void init(int port) throws UnknownHostException {
        nMax = 1;
        myId = new ID(InetAddress.getLocalHost().getHostAddress(), port, nMax);

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

        log.info("initialized new node {}", this);
    }

    @Override
    public int addRemote(String newNodeIp, int newNodePort) throws RemoteException {
        // increase nMax and create new node ID
        nMax += 1;
        final var newNodeId = new ID(newNodeIp, newNodePort, nMax);

        // skip if known
        synchronized (remotes) {
            if (hasRemote(newNodeId)) {
                // reset nMax so we don't skip N
                nMax -= 1;
                log.info("node {} is already in the network", newNodeId);
                return -1;
            }

            // locate node
            var newNode = tryLocateRemoteNode(newNodeId);
            if (newNode.isEmpty())
                return -1;

            // add new remote
            remotes.put(newNodeId, new Remote(newNode.get(), false));
        }

        remotes.get(newNodeId).getNode().updateVariable(sharedVariable);
        updateNetworkOnRemotes();

        log.info("added new node, new state is {}", this);
        return nMax;
    }

    @Override
    public void removeRemote(ID nodeId) throws RemoteException {
        synchronized (this) {
            if (!hasRemote(nodeId)) {
                log.info("node is removed already");
                return;
            }

            // remove node from remotes
            remotes.remove(nodeId);
        }

        updateNetworkOnRemotes();

        log.info("removed {} from the network", nodeId);
    }

    @Override
    public synchronized void updateRemotes(HashSet<ID> updatedRemotes) throws RemoteException {
        // add new records from updated remotes
        for (ID remoteId : updatedRemotes) {
            if (hasRemote(remoteId)) // skip existing
                continue;

            final var newNode = tryLocateRemoteNode(remoteId);
            if (newNode.isEmpty())
                continue;

            remotes.put(remoteId, new Remote(newNode.get(), false));
        }

        // remove excess remotes that are not in updated list
        var myRemoteIds = new HashSet<>(remotes.keySet());
        for (ID myRemoteId : myRemoteIds) {
            if (updatedRemotes.contains(myRemoteId))
                continue;

            remotes.remove(myRemoteId);
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
            log.error("failed sleeping ...");
        }

        synchronized (this) {
            clockMax = Math.max(clockMax, request.getSenderClock());

            final var isBusy = !state.equals(State.RELEASED);
            final var isIncomingClockAhead = request.getSenderClock() > myClock;
            final var isClockSameAndReqNumHigher = (request.getSenderClock() == myClock
                    && request.getSenderId().getN() > myId.getN());

            final var delay = isBusy && (isIncomingClockAhead || isClockSameAndReqNumHigher);

            log.debug("delay = isBusy={} && (isReqClockAhead={} || isClockSameAndReqNumHigher={}",
                    state.equals(State.REQUESTING), isIncomingClockAhead, isClockSameAndReqNumHigher);

            // delay response until not busy, response happens after critical section
            if (delay) {
                remotes.get(request.getSenderId()).setRequesting(true);
                log.info("Received request from {}, put it in waiting line ...", request.getSenderId());
                return;
            }
        }

        log.info("Received request from {}, replying instantly", request.getSenderId());
        var success = sendResponse(request.getSenderId(), new Response());
        if (!success) {
            // remove inactive node
            remotes.remove(request.getSenderId());
            updateNetworkOnRemotes();
        }
    }

    @Override
    public void receiveResponse(Response response) throws RemoteException {
        synchronized (this) {
            responseCount++;

            log.debug("reponse count is {}", responseCount);

            if (responseCount < remotes.size())
                return;

            // received all responses needed to enter critical section
            // CRITICAL SECTION
            state = State.HELD;

            log.info("{} HELD and changing variable {} to {} ...", strclk(), sharedVariable, requestedVariable);
            sharedVariable = requestedVariable;
        }

        try {
            var sleepTime = Utils.getRandomLong(Config.CRITICAL_WAIT_MIN_MS, Config.CRITICAL_WAIT_MAX_MS);
            log.info("sleeping for {} ms", sleepTime);
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.error("failed to sleep ...");
        }

        state = State.RELEASED;
        log.info("{} RELEASED and replying to requests ...", strclk());

        for (Remote remote : remotes.values()) {
            // update variable
            remote.getNode().updateVariable(sharedVariable);

            // reply to requests
            if (remote.isRequesting()) {
                remote.setRequesting(false);
                remote.getNode().receiveResponse(new Response());
            }
        }
    }

    @Override
    public synchronized void updateVariable(Integer payload) {
        sharedVariable = payload;
        log.info("updated variable to {}", sharedVariable);
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
                        log.warn("can't log out, this is the only node in the network");
                        continue;
                    }
                    // find any remote that can remove me

                    var success = false;
                    var remoteIds = new HashSet<>(remotes.keySet());
                    for (ID id : remoteIds) {
                        try {
                            remotes.get(id).getNode().removeRemote(myId);
                            success = true;
                            break;
                        } catch (RemoteException e) {
                            log.error("failed to sign out via node {}", id);
                        }
                    }
                    if (!success)
                        log.error("all remotes failed to sign me out ..");

                    remotes.clear();

                    continue;
                case "login":
                    if (args.length < 3) {
                        log.warn("invalid arguments");
                        continue;
                    }
                    final var gateway = tryLocateRemoteNode(new ID(args[1], Integer.parseInt(args[2])));
                    if (gateway.isEmpty())
                        continue;

                    try {
                        nMax = gateway.get().addRemote(myId.getIp(), myId.getPort());
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
                log.error("failed to parse input string to int");
                continue;
            }

            // change state to wanted, request access to critical section
            synchronized (this) {
                state = State.REQUESTING;
                myClock = clockMax + 1;
            }

            log.info("{} REQUESTING access ...", strclk());

            responseCount = 0;

            // no remotes, respond to self to ender CS
            // check happens here, because first receiveRequest needs to fail in order for
            // the non-existent node
            // to be removed
            if (remotes.size() < 1) {
                log.info("no peers in the network, self-requesting CS ...");
                try {
                    receiveResponse(new Response());
                } catch (RemoteException e) {
                    log.error("can't receive self-response");
                }
                continue;
            }

            // need to copy ids, because sendRequest can remove id during the for loop
            var remoteIds = new HashSet<>(remotes.keySet());
            var it = remoteIds.iterator();
            while (it.hasNext()) {
                var remoteId = it.next();
                var success = sendRequest(remoteId, new Request(myClock, myId));
                if (!success) {
                    // remove inactive node
                    remotes.remove(remoteId);
                    updateNetworkOnRemotes();

                    // if no more remotes and last one was not success, respond to self to trigger
                    // CS
                    if (!it.hasNext()) {
                        try {
                            // send response to self as replacement for the remote node
                            receiveResponse(new Response());
                        } catch (RemoteException e) {
                            log.error("failed to send request to self");
                        }
                        break;
                    }
                } else {
                    log.info("sent request to {}", remoteId);
                }
            }
        }
    }

    @Override
    public String toString() {
        var builder = new StringBuilder();

        // print in format: 192.168.56.101:2010 - 2 peers: [192.168.56.102:2010,
        // 192.168.56.103:2013]
        builder.append(String.format("\n+++++++++\n%s %s | var=%d\n%d peers: {\n", strclk(), myId, sharedVariable, remotes.size()));
        for (Iterator<ID> it = remotes.keySet().iterator(); it.hasNext();) {
            ID remoteId = it.next();
            builder.append(String.format(it.hasNext() ? "* %s,\n" : "%s\n", remoteId));
        }
        builder.append("}\n+++++++++");

        return builder.toString();
    }

    private void updateNetworkOnRemotes() {
        for (ID remoteId : remotes.keySet()) {
            // copy remotes
            var ids = new HashSet<>(remotes.keySet());

            // add self and remove remote (remote has different remotes than this node)
            ids.add(myId);
            ids.remove(remoteId);

            try {
                remotes.get(remoteId).getNode().updateRemotes(ids);
            } catch (RemoteException e) {
                log.error("can't update remotes for {}", remoteId);
            }
        }
    }

    private boolean sendRequest(ID remoteId, Request request) {
        try {
            remotes.get(remoteId).getNode().receiveRequest(request);
            return true;
        } catch (RemoteException e) {
            log.error("failed to send request to node {}", remoteId);
        }
        return false;
    }

    private boolean sendResponse(ID remoteId, Response response) {
        try {
            remotes.get(remoteId).getNode().receiveResponse(response);
            return true;
        } catch (RemoteException e) {
            log.error("failed to send response to node {}", remoteId);
        }
        return false;
    }

    private Optional<Node> tryLocateRemoteNode(ID id) {
        try {
            final var remoteRegistry = LocateRegistry.getRegistry(id.getIp(), id.getPort());
            return Optional.of((Node) remoteRegistry.lookup(id.getName()));
        } catch (RemoteException | NotBoundException e) {
            log.error("error contacting registry at {}", id.getName());
        }
        return Optional.empty();
    }

    // returns true if node with given remoteId is in remotes and is poke-able
    private boolean hasRemote(ID remoteId) {
        for (ID id : remotes.keySet()) {
            if (id.equals(remoteId)) {
                return true;
            }
        }
        // it's not even in remotes, lol
        return false;
    }

    private String strclk() {
        return String.format("[clK:%s]", myClock);
    }
}
