package cz.cvut.fel.dsv;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.util.Objects;

@AllArgsConstructor
@Getter
public class ID implements Serializable, Comparable<Integer> {

    private final Integer n;
    private final String ip;
    private final int port;

    @Override
    public String toString() {
        return String.format("N%d %s:%d", n, ip, port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ID id = (ID) o;
        return port == id.port && Objects.equals(n, id.n) && Objects.equals(ip, id.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(n, ip, port);
    }

    @Override
    public int compareTo(Integer o) {
        return n.compareTo(o);
    }
}
