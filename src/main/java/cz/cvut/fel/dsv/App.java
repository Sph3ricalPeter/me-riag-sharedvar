package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class App {
    public static void main(String[] args) {
        try {
            Node node = args.length < 1 ? new RiAgNode() : new RiAgNode(args[0], Integer.parseInt(args[1]));
        } catch (Exception e) {
            log.error(e.getMessage());
        }
    }
}
