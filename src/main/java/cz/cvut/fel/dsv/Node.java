package cz.cvut.fel.dsv;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface Node extends Remote, Serializable {

    ID getIdentifier() throws RemoteException;

    int signIn(ID id) throws RemoteException;

    void signOut(ID id) throws RemoteException;

    void receiveRequest(Request request) throws RemoteException;

    void receiveResponse(Response response) throws RemoteException;

    void receivePayload(Integer payload) throws RemoteException;

}
