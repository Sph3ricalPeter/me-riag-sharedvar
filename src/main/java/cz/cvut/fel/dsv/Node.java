package cz.cvut.fel.dsv;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Node extends Remote, Serializable {

    void signIn(Node node) throws RemoteException;

    void signOut(Node node) throws RemoteException;

    void receiveRequest(int ts, int id) throws RemoteException;

    void receiveResponse(int lc, int id) throws RemoteException;

}
