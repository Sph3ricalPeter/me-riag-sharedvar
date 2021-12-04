package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class App {
    public static void main(String[] args) {
        try {
            var thread = new Thread(args.length < 1 ? new NodeImpl() : new NodeImpl(args[0], Integer.parseInt(args[1])));
            thread.start();
        } catch (Exception e) {
            log.error("failed to start node", e);
        }
    }
}
