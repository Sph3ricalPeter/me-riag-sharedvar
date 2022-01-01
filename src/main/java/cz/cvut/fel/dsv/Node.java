package cz.cvut.fel.dsv;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashSet;

public interface Node extends Remote, Serializable {

    void receivePoke() throws RemoteException;

    int addNode(String ip, int port) throws RemoteException;

    void removeNode(ID id) throws RemoteException;

    void updateRemotes(HashSet<ID> remotes) throws RemoteException;

    void receiveRequest(Request request) throws RemoteException;

    void receiveResponse(Response response) throws RemoteException;

    void updateVariable(Integer payload) throws RemoteException;

}
