package cz.cvut.fel.dsv;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Getter
public class Response implements Serializable {

    private Integer payload;

}
