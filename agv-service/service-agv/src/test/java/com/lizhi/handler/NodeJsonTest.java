package com.lizhi.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lizhi.domain.po.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Node 的对外 JSON 与 planned_path 落库格式都只保留 x / y */
class NodeJsonTest {

    private static List<Node> chain() {
        Node a = new Node(1, 2);
        Node b = new Node(1, 3);
        b.setParent(a);
        b.setCost(7);
        b.setG(3);
        b.setH(4);
        return List.of(a, b);
    }

    @Test
    void jacksonResponseOnlyContainsCoordinates() throws Exception {
        assertEquals("[{\"x\":1,\"y\":2},{\"x\":1,\"y\":3}]", new ObjectMapper().writeValueAsString(chain()));
    }

    @Test
    void plannedPathColumnOnlyContainsCoordinates() {
        assertEquals("[{\"x\":1,\"y\":2},{\"x\":1,\"y\":3}]", NodeListTypeHandler.toCompactJson(chain()));
        assertEquals("[]", NodeListTypeHandler.toCompactJson(List.of()));
    }

    @Test
    void jacksonStillAcceptsLegacyPayloadWithExtraFields() throws Exception {
        Node node = new ObjectMapper().readValue("{\"x\":5,\"y\":6,\"cost\":1.5,\"parent\":{\"x\":4,\"y\":6}}", Node.class);
        assertEquals(new Node(5, 6), node);
    }
}
