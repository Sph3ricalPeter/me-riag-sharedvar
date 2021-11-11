package cz.cvut.fel.dsv;

public interface Site {

    void request(Timestamp timestamp);

    void reply();

}
