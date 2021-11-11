package cz.cvut.fel.dsv;

import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Node extends Remote {

    void receiveRequest(int ts, int id) throws NotBoundException, RemoteException, InterruptedException;

    void receiveResponse(int lc, int id) throws NotBoundException, RemoteException, InterruptedException;

}
