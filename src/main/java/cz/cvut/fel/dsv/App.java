package cz.cvut.fel.dsv;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class App {
    public static void main(String[] args) {
        clearScreen();
        try {
            var node = args.length < 2 ? new NodeImpl(Integer.parseInt(args[0]))
                    : new NodeImpl(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]));
            node.run();
        } catch (Exception e) {
            log.error("failed to start node", e);
        }
    }

    public static void clearScreen() {  
        System.out.print("\033[H\033[2J");  
        System.out.flush();  
    }  
}
