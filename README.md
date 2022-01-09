## Distributed system using Ricart-Agrawala mutual exclusion algorithm

### About
This project is an implementation of the Ricart-Agrawala mutual exclusion algorithm, used for sharing a variable across a distributed full-mesh network. It's built with Java 17 and uses RMI for node to node communication.

### Pre-requisites
* **Maven 3**
* **Java 17** or later
* N machines with static IP addresses setup in a network able to ping each other

### Installation & How to run
```shell
# create artifact using Maven
mvn install

# move to build directory
cd target

# run first node
./start.sh

# run N-th node
./start.sh -i <N> -a <gateway node ip address> -p <gateway node port>
```

#### Example
```shell
### Example for 3 nodes in a network 192.168.56.0 255.255.255.0 ###
# N = node, M = machine

# start N1 on M1 with ip 192.168.56.101:2010
./start.sh 
# start N2 on M2 with ip 192.168.56.102:2010, use N1 as gateway
./start.sh -i 2 -g 192.168.56.101 
# start N3 on M3 with ip 192.168.56.103:2010, use N2 as gateway
./start.sh -i 3 -g 192.168.56.102
```

### Usage
While running, the app accepts the following commands from the command line
```shell
# - any 32 bit integer
# if the node is currently not requesting or in CS=critical section, it will request access
13 

# - print
# prints the current state of the node and lists its neighbors
print

# - login <gateway ip> <gateway port>
# logs into an existing network via specified gateway ip and port
login 192.168.56.101 2010

# - logout
# logs out of the current network, unless it's the only node
logout

# - exit
# exits the app without logging out, nodes don't get immediately notified as with logout
exit
```

### Implementation notes

#### Algorithm
The algorithm used in this project is a simple version of  the [Ricart-Agrawala](https://en.wikipedia.org/wiki/Ricart%E2%80%93Agrawala_algorithm) mutual exclusion algorithm. 

Node requests access to critical section and awaits response from all of all its neighbors. Lossless communication is assumed, so each request will eventually get all responses. 

Node priority when entering CS is determined by its [Lamport timestamp](https://en.wikipedia.org/wiki/Lamport_timestamp). If the timestamp comparison doesn't suffice, node number N will be used to determine priority. If a node has lower priority and requests access to the CS, the node entering CS will contact all requesting nodes with lower priority after the CS has ended one by one, in order.

#### Node
Each node has its unique identifier which consists of IP, PORT and unique number N, which is assigned at the time of login by an existing node, or set to 1 if there's no network established yet.

Node can by in one of total of 3 states at any given time.
* `RELEASED` - the node is listening for requests, otherwise remains passive
* `REQUESTING` - the node is looking to write new value into the shared variable and has requested access to the critical section (CS)
* `HELD` - upon receiving all responses, the node enters CS and its state changes to `HELD`, after the CS the state is changed back to `RELEASED` and the variable is distributed to all neighbors

#### RMI interface
The node app uses RMI to create a registry on a specified port, which other nodes can locate and use to call its neighbor's methods. The exposed methods are specified by the `Node` interface, which implements the `Remote` interface provided by RMI. The `NodeImpl` class itself then extends `UnicastRemoteObject` and can be bound to the registry as shown in the example below.
```java
// node A creates a registry, passing itself as the object to register
myRegistry = LocateRegistry.createRegistry(A_PORT);
myRegistry.rebind("A_ID", this);


// node B locates the registry of node A
remoteRegistry = LocateRegistry.getRegistry(A_IP, A_PORT);
remoteNodeA = remoteRegistry.lookup("A_ID");

// node B can now call remote methods on node A
remoteNodeA.updateVariable(9);
```

The `Node` interface exposes the following methods:
* `receivePoke()` - method with `void` return type and no parameters, only used as a heartbeat check, whether a node is reachable
* `addNode(String ip, int port)` - locates a node on the specified IP address and port. If the node is successfully located `updateRemotes()` method is called on all neighbors
* `removeNode(ID id)` - removes a node with the specified ID from the netowrk, calls `updateRemotes()` on all neighbors to update the topology
* `updateRemotes(HashSet<ID> remotes)` - updates remotes to match the hashset given as parameter
* `receiveRequest(Request request)` - receives request to let the sender node enter CS
* `receiveResponse(Response response)` - receives response, letting the receiving node enter CS
* `updateVariable(Integerp payload)` - used to distribute the updated variable after CS

Because of the nature of RMI, everything happens sequentially.
In the example below can be seen an example of the communication occurring in a 3 node network:
```
N1 requests write 19
N1 --request-> N2
N1 --request-> N3
N1 <-response- N2
N1 <-response- N3
N1 enters CS
N1 writes 19
N1 --update--> N2
N1 --update--> N3
N1 exits CS
```

When multiple nodes request at the same time, nodes enter CS based on priority determined by the algorithm:
```
N1 requests write 19 && B requests write 21
N1 --request-> N2 && N2 --request-> N1
N1 and N2 have identical lamport timestamp, but N1 has lower N=1, so it takes priority
N1 remembers that N2 requested, N1 <-response- N2
N1 enters CS
N1 writes 19
N1 --update--> N2
N2 <-response- N1
N2 enters CS
N2 writes 21
N2 --update--> N1
N2 exits CS
```
