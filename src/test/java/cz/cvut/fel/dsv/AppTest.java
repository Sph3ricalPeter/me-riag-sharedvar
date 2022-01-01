package cz.cvut.fel.dsv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.HashMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Unit test for simple App.
 */
public class AppTest
{
    /**
     * Rigorous Test :-)
     */
    @Test
    public void shouldAnswerWithTrue()
    {
        assertTrue( true );
    }

    @Test
    public void testIDEqualsAndHashCode() {
        var ip = "192.168.56.101";
        var port = 2010;
        var id1 = new ID(ip, port, 1);
        var id2 = new ID(ip, port, 2);
        var remotes = new TreeMap<ID, String>();

        // s1: ids are the same
        // should be as the are compared only by IP and port
        {
            var expected = true;
            var actual = id1.equals(id2);
            assertEquals(expected, actual);
        }

        // s2: hashcodes should equal
        {
            var expected = true;
            var actual = id1.hashCode() == id2.hashCode();
            assertEquals(expected, actual);
        }

        // s3: remotes should have both id1 and id2 because the IDs are the same based on hashCode and equals()
        {
            remotes.put(id1, "First Node"); // should put
            var expected = false;
            var actual = remotes.containsKey(id2); // comparison happens via compareTo instead of equal so ..
            System.out.println("id1=" + id1 + ", id2=" + id2 + ", hc1=" + id1.hashCode() + ", hc2=" + id2.hashCode());

            assertEquals(expected, actual);
        }
    }
}
