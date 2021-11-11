package cz.cvut.fel.dsv;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.rmi.server.UnicastRemoteObject;

@Slf4j
@AllArgsConstructor
public class Node extends UnicastRemoteObject implements Site {

    private int id;
    private Timestamp timestamp;

    @Override
    public void request(Timestamp timestamp) {

    }

    @Override
    public void reply() {

    }

}
