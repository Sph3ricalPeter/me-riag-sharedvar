package cz.cvut.fel.dsv;

import lombok.Getter;

import java.util.Arrays;

import static cz.cvut.fel.dsv.Config.N_NODES;

// Lamport's logical clock timestamp
@Getter
public class Timestamp implements Comparable<Timestamp> {

    private final int[] vector;

    public Timestamp() {
        vector = new int[N_NODES];
        Arrays.fill(vector, 0);
    }

    public void increment(int id) {
        vector[id]++;
    }

    public Timestamp max(Timestamp t1, Timestamp t2) {
        var ret = new Timestamp();
        for (var i = 0; i < t1.vector.length; ++i) {
            ret.vector[i] = Math.max(t1.vector[i], t2.vector[i]);
        }
        return ret;
    }

    @Override
    public int compareTo(Timestamp o) {
        // 1 1 1 > 0 1 1
        // 2 1 1 < 2 2 1
        // 1 3 2 ? 1 2 5
        for (var i = 0; i < vector.length; ++i) {

        }
        return 0;
    }
}
