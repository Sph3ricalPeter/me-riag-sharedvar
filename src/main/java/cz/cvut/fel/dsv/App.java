package cz.cvut.fel.dsv;

public class App {
    public static void main(String[] args) {
        new Thread(args.length < 1 ? new RiAgNode() : new RiAgNode(args[0], Integer.parseInt(args[1]))).start();
    }
}
