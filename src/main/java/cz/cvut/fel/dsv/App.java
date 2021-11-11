package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

import java.rmi.RemoteException;

@Slf4j
public class App {

    public static void main(String[] args) {
        try {
            new Thread(new SimpleNode(Integer.parseInt(args[0]))).start();
        } catch (RemoteException e) {
            log.error("failed to initialize node");
            e.printStackTrace();
        }
    }
}
