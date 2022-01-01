package cz.cvut.fel.dsv;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Getter
public class ID implements Serializable, Comparable<ID> {

    private final String ip;
    private final int port;

    @Setter
    private Integer n;

    public ID(String ip, int port) {
        this.ip = ip;
        this.port = port;
        n = null;
    }

    public ID(String ip, int port, int n) {
        this.ip = ip;
        this.port = port;
        this.n = n;
    }

    public String getName() {
        return String.format("%s:%d", ip, port);
    }

    @Override
    public String toString() {
        return String.format("N%d %s:%d", n, ip, port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ID id = (ID) o;
        return port == id.port && ip.equals(id.ip); // only equals on ip and port
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port); // only hash IP and port
    }

    @Override
    public int compareTo(ID o) {
        return n.compareTo(o.getN());
    }
}
