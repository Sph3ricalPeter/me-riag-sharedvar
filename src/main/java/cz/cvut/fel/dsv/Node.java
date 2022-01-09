package cz.cvut.fel.dsv;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashSet;

public interface Node extends Remote, Serializable {

    int addRemote(String ip, int port) throws RemoteException;

    void removeRemote(ID id) throws RemoteException;

    void updateRemotes(HashSet<ID> remotes) throws RemoteException;

    void receiveRequest(Request request) throws RemoteException;

    void receiveResponse(Response response) throws RemoteException;

    void updateVariable(Integer payload) throws RemoteException;

}
